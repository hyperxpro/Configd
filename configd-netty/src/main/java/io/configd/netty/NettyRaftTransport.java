package io.configd.netty;

import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.transport.ConnectionManager;
import io.configd.transport.FrameCodec;
import io.configd.transport.InboundMessage;
import io.configd.transport.PeerIdentityPolicy;
import io.configd.transport.RaftTransport;
import io.configd.transport.RaftTransportEndpoint;
import io.configd.transport.RaftTransportMetrics;
import io.configd.transport.RaftWireProtocol;
import io.configd.transport.TlsConfig;
import io.configd.transport.TlsManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.uring.IoUringChannelOption;
import io.netty.channel.uring.IoUringSocketChannel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Netty consensus transport: transport-agnostic {@link RaftWireProtocol} wire over Netty pipelines
 * with identical safety properties as the JDK {@link io.configd.transport.TcpRaftTransport
 * TcpRaftTransport}, proven by {@code RaftTransportContract} across all tiers.
 *
 * <p><b>Inbound:</b> admission-capped before handshake (half-open flood cannot exhaust fds/threads).
 * <p><b>Outbound:</b> peer replies on this connection (JDK twin).
 * <p><b>Write path:</b> sends offer a bounded queue; one CAS-gated wake drains frames inline,
 * gated on {@link Channel#isWritable()} so slow peers back up into the queue.
 */
public final class NettyRaftTransport implements RaftTransportEndpoint {

    private static final Logger LOG = Logger.getLogger(NettyRaftTransport.class.getName());

    /**
     * Per-channel pinned peer {@link NodeId}, resolved from the peer's certificate on handshake
     * completion. Present only on server-accepted connections under an enforced
     * {@link PeerIdentityPolicy}; {@code channelRead0} binds each frame's {@code senderId} to it.
     */
    static final AttributeKey<NodeId> PEER_IDENTITY = AttributeKey.valueOf("configd.raft.peerIdentity");

    private final NodeId self;
    private final InetSocketAddress bindAddress;
    private final Map<NodeId, InetSocketAddress> peerAddresses;
    private final TlsManager tlsManager; // nullable for plaintext (test/single-node)
    private final Consumer<InboundMessage> inboundHandler; // nullable
    private final ConnectionManager connectionManager;

    /** Cert-identity &harr; NodeId binding policy. Default {@link PeerIdentityPolicy#unenforced()}. */
    private final PeerIdentityPolicy peerIdentityPolicy;
    /** Security-event sink (peer-identity rejections). Default {@link RaftTransportMetrics#NOOP}. */
    private final RaftTransportMetrics transportMetrics;
    /** Guards the one-time "peer-identity verification unconfigured" warning (unenforced-but-TLS posture). */
    private final AtomicBoolean unconfiguredWarningEmitted = new AtomicBoolean(false);

    private final NettyTransport.Selection transport;
    private final int workerThreads;
    private final int inboundReadTimeoutMs = RaftWireProtocol.inboundReadTimeoutMs();
    private final int maxInboundConnections = RaftWireProtocol.maxInboundConnections();
    /**
     * Kernel deadline (ms) for our outbound sends to be ACKed before the connection is failed - the
     * dead/restarted-peer detector for a send-only outbound (see the connect() comment). Well above a
     * healthy peer's millisecond ACK; tunable, default 10s so a restarted node rejoins promptly.
     */
    private static final int OUTBOUND_ACK_TIMEOUT_MS =
            Integer.getInteger("configd.raft.outboundAckTimeoutMs", 10_000);

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
     * Creates a Netty consensus transport with unenforced peer-identity and no metrics.
     *
     * @param self           this node's identity
     * @param bindAddress    listen address for inbound peer connections
     * @param peerAddresses  map of peer NodeIds to their listen addresses
     * @param tlsManager     TLS manager for mTLS, or null for plaintext
     * @param inboundHandler inbound message callback (may be null if using registerHandler)
     */
    public NettyRaftTransport(NodeId self,
                              InetSocketAddress bindAddress,
                              Map<NodeId, InetSocketAddress> peerAddresses,
                              TlsManager tlsManager,
                              Consumer<InboundMessage> inboundHandler) {
        // This constructor leaves peer-identity binding unenforced with no metrics sink; the
        // enforcement path stays dormant until an allow-list policy is supplied via the fuller
        // constructor below.
        this(self, bindAddress, peerAddresses, tlsManager, inboundHandler,
                PeerIdentityPolicy.unenforced(), RaftTransportMetrics.NOOP);
    }

    /**
     * Creates a Netty consensus transport with an explicit peer-identity binding policy and metrics
     * sink. When {@code peerIdentityPolicy} is {@linkplain PeerIdentityPolicy#enforced()
     * enforced}, an accepted peer's TLS cert identity is verified against the allow-list on handshake
     * completion and each frame's {@code senderId} must match the connection's resolved {@link NodeId};
     * otherwise the transport keeps its CA-chain-only behavior (with a one-time warning when TLS is on).
     *
     * @param peerIdentityPolicy cert-identity&harr;NodeId binding policy (never null)
     * @param transportMetrics   security-event sink (never null; pass {@link RaftTransportMetrics#NOOP})
     */
    public NettyRaftTransport(NodeId self,
                              InetSocketAddress bindAddress,
                              Map<NodeId, InetSocketAddress> peerAddresses,
                              TlsManager tlsManager,
                              Consumer<InboundMessage> inboundHandler,
                              PeerIdentityPolicy peerIdentityPolicy,
                              RaftTransportMetrics transportMetrics) {
        this.self = Objects.requireNonNull(self, "self must not be null");
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress must not be null");
        this.peerAddresses = Map.copyOf(Objects.requireNonNull(peerAddresses, "peerAddresses must not be null"));
        this.tlsManager = tlsManager;
        this.inboundHandler = inboundHandler;
        this.peerIdentityPolicy = Objects.requireNonNull(peerIdentityPolicy, "peerIdentityPolicy must not be null");
        this.transportMetrics = Objects.requireNonNull(transportMetrics, "transportMetrics must not be null");
        this.connectionManager = new ConnectionManager(Clock.system());
        this.transport = NettyTransport.select();
        this.workerThreads = Integer.getInteger("configd.raft.netty.workerThreads",
                Math.max(2, Runtime.getRuntime().availableProcessors()));
        for (NodeId peer : peerAddresses.keySet()) {
            connectionManager.addPeer(peer);
        }
    }

    /** The active transport tier (io_uring / epoll / nio) - surfaced for the startup log and CI proof. */
    public String transportTier() {
        return transport.tier();
    }

    @Override
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Transport already started");
        }
        // Enforced identity policy without mTLS fails OPEN — refuse to start rather than silently downgrade
        if (peerIdentityPolicy.enforced() && tlsManager == null) {
            running.set(false);
            throw new IllegalStateException(
                    "Raft peer-identity allow-list is configured but the transport is PLAINTEXT (no "
                            + "TlsManager); enforced identity binding requires mTLS. Refusing to start.");
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
                        ch.pipeline().addLast(new IdleStateHandler(
                                inboundReadTimeoutMs, 0, 0, TimeUnit.MILLISECONDS));
                        ch.pipeline().addLast(new RaftFrameDecoder());
                        ch.pipeline().addLast(new InboundHandler());
                    }
                });
        boolean bound = false;
        try {
            try {
                serverChannel = b.bind(bindAddress).sync().channel();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted binding NettyRaftTransport", e);
            }
            warnIfPeerIdentityUnconfigured();
            LOG.info(() -> "NettyRaftTransport listening on " + serverChannel.localAddress()
                    + (tlsManager != null ? " (mTLS)" : " (PLAINTEXT)") + " [tier=" + transport.tier() + "]");
            bound = true;
        } finally {
            if (!bound) {
                close();
            }
        }
    }

    /**
     * Emits a loud one-time warning when the transport runs mTLS but no peer-identity allow-list is
     * configured (enforce when configured, warn when not). In this posture a cert-valid peer
     * can still forge another node's {@code senderId}; only the CA-chain is checked. No warning for
     * plaintext (test/single-node) or when a policy is enforced.
     */
    private void warnIfPeerIdentityUnconfigured() {
        if (tlsManager != null && !peerIdentityPolicy.enforced()
                && unconfiguredWarningEmitted.compareAndSet(false, true)) {
            LOG.warning(() -> "Raft peer-identity verification is UNCONFIGURED ("
                    + PeerIdentityPolicy.ALLOWED_NODES_PROP + " unset); a cert-valid peer can forge "
                    + "another node's senderId. Configure an allow-list to enforce identity binding.");
        }
    }

    @Override
    public void send(NodeId target, Object message) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(message, "message must not be null");
        if (!running.get()) {
            return;
        }
        if (!(message instanceof FrameCodec.Frame frame)) {
            throw new IllegalArgumentException(
                    "NettyRaftTransport expects FrameCodec.Frame messages, got: " + message.getClass().getName());
        }
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
    public boolean peerIdentityEnforced() {
        return peerIdentityPolicy.enforced();
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Channel sc = serverChannel;
        if (sc != null) {
            // Await the listen-socket close BEFORE tearing down the event-loop groups. On the
            // io_uring tier a fire-and-forget close is abandoned when the ring shuts down, so the
            // listen FD lingers and a same-port rebind fails with EADDRINUSE. NIO and Epoll tolerate
            // fire-and-forget (close() is synchronous there) but we await unconditionally so the
            // semantics are identical across all tiers. close() is called from application threads
            // (server shutdown / tests), never from an event loop, so the await cannot deadlock.
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
            if (running.get()) {
                LOG.log(Level.WARNING, e, () -> "Inbound handler error from peer " + msg.from()
                        + " for frame " + msg.frame().messageType());
            }
        }
    }

    /** Server-mode mTLS handler. Package-private for the handshake-timeout regression test. */
    SslHandler newServerSslHandler() {
        SSLContext ctx = tlsManager.peerContext();
        SSLEngine engine = ctx.createSSLEngine();
        engine.setUseClientMode(false);
        engine.setNeedClientAuth(true); // mTLS REQUIRED: a peer with no/expired/untrusted cert is rejected
        applyTlsConfig(engine);
        return boundedHandshake(new SslHandler(engine));
    }

    /** Client-mode mTLS handler with hostname verification against {@code peer}. */
    SslHandler newClientSslHandler(InetSocketAddress peer) {
        SSLContext ctx = tlsManager.peerContext();
        SSLEngine engine = ctx.createSSLEngine(peer.getHostString(), peer.getPort());
        engine.setUseClientMode(true);
        applyTlsConfig(engine);
        SSLParameters params = engine.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS"); // enforce hostname verification
        engine.setSSLParameters(params);
        return boundedHandshake(new SslHandler(engine));
    }

    /**
     * Applies the shared bounded TLS handshake timeout ({@link RaftWireProtocol#HANDSHAKE_TIMEOUT_MS})
     * so both transports apply the same numbers. Without it the Netty default (10 s) would apply, so a
     * peer that completes the TCP connect but stalls mid-handshake holds the slot ~10 s instead of ~2 s.
     * The handshake runs on a worker event loop, never the consensus/tick thread, so this is a liveness
     * bound only. Pinned by {@code NettyRaftTransportHandshakeTimeoutTest}.
     */
    private static SslHandler boundedHandshake(SslHandler handler) {
        handler.setHandshakeTimeoutMillis(RaftWireProtocol.HANDSHAKE_TIMEOUT_MS);
        return handler;
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


    private final class InboundHandler extends SimpleChannelInboundHandler<InboundMessage> {

        /** This channel incremented {@link #liveInbound} (event-loop-only). */
        private boolean counted;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
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
            if (peerIdentityPolicy.enforced()) {
                NodeId pinned = ctx.channel().attr(PEER_IDENTITY).get();
                if (pinned == null || !pinned.equals(msg.from())) {
                    transportMetrics.onPeerIdentityRejected();
                    ctx.close();
                    return;
                }
            }
            dispatch(msg);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent) {
                ctx.close();
                return;
            }
            if (evt instanceof SslHandshakeCompletionEvent handshake) {
                if (handshake.isSuccess() && peerIdentityPolicy.enforced()) {
                    NodeId pinned = resolvePinnedIdentity(ctx);
                    if (pinned == null) {
                        transportMetrics.onPeerIdentityRejected();
                        ctx.close();
                        return;
                    }
                    ctx.channel().attr(PEER_IDENTITY).set(pinned);
                }
            }
            ctx.fireUserEventTriggered(evt);
        }

        /**
         * Resolves the peer's authorized {@link NodeId} from its verified certificate, per the policy's
         * marker mode. RDN mode (default) reads the Subject-DN marker, keeping an RDN deployment
         * byte-identical. SAN-URI mode reads the peer cert's SAN URI entries.
         */
        private NodeId resolvePinnedIdentity(ChannelHandlerContext ctx) {
            if (peerIdentityPolicy.usesSanUriMarker()) {
                return peerIdentityPolicy.resolveFromSanUris(resolvePeerCertificate(ctx));
            }
            return peerIdentityPolicy.resolve(resolveCertIdentity(ctx));
        }

        /**
         * The verified peer-certificate Subject DN on this channel's {@link SslHandler}, or
         * {@code null} if no verifiable peer certificate is present (fail-closed). Mirrors the edge
         * plane's {@code NettyFanOutServer.resolveCertIdentity}.
         */
        private String resolveCertIdentity(ChannelHandlerContext ctx) {
            SslHandler ssl = ctx.pipeline().get(SslHandler.class);
            if (ssl == null) {
                return null;
            }
            try {
                return ssl.engine().getSession().getPeerPrincipal().getName();
            } catch (Exception e) {
                return null; // no verifiable peer certificate
            }
        }

        /**
         * The verified peer end-entity {@link X509Certificate} on this channel's {@link SslHandler}, or
         * {@code null} if none is present (fail-closed). Used only for SAN-URI marker resolution.
         */
        private X509Certificate resolvePeerCertificate(ChannelHandlerContext ctx) {
            SslHandler ssl = ctx.pipeline().get(SslHandler.class);
            if (ssl == null) {
                return null;
            }
            try {
                Certificate[] chain = ssl.engine().getSession().getPeerCertificates();
                return (chain != null && chain.length > 0 && chain[0] instanceof X509Certificate x) ? x : null;
            } catch (Exception e) {
                return null; // no verifiable peer certificate
            }
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
            if (cause instanceof CorruptedFrameException) {
                transportMetrics.onInboundConnectionDropped();
            }
            ctx.close();
        }
    }

    /**
     * Manages a single outbound connection to a peer: a bounded queue the consensus thread offers to
     * (non-blocking), an async connect/handshake that never touches the consensus thread, and an
     * event-loop drain that writes inline (no {@code WriteTask}) gated on channel writability.
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
         * (drop-oldest on full, counted; Raft re-sends on the next heartbeat), then either wakes the
         * drain (if connected) or schedules an async connect. Never touches a socket.
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
                try {
                    ch.eventLoop().execute(() -> drain(ch));
                } catch (RejectedExecutionException rejected) {
                    // The worker event loop is shutting down (send() racing close(): send passed the
                    // running.get() check, then close() set running=false and shut the group down).
                    // Relinquish the flag so a later send can re-arm; the caller (tick thread) is
                    // unaffected - it only offers, never executes here.
                    drainScheduled.set(false);
                }
            }
        }

        /**
         * Runs ON the event loop. Writes queued frames inline (no {@code WriteTask}) while the channel
         * is writable, then flushes once. Re-arms only when there is more to send AND the channel is
         * writable - when not writable, {@link #channelWritabilityChanged} re-arms later, so there is no
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
                    ch.write(f); // inEventLoop -> inline, no WriteTask; the encoder encodes here
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

        /** Schedules an async connect on a worker event loop (never the consensus thread), honouring connection backoff. */
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
                    .option(ChannelOption.SO_KEEPALIVE, true)
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
            // Detect unACKed sends via TCP_USER_TIMEOUT on native tiers (absent on NIO)
            Class<? extends Channel> clientChannel = transport.clientChannelClass();
            if (clientChannel == EpollSocketChannel.class) {
                b.option(EpollChannelOption.TCP_USER_TIMEOUT, OUTBOUND_ACK_TIMEOUT_MS);
            } else if (clientChannel == IoUringSocketChannel.class) {
                b.option(IoUringChannelOption.TCP_USER_TIMEOUT, OUTBOUND_ACK_TIMEOUT_MS);
            }
            ChannelFuture cf = b.connect(address);
            cf.addListener((ChannelFuture f) -> {
                if (!f.isSuccess()) {
                    onConnectFailed();
                    return;
                }
                Channel ch = f.channel();
                SslHandler ssl = ch.pipeline().get(SslHandler.class);
                if (ssl != null) {
                    // TLS: publish + drain only after the handshake (incl. hostname check) succeeds.
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
                if (peerIdentityPolicy.enforced() && !target.equals(msg.from())) {
                    transportMetrics.onPeerIdentityRejected();
                    ctx.close();
                    return;
                }
                dispatch(msg);
            }

            @Override
            public void channelWritabilityChanged(ChannelHandlerContext ctx) {
                Channel ch = ctx.channel();
                if (ch.isWritable() && !queue.isEmpty()) {
                    scheduleDrain(ch); // outbound buffer drained below the low watermark - resume
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
                if (cause instanceof CorruptedFrameException) {
                    transportMetrics.onInboundConnectionDropped();
                }
                ctx.close();
            }
        }
    }
}
