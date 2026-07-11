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
 * Netty inter-node consensus transport - the Netty twin of the JDK
 * {@link io.configd.transport.TcpRaftTransport TcpRaftTransport}, over the SAME transport-agnostic
 * {@link RaftWireProtocol} wire, framing, and admission policy. Every safety property the JDK
 * transport carries is re-proven on this pipeline by the identical {@code RaftTransportContract}
 * (JDK + Netty(auto) + Netty(forced-NIO)) and the mTLS-attack / slowloris / blackhole /
 * non-blocking contracts.
 *
 * <h2>Pipelines</h2>
 * <ul>
 *   <li><b>Inbound (accepted):</b> {@code [SslHandler(server)? -&gt; IdleStateHandler -&gt;
 *       RaftFrameDecoder -&gt; InboundHandler]}. The {@link IdleStateHandler} is the read-idle
 *       deadline (the Netty equivalent of the JDK {@code setSoTimeout}); admission is bounded in
 *       {@code channelActive} <b>before</b> the handshake (a half-open flood cannot exhaust
 *       fds/threads).</li>
 *   <li><b>Outbound (connect to a peer):</b> {@code [SslHandler(client)? -&gt; RaftFrameDecoder
 *       -&gt; NettyConsensusFrameEncoder -&gt; PeerHandler]}. The peer may send responses back on
 *       this connection (decoded and dispatched), exactly like the JDK reader on the outbound
 *       socket.</li>
 * </ul>
 *
 * <h2>mTLS</h2>
 * Both directions build an {@link SslHandler} from the same {@link TlsManager} {@link SSLContext}.
 * Server: {@code setUseClientMode(false)} + {@code setNeedClientAuth(true)} + TLSv1.3-only. Client:
 * {@code setUseClientMode(true)} + {@code setEndpointIdentificationAlgorithm("HTTPS")} for
 * hostname verification. No {@link TlsManager} = plaintext (no {@code SslHandler}), matching the
 * JDK transport for tests.
 *
 * <h2>The ~0-B/op write path</h2>
 * {@link #send} (the consensus/tick thread) does a non-blocking {@code offer} onto a per-peer bounded
 * {@link ArrayBlockingQueue} (drop-oldest on full, counted) and at most one CAS-gated wake of the
 * channel's event loop. The drain runs <b>on the event loop</b>, writing each frame inline
 * ({@code ch.write} from {@code inEventLoop()} - no per-message {@code WriteTask}) and flushing
 * once. Draining is gated on {@link Channel#isWritable()} so a slow/blackholed peer backs up into
 * the bounded queue (overflow = drop); {@code channelWritabilityChanged} re-arms when the outbound
 * buffer drains.
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
        // Fail closed BEFORE binding: an enforced allow-list without mTLS cannot bind identity to a
        // certificate, so it would fail OPEN. Refuse to start rather than silently downgrade. After
        // this, enforced() implies mTLS on both transports (parity with the JDK transport).
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
                        // Read-idle deadline (the JDK setSoTimeout equivalent). A stalled/slow-drip
                        // peer makes no read progress within the window -> READER_IDLE -> close.
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
                // A mid-start failure (bind refused / port in use, including a BindException sneak-thrown by
                // sync()) must not leak the non-daemon boss/worker event loops just created. close() resets
                // running and shuts them (it is idempotent, and serverChannel is null-guarded), so a failed
                // start() leaves nothing behind; the original failure propagates.
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
            return; // closed - silently drop (Raft re-sends); matches the JDK transport
        }
        if (!(message instanceof FrameCodec.Frame frame)) {
            throw new IllegalArgumentException(
                    "NettyRaftTransport expects FrameCodec.Frame messages, got: " + message.getClass().getName());
        }
        // Runs on the consensus (tick) thread and MUST NOT block. computeIfAbsent throws
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
            // A handler throw is logged but does NOT close the channel: the framing layer is intact,
            // so we keep reading (mirrors TcpRaftTransport.handleInboundConnection's inner try/catch).
            if (running.get()) {
                LOG.log(Level.WARNING, e, () -> "Inbound handler error from peer " + msg.from()
                        + " for frame " + msg.frame().messageType());
            }
        }
    }

    /** Server-mode mTLS handler. Package-private for the handshake-timeout regression test. */
    SslHandler newServerSslHandler() {
        // peerContext(): the Raft interior may use a SEPARATE peer trust anchor. Identical to
        // currentContext() unless configd.raft.peerIdentity.trustStore is set (byte-identical then).
        SSLContext ctx = tlsManager.peerContext();
        SSLEngine engine = ctx.createSSLEngine();
        engine.setUseClientMode(false);
        engine.setNeedClientAuth(true); // mTLS REQUIRED: a peer with no/expired/untrusted cert is rejected
        applyTlsConfig(engine);
        return boundedHandshake(new SslHandler(engine));
    }

    /**
     * Client-mode mTLS handler with hostname verification against {@code peer}. The engine is created
     * with the advisory peer host/port (for SNI and endpoint identification), mirroring the JDK
     * {@code createClientSocket}. Without the {@code HTTPS} algorithm any trust-store-signed cert is
     * accepted, defeating peer pinning.
     */
    SslHandler newClientSslHandler(InetSocketAddress peer) {
        // peerContext(): the Raft interior may use a SEPARATE peer trust anchor (see newServerSslHandler).
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

    // Inbound (accepted) connection handler - admission, dispatch, and idle close.

    private final class InboundHandler extends SimpleChannelInboundHandler<InboundMessage> {

        /** This channel incremented {@link #liveInbound} (event-loop-only). */
        private boolean counted;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            // Cap concurrent inbound connections BEFORE the handshake, so a flood of half-open
            // sockets cannot exhaust fds/threads. Over the bound: refuse + close + count.
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
            // Layer 2: the self-declared senderId prefix must equal the connection's authenticated
            // cert identity (pinned on handshake). A cert-valid peer forging another node's id is
            // dropped (desync-equivalent) and counted. Only active when an allow-list is enforced;
            // the JDK transport applies the identical check in handleInboundConnection.
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
                // Read-idle deadline tripped: a slow-drip / stalled peer. Drop the connection
                // (releases the reader + FD), exactly like the JDK SocketTimeoutException path.
                ctx.close();
                return;
            }
            if (evt instanceof SslHandshakeCompletionEvent handshake) {
                // Layer 1: on a successful mTLS handshake under an enforced allow-list, resolve the
                // peer's certificate identity and pin the NodeId it is authorized to present. A cert
                // whose identity is not in the allow-list (e.g. a plain client cert with no node
                // marker) cannot open a peer connection - drop (counted). Unenforced or plaintext
                // leaves no pinned attribute and the read path unchanged. A FAILED handshake needs no
                // action here: the SslHandler closes the channel itself.
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
            // Decode desync (CorruptedFrameException) or transport error - drop the connection. Count only
            // the decode-desync so the series stays "connection dropped because a frame did not decode",
            // not a catch-all for transport errors (which are their own class of event).
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
            // A raft outbound channel is SEND-ONLY (a peer replies on ITS OWN outbound, never on the
            // connection we opened), so a read-idle check can never see a half-open link to a peer that
            // restarted or crashed - our AppendEntries would blackhole into the dead socket forever with
            // no reconnect (the peers-never-re-dial-a-restarted-node bug). TCP_USER_TIMEOUT makes the
            // kernel fail the connection once our sent bytes go unACKed for the window (exactly the
            // dead/restarted-peer case), so channelInactive fires and we reconnect to the peer's fresh
            // listener. A healthy peer ACKs within milliseconds so a live link never trips it. Set it on
            // every native Linux tier that exposes the socket option (epoll AND io_uring). On the pure-NIO
            // tier the option is unavailable, so a dead peer is detected only by the kernel's TCP
            // retransmission timeout (tcp_retries2, ~15 min) - SO_KEEPALIVE above does NOT shorten that
            // (its default idle is 2h and it does not probe while sends are outstanding); NIO is a
            // test/fallback tier, not the production tier (which auto-selects epoll on Linux).
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
                // Layer 2 reverse-path binding: a peer may reply on this connection WE dialed. The far
                // end is the target we connected to (hostname-verified on connect), so any frame whose
                // senderId differs from `target` is a forged-id injection - drop + count. Only active
                // when an allow-list is enforced; mirrors the JDK outbound-reverse reader.
                if (peerIdentityPolicy.enforced() && !target.equals(msg.from())) {
                    transportMetrics.onPeerIdentityRejected();
                    ctx.close();
                    return;
                }
                dispatch(msg); // a peer may reply on the connection we opened (JDK reads here too)
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
                // The frame decoder is shared with the inbound pipeline (a peer may reply on the
                // connection we dialed), so a corrupt reply desyncs here too - count it the same way.
                if (cause instanceof CorruptedFrameException) {
                    transportMetrics.onInboundConnectionDropped();
                }
                ctx.close(); // decode desync / transport error - drop; reconnect on the next send
            }
        }
    }
}
