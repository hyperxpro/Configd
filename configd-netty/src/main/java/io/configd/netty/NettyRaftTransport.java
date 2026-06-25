package io.configd.netty;

import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.transport.ConnectionManager;
import io.configd.transport.FrameCodec;
import io.configd.transport.InboundMessage;
import io.configd.transport.RaftTransport;
import io.configd.transport.RaftTransportEndpoint;
import io.configd.transport.RaftWireProtocol;
import io.configd.transport.TlsConfig;
import io.configd.transport.TlsManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Netty inter-node consensus transport (ADR-0043 M4) — the Netty twin of the JDK
 * {@link io.configd.transport.TcpRaftTransport TcpRaftTransport}, over the SAME transport-agnostic
 * {@link RaftWireProtocol} wire + framing + admission policy (DR-N16). Every safety property the JDK
 * transport carries is re-proven on this pipeline by the identical {@code RaftTransportContract}
 * (JDK + Netty(auto) + Netty(forced-NIO)) and the mTLS-attack / slowloris / blackhole / non-blocking
 * contracts — not re-implemented.
 *
 * <h2>Why this surface is dangerous</h2>
 * The coalesced-heartbeat timing (Phase 0 M3) rides this transport: a frame dropped/delayed/reordered
 * here can trip a follower election (the churn-collapse Phase 0 removed). So the design preserves the
 * JDK transport's exact delivery semantics — non-blocking send off the consensus thread (RR-002),
 * per-peer FIFO ordering, and drop-on-overflow (Raft re-sends on the next heartbeat).
 *
 * <h2>Pipelines</h2>
 * <ul>
 *   <li><b>Inbound (accepted):</b> {@code [SslHandler(server)? → IdleStateHandler → RaftFrameDecoder
 *       → InboundHandler]}. The {@link IdleStateHandler} is the F-S7-FUZZ-1 read-idle deadline (the
 *       JDK {@code setSoTimeout}); admission is bounded in {@code channelActive} <b>before</b> the
 *       handshake (a half-open flood cannot exhaust fds/threads).</li>
 *   <li><b>Outbound (connect to a peer):</b> {@code [SslHandler(client)? → RaftFrameDecoder →
 *       NettyConsensusFrameEncoder → PeerHandler]}. The encoder is the in-pipeline ~0-B/op encode
 *       (DR-N17); the peer may send responses back on this connection (decoded + dispatched), exactly
 *       like the JDK reader on the outbound socket.</li>
 * </ul>
 *
 * <h2>mTLS (DR-N18)</h2>
 * Both directions build an {@link SslHandler} from the SAME {@link TlsManager} {@link SSLContext}.
 * Server: {@code setUseClientMode(false)} + {@code setNeedClientAuth(true)} + TLSv1.3-only. Client:
 * {@code setUseClientMode(true)} + {@code setEndpointIdentificationAlgorithm("HTTPS")} (F-0051
 * hostname verification — the bidirectional delta the JDK-only fan-out client never needed). No
 * {@link TlsManager} ⇒ plaintext (no {@code SslHandler}), matching the JDK transport (test-only).
 *
 * <h2>The ~0-B/op write path (DR-N17)</h2>
 * {@link #send} (the consensus/tick thread) does a non-blocking {@code offer} onto a per-peer bounded
 * {@link ArrayBlockingQueue} (== the JDK {@code OUTBOUND_QUEUE_CAPACITY} ring; drop-oldest on full,
 * counted) and at most one CAS-gated wake of the channel's event loop. The drain runs <b>on the event
 * loop</b>, writing each frame inline ({@code ch.write} from {@code inEventLoop()} ⇒ no per-message
 * {@code WriteTask} — the verdict's 0.0-B/msg path) and flushing once. Draining is gated on
 * {@link Channel#isWritable()} so a slow/blackholed peer backs up into the bounded queue (→ drop), the
 * Netty equivalent of the JDK writer blocking on a full socket; {@code channelWritabilityChanged}
 * re-arms when the outbound buffer drains.
 */
public final class NettyRaftTransport implements RaftTransportEndpoint {

    private static final Logger LOG = Logger.getLogger(NettyRaftTransport.class.getName());

    private final NodeId self;
    private final InetSocketAddress bindAddress;
    private final Map<NodeId, InetSocketAddress> peerAddresses;
    private final TlsManager tlsManager; // nullable for plaintext (test/single-node)
    private final Consumer<InboundMessage> inboundHandler; // nullable
    private final ConnectionManager connectionManager;

    private final NettyTransport.Selection transport;
    private final int workerThreads;
    private final int inboundReadTimeoutMs = RaftWireProtocol.inboundReadTimeoutMs();
    private final int maxInboundConnections = RaftWireProtocol.maxInboundConnections();

    private final ConcurrentHashMap<NodeId, PeerChannel> peers = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong framesDropped = new AtomicLong();
    private final AtomicLong inboundConnectionsRefused = new AtomicLong();
    /** Live accepted inbound connections INCLUDING mid-handshake (the cap is applied before handshake). */
    private final AtomicInteger liveInbound = new AtomicInteger();

    private volatile EventLoopGroup boss;
    private volatile EventLoopGroup worker;
    private volatile Channel serverChannel;
    private volatile RaftTransport.MessageHandler messageHandler;

    /**
     * Creates a Netty consensus transport.
     *
     * @param self           this node's identity (the 4-byte wire sender-id prefix)
     * @param bindAddress    the address to listen on for inbound peer connections
     * @param peerAddresses  map of peer NodeIds to their listen addresses
     * @param tlsManager     TLS manager for mTLS, or null for plaintext (test only)
     * @param inboundHandler callback invoked when a message arrives (may be null if using registerHandler)
     */
    public NettyRaftTransport(NodeId self,
                              InetSocketAddress bindAddress,
                              Map<NodeId, InetSocketAddress> peerAddresses,
                              TlsManager tlsManager,
                              Consumer<InboundMessage> inboundHandler) {
        this.self = Objects.requireNonNull(self, "self must not be null");
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress must not be null");
        this.peerAddresses = Map.copyOf(Objects.requireNonNull(peerAddresses, "peerAddresses must not be null"));
        this.tlsManager = tlsManager;
        this.inboundHandler = inboundHandler;
        this.connectionManager = new ConnectionManager(Clock.system());
        this.transport = NettyTransport.select();
        this.workerThreads = Integer.getInteger("configd.raft.netty.workerThreads",
                Math.max(2, Runtime.getRuntime().availableProcessors()));
        for (NodeId peer : peerAddresses.keySet()) {
            connectionManager.addPeer(peer);
        }
    }

    /** The active transport tier (io_uring / epoll / nio) — surfaced for the startup log + CI proof. */
    public String transportTier() {
        return transport.tier();
    }

    @Override
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Transport already started");
        }
        IoHandlerFactory ioFactory = transport.ioHandlerFactory();
        boss = new MultiThreadIoEventLoopGroup(1, ioFactory);
        worker = new MultiThreadIoEventLoopGroup(workerThreads, ioFactory);
        ServerBootstrap b = new ServerBootstrap()
                .group(boss, worker)
                .channel(transport.serverChannelClass())
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        if (tlsManager != null) {
                            ch.pipeline().addLast(newServerSslHandler());
                        }
                        // F-S7-FUZZ-1: read-idle deadline (the JDK setSoTimeout). A stalled/slow-drip
                        // peer makes no read progress within the window → READER_IDLE → close.
                        ch.pipeline().addLast(new IdleStateHandler(
                                inboundReadTimeoutMs, 0, 0, TimeUnit.MILLISECONDS));
                        ch.pipeline().addLast(new RaftFrameDecoder());
                        ch.pipeline().addLast(new InboundHandler());
                    }
                });
        try {
            serverChannel = b.bind(bindAddress).sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted binding NettyRaftTransport", e);
        }
        LOG.info(() -> "NettyRaftTransport listening on " + serverChannel.localAddress()
                + (tlsManager != null ? " (mTLS)" : " (PLAINTEXT)") + " [tier=" + transport.tier() + "]");
    }

    @Override
    public void send(NodeId target, Object message) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(message, "message must not be null");
        if (!running.get()) {
            return; // closed → silently drop (Raft re-sends); matches the JDK transport
        }
        if (!(message instanceof FrameCodec.Frame frame)) {
            throw new IllegalArgumentException(
                    "NettyRaftTransport expects FrameCodec.Frame messages, got: " + message.getClass().getName());
        }
        // RR-002: runs on the consensus (tick) thread and MUST NOT block. computeIfAbsent throws
        // IllegalArgumentException for an unknown peer (matching TcpRaftTransport.createPeerConnection).
        PeerChannel pc = peers.computeIfAbsent(target, t -> {
            InetSocketAddress addr = peerAddresses.get(t);
            if (addr == null) {
                throw new IllegalArgumentException("Unknown peer: " + t);
            }
            return new PeerChannel(t, addr);
        });
        pc.enqueueOrDrop(frame);
    }

    @Override
    public void registerHandler(MessageHandler handler) {
        this.messageHandler = handler;
    }

    @Override
    public int localPort() {
        Channel ch = serverChannel;
        if (ch == null) {
            throw new IllegalStateException("Transport not started");
        }
        return (ch.localAddress() instanceof InetSocketAddress addr) ? addr.getPort() : -1;
    }

    @Override
    public TlsManager tlsManager() {
        return tlsManager;
    }

    @Override
    public long framesDropped() {
        return framesDropped.get();
    }

    @Override
    public long inboundConnectionsRefused() {
        return inboundConnectionsRefused.get();
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Channel sc = serverChannel;
        if (sc != null) {
            // Await the listen-socket close BEFORE tearing down the event-loop groups. A
            // fire-and-forget close is abandoned on the io_uring tier when the ring shuts down, so the
            // listen FD lingers and a same-port rebind fails (proven by the contract's
            // reconnectionAfterConnectionDrop on io_uring); JDK ServerSocket.close() is synchronous, so
            // this restores that semantics across all tiers. close() is called from application threads
            // (ConfigdServer shutdown / tests), never an event loop, so the await cannot deadlock.
            sc.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        }
        for (PeerChannel pc : peers.values()) {
            pc.close();
        }
        peers.clear();
        EventLoopGroup b = boss;
        EventLoopGroup w = worker;
        if (b != null) {
            b.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
        if (w != null) {
            w.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
    }

    // ---- shared inbound dispatch (handler-throw does NOT desync; the JDK rule) ----

    private void dispatch(InboundMessage msg) {
        try {
            if (inboundHandler != null) {
                inboundHandler.accept(msg);
            }
            MessageHandler handler = messageHandler;
            if (handler != null) {
                handler.onMessage(msg.from(), msg.frame());
            }
        } catch (RuntimeException e) {
            // A handler throw is logged but does NOT close the channel — the framing layer is intact,
            // so we keep reading (mirrors TcpRaftTransport.handleInboundConnection's inner try/catch).
            if (running.get()) {
                LOG.log(Level.WARNING, e, () -> "Inbound handler error from peer " + msg.from()
                        + " for frame " + msg.frame().messageType());
            }
        }
    }

    // ---- mTLS handlers (DR-N18) ----

    /** Server-mode mTLS handler — same SSLContext/protocols/ciphers as the JDK server (DR-N13). */
    private SslHandler newServerSslHandler() {
        SSLContext ctx = tlsManager.currentContext();
        SSLEngine engine = ctx.createSSLEngine();
        engine.setUseClientMode(false);
        engine.setNeedClientAuth(true); // mTLS REQUIRED: a peer with no/expired/untrusted cert is rejected
        applyTlsConfig(engine);
        return new SslHandler(engine);
    }

    /**
     * Client-mode mTLS handler with F-0051 hostname verification against {@code peer}. The engine is
     * created with the advisory peer host/port (for SNI + endpoint identification), mirroring the JDK
     * {@code createClientSocket}. Without the {@code HTTPS} algorithm any trust-store-signed cert is
     * accepted, defeating peer pinning.
     */
    private SslHandler newClientSslHandler(InetSocketAddress peer) {
        SSLContext ctx = tlsManager.currentContext();
        SSLEngine engine = ctx.createSSLEngine(peer.getHostString(), peer.getPort());
        engine.setUseClientMode(true);
        applyTlsConfig(engine);
        SSLParameters params = engine.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS"); // F-0051 hostname verification
        engine.setSSLParameters(params);
        return new SslHandler(engine);
    }

    private void applyTlsConfig(SSLEngine engine) {
        TlsConfig cfg = tlsManager.config();
        if (cfg != null) {
            if (!cfg.protocols().isEmpty()) {
                engine.setEnabledProtocols(cfg.protocols().toArray(String[]::new));
            }
            if (!cfg.ciphers().isEmpty()) {
                engine.setEnabledCipherSuites(cfg.ciphers().toArray(String[]::new));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Inbound (accepted) connection handler — admission + dispatch + idle close
    // -----------------------------------------------------------------------

    private final class InboundHandler extends SimpleChannelInboundHandler<InboundMessage> {

        /** This channel incremented {@link #liveInbound} (event-loop-only). */
        private boolean counted;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            // F-S7-FUZZ-1: cap concurrent inbound connections BEFORE the handshake, so a flood of
            // half-open sockets cannot exhaust fds/threads. Over the bound → refuse + close + count.
            counted = true;
            if (liveInbound.incrementAndGet() > maxInboundConnections) {
                inboundConnectionsRefused.incrementAndGet();
                liveInbound.decrementAndGet();
                counted = false;
                ctx.close();
                return;
            }
            ctx.fireChannelActive();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, InboundMessage msg) {
            dispatch(msg);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent) {
                // F-S7-FUZZ-1: read-idle deadline tripped — a slow-drip / stalled peer. Drop it
                // (releases the reader + fd), exactly like the JDK SocketTimeoutException path.
                ctx.close();
                return;
            }
            ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (counted) {
                liveInbound.decrementAndGet();
                counted = false;
            }
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // Decode desync (CorruptedFrameException) or transport error → drop the connection.
            ctx.close();
        }
    }

    // -----------------------------------------------------------------------
    // Per-peer outbound connection — the ~0-B/op event-loop-driven write path
    // -----------------------------------------------------------------------

    /**
     * Manages a single outbound connection to a peer: a bounded queue the consensus thread offers to
     * (non-blocking), an async connect/handshake that never touches the consensus thread (RR-002), and
     * an event-loop drain that writes inline (no {@code WriteTask}) gated on channel writability.
     */
    private final class PeerChannel {
        private final NodeId target;
        private final InetSocketAddress address;
        private final ArrayBlockingQueue<FrameCodec.Frame> queue =
                new ArrayBlockingQueue<>(RaftWireProtocol.OUTBOUND_QUEUE_CAPACITY);
        private final AtomicBoolean connectInFlight = new AtomicBoolean(false);
        private final AtomicBoolean drainScheduled = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile Channel channel;

        PeerChannel(NodeId target, InetSocketAddress address) {
            this.target = target;
            this.address = address;
        }

        /**
         * Non-blocking; called on the consensus (tick) thread. Offers onto the bounded queue
         * (drop-oldest on full, counted — Raft re-sends), then either wakes the drain (if connected) or
         * schedules an async connect. Never touches a socket (RR-002).
         */
        void enqueueOrDrop(FrameCodec.Frame frame) {
            if (closed.get()) {
                framesDropped.incrementAndGet();
                return;
            }
            if (!queue.offer(frame)) {
                queue.poll(); // drop the OLDEST to favour fresher Raft state, then retry once
                framesDropped.incrementAndGet();
                if (!queue.offer(frame)) {
                    framesDropped.incrementAndGet();
                }
            }
            Channel ch = channel;
            if (ch != null && ch.isActive()) {
                scheduleDrain(ch);
            } else {
                scheduleConnect();
            }
        }

        /** CAS-gated wake of the event loop: at most one drain in flight, so under load this is ~0 alloc. */
        private void scheduleDrain(Channel ch) {
            if (drainScheduled.compareAndSet(false, true)) {
                ch.eventLoop().execute(() -> drain(ch));
            }
        }

        /**
         * Runs ON the event loop. Writes queued frames inline (no {@code WriteTask}) while the channel
         * is writable, then flushes once. Re-arms only when there is more to send AND the channel is
         * writable — when not writable, {@link #channelWritabilityChanged} re-arms later, so there is no
         * busy loop; meanwhile the queue fills and overflows drop (the JDK blocked-writer equivalent).
         */
        private void drain(Channel ch) {
            for (;;) {
                boolean wrote = false;
                while (ch.isActive() && ch.isWritable()) {
                    FrameCodec.Frame f = queue.poll();
                    if (f == null) {
                        break;
                    }
                    ch.write(f); // inEventLoop ⇒ inline, no WriteTask; the encoder encodes here
                    wrote = true;
                }
                if (wrote) {
                    ch.flush();
                }
                drainScheduled.set(false);
                if (ch.isActive() && ch.isWritable() && !queue.isEmpty()
                        && drainScheduled.compareAndSet(false, true)) {
                    continue; // a producer enqueued after our last poll but before we cleared the flag
                }
                return;
            }
        }

        /** Schedules an async connect on a worker event loop (never the consensus thread), honouring backoff. */
        private void scheduleConnect() {
            if (closed.get() || !running.get()) {
                return;
            }
            if (!connectInFlight.compareAndSet(false, true)) {
                return; // a connect is already in flight for this peer
            }
            long delayMs;
            synchronized (connectionManager) {
                delayMs = connectionManager.backoffRemainingMs(target);
            }
            try {
                worker.schedule(this::connect, delayMs, TimeUnit.MILLISECONDS);
            } catch (RuntimeException rejected) {
                connectInFlight.set(false); // group shutting down; relinquish
            }
        }

        /** Runs on a worker event loop. Opens the connection; publishes the channel only after the
         *  (TLS) handshake succeeds, then drains the queued frames. */
        private void connect() {
            if (closed.get() || !running.get()) {
                connectInFlight.set(false);
                return;
            }
            Bootstrap b = new Bootstrap()
                    .group(worker)
                    .channel(transport.clientChannelClass())
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, RaftWireProtocol.CONNECT_TIMEOUT_MS)
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            if (tlsManager != null) {
                                ch.pipeline().addLast(newClientSslHandler(address));
                            }
                            ch.pipeline().addLast(new RaftFrameDecoder());
                            ch.pipeline().addLast(new NettyConsensusFrameEncoder(self.id()));
                            ch.pipeline().addLast(new PeerHandler());
                        }
                    });
            ChannelFuture cf = b.connect(address);
            cf.addListener((ChannelFuture f) -> {
                if (!f.isSuccess()) {
                    onConnectFailed();
                    return;
                }
                Channel ch = f.channel();
                SslHandler ssl = ch.pipeline().get(SslHandler.class);
                if (ssl != null) {
                    // TLS: publish + drain only after the handshake (incl. F-0051 hostname check) succeeds.
                    ssl.handshakeFuture().addListener(hf -> {
                        if (hf.isSuccess()) {
                            onConnected(ch);
                        } else {
                            ch.close();
                            onConnectFailed();
                        }
                    });
                } else {
                    onConnected(ch); // plaintext: TCP connect is enough
                }
            });
        }

        private void onConnected(Channel ch) {
            if (closed.get() || !running.get()) {
                ch.close();
                connectInFlight.set(false);
                return;
            }
            this.channel = ch;
            synchronized (connectionManager) {
                connectionManager.markConnected(target);
            }
            connectInFlight.set(false);
            scheduleDrain(ch); // flush whatever queued while connecting
        }

        private void onConnectFailed() {
            synchronized (connectionManager) {
                connectionManager.markDisconnected(target);
            }
            connectInFlight.set(false);
            if (!closed.get() && running.get() && !queue.isEmpty()) {
                scheduleConnect(); // retry after the (now larger) backoff so queued frames are delivered
            }
        }

        /** Connection lost: clear the published channel and reconnect if frames remain. */
        private void onChannelClosed(Channel ch) {
            if (this.channel == ch) {
                this.channel = null;
                if (!closed.get() && running.get()) {
                    synchronized (connectionManager) {
                        connectionManager.markDisconnected(target);
                    }
                    if (!queue.isEmpty()) {
                        scheduleConnect();
                    }
                }
            }
        }

        void close() {
            closed.set(true);
            Channel ch = channel;
            if (ch != null) {
                ch.close();
            }
            channel = null;
            queue.clear();
        }

        /** The outbound channel's tail handler: dispatch peer responses, re-arm drain on writability. */
        private final class PeerHandler extends SimpleChannelInboundHandler<InboundMessage> {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, InboundMessage msg) {
                dispatch(msg); // a peer may reply on the connection we opened (JDK reads here too)
            }

            @Override
            public void channelWritabilityChanged(ChannelHandlerContext ctx) {
                Channel ch = ctx.channel();
                if (ch.isWritable() && !queue.isEmpty()) {
                    scheduleDrain(ch); // outbound buffer drained below the low watermark → resume
                }
                ctx.fireChannelWritabilityChanged();
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                onChannelClosed(ctx.channel());
                ctx.fireChannelInactive();
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                ctx.close(); // decode desync / transport error → drop; reconnect on the next send
            }
        }
    }
}
