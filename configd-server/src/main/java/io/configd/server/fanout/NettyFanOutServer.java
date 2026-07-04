package io.configd.server.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.FanOutConnectionDriver;
import io.configd.distribution.fanout.FanOutSessionCore;
import io.configd.distribution.fanout.SlowConsumerGovernor;
import io.configd.distribution.fanout.ShardResolver;
import io.configd.distribution.fanout.SlowConsumerPolicyConfig;
import io.configd.distribution.fanout.TransportSink;
import io.configd.distribution.fanout.WatchAuthorizer;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.ErrorCode;
import io.configd.distribution.wire.WatchCursor;
import io.configd.netty.NettyTransport;
import io.configd.transport.TlsConfig;
import io.configd.transport.TlsManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * The Netty edge fan-out endpoint - the Netty transport over the SAME
 * transport-agnostic {@link FanOutConnectionDriver} + {@link FanOutSessionCore} the JDK
 * {@link FanOutServer} drives. Every fan-out control - mTLS admission, the slow-consumer
 * demotion->quarantine->disconnect policy, propagation/monotonicity - is therefore re-proven on this
 * pipeline by the identical {@code FanOutServerContract} (JDK + Netty(auto) + Netty(forced-NIO)),
 * not re-implemented.
 *
 * <h2>Pipeline (per connection)</h2>
 * {@code [SslHandler? -> ByteToEdgeFrameDecoder -> EdgeFrameToByteEncoder -> FanOutConnection]}.
 * <ul>
 *   <li><b>mTLS:</b> when a {@link TlsManager} is present the first stage is an
 *       {@link SslHandler} built from the SAME {@code SSLContext} the JDK server + Raft use, in
 *       server mode with {@code setNeedClientAuth(true)} and the {@link TlsConfig} TLSv1.3-only
 *       protocols + ciphers. The edge identity is the verified client-cert Subject DN read from the
 *       post-handshake {@code SSLSession.getPeerPrincipal()}; the wire {@code edgeId} is advisory.
 *       No {@code TlsManager} => plaintext (no {@code SslHandler}), matching the JDK server.</li>
 *   <li><b>Codec:</b> {@link ByteToEdgeFrameDecoder} keeps the {@code peekLength}
 *       bounds-before-allocation discipline; {@link EdgeFrameToByteEncoder} does the single-pass
 *       in-pipeline pooled encode.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * The event loop owns the socket (TLS, decode, the outbound encode, write completion). One virtual
 * <b>session thread</b> per connection drives {@link FanOutConnectionDriver#runSessionLoop} - the
 * same model as the JDK server (session work, incl. up-to-1 MiB snapshot serialization, never runs
 * on the event loop). Inbound frames are routed on the event loop into the driver (single-threaded
 * inbound, exactly like the JDK reader); the driver and session communicate through a concurrent
 * command queue. The {@link TransportSink#offer} writes to the channel and bounds in-flight
 * (written-not-flushed) frames at {@code transportQueueFrames}.
 */
public final class NettyFanOutServer implements FanOutEndpoint {

    private static final Logger LOG = Logger.getLogger(NettyFanOutServer.class.getName());

    /** Named config: per-connection outbound transport queue depth (frames). Design section 4 (== JDK). */
    public static final int DEFAULT_TRANSPORT_QUEUE_FRAMES = FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES;

    /** Named config {@code edge.fanout.transport.maxSessions} (== JDK; hard rule 4). */
    public static final int DEFAULT_MAX_SESSIONS = FanOutServer.DEFAULT_MAX_SESSIONS;

    /** The single-shard resolver the single-source constructors bind: every target -> gid 0. */
    private static final ShardResolver SINGLE_SHARD = t -> new int[]{0};

    private final InetSocketAddress bindAddress;
    private final TlsManager tlsManager;
    /** gid -> that shard's commit source; single-entry {@code {0 -> source}} for the single-shard ctors. */
    private final Map<Integer, CommitNotificationSource> shardSources;
    /** gid -> that shard's replay source; single-entry for the single-shard ctors. */
    private final Map<Integer, ReplaySource> shardReplaySources;
    /** The connection's shard set, ascending ({@code [0, N)}); {@code {0}} for the single-shard ctors. */
    private final int[] allGids;
    /** Resolves a watch target to its covered shard set; {@link #SINGLE_SHARD} for the single-shard ctors. */
    private final ShardResolver shardResolver;
    /** The server's topology epoch ({@code ShardMap.epoch()}), threaded into every session driver (A4). */
    private final long topologyEpoch;
    private final FanOutConfig config;
    private final int transportQueueFrames;
    private final int maxSessions;
    private final SlowConsumerGovernor governor;
    private final RegistryFanOutSessionMetrics metrics;
    private final Clock clock;
    private final int workerThreads;

    /**
     * The authorization gate, or {@code null} when no principal model is wired. It gates both
     * {@code WATCH_CREATE} (per-target) and the legacy full-store {@code SUBSCRIBE} (whole-store READ).
     * A {@code null} authorizer fails CLOSED for watches (every {@code WATCH_CREATE} ->
     * {@code NOT_AUTHORIZED}) but admits {@code SUBSCRIBE} (auth off), so existing callers (the
     * contract, the testkit main) behave as an unauthenticated deployment; {@code ConfigdServer}
     * threads a real authorizer.
     */
    private final WatchAuthorizer authorizer;

    private final NettyTransport.Selection transport;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Live connections INCLUDING mid-handshake (the bound is applied before the handshake). */
    private final AtomicInteger liveConnections = new AtomicInteger();

    private volatile EventLoopGroup boss;
    private volatile EventLoopGroup worker;
    private volatile Channel serverChannel;

    public NettyFanOutServer(InetSocketAddress bindAddress,
                             TlsManager tlsManager,
                             CommitNotificationSource source,
                             ReplaySource replaySource,
                             FanOutConfig config,
                             int transportQueueFrames,
                             RegistryFanOutSessionMetrics metrics,
                             Clock clock) {
        this(bindAddress, tlsManager, source, replaySource, config, transportQueueFrames,
                DEFAULT_MAX_SESSIONS, metrics, clock);
    }

    public NettyFanOutServer(InetSocketAddress bindAddress,
                             TlsManager tlsManager,
                             CommitNotificationSource source,
                             ReplaySource replaySource,
                             FanOutConfig config,
                             int transportQueueFrames,
                             int maxSessions,
                             RegistryFanOutSessionMetrics metrics,
                             Clock clock) {
        this(bindAddress, tlsManager, source, replaySource, config, transportQueueFrames, maxSessions,
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(),
                        Objects.requireNonNull(metrics, "metrics")),
                metrics, clock);
    }

    public NettyFanOutServer(InetSocketAddress bindAddress,
                             TlsManager tlsManager,
                             CommitNotificationSource source,
                             ReplaySource replaySource,
                             FanOutConfig config,
                             int transportQueueFrames,
                             int maxSessions,
                             SlowConsumerGovernor governor,
                             RegistryFanOutSessionMetrics metrics,
                             Clock clock) {
        this(bindAddress, tlsManager, source, replaySource, config, transportQueueFrames, maxSessions,
                governor, metrics, clock, null);
    }

    /**
     * Full constructor with the authorization gate ({@code authorizer}). A {@code null} authorizer
     * fails CLOSED for watches (rejected {@code NOT_AUTHORIZED}) and admits the legacy full-store
     * {@code SUBSCRIBE} (auth off); a wired authorizer additionally gates {@code SUBSCRIBE} on
     * whole-store READ. {@code ConfigdServer} threads the {@code AclServiceWatchAuthorizer} here.
     */
    public NettyFanOutServer(InetSocketAddress bindAddress,
                             TlsManager tlsManager,
                             CommitNotificationSource source,
                             ReplaySource replaySource,
                             FanOutConfig config,
                             int transportQueueFrames,
                             int maxSessions,
                             SlowConsumerGovernor governor,
                             RegistryFanOutSessionMetrics metrics,
                             Clock clock,
                             WatchAuthorizer authorizer) {
        this(Map.of(0, Objects.requireNonNull(source, "source")),
                Map.of(0, Objects.requireNonNull(replaySource, "replaySource")),
                new int[]{0}, SINGLE_SHARD, WatchCursor.INITIAL_TOPOLOGY_EPOCH, bindAddress, tlsManager,
                config, transportQueueFrames, maxSessions, governor, metrics, clock, authorizer);
    }

    /**
     * The multi-shard constructor: the per-shard commit sources + replay sources + shard set +
     * resolver the fan-out/fan-in coordinator fans a watch across. At {@code N = 1} the single-source
     * constructors delegate here with single-entry maps and the single-shard resolver, so one core is
     * the single-shard drain (byte-identical). {@code ConfigdServer} threads the real per-shard maps.
     */
    public NettyFanOutServer(Map<Integer, CommitNotificationSource> shardSources,
                             Map<Integer, ReplaySource> shardReplaySources,
                             int[] allGids,
                             ShardResolver shardResolver,
                             long topologyEpoch,
                             InetSocketAddress bindAddress,
                             TlsManager tlsManager,
                             FanOutConfig config,
                             int transportQueueFrames,
                             int maxSessions,
                             SlowConsumerGovernor governor,
                             RegistryFanOutSessionMetrics metrics,
                             Clock clock,
                             WatchAuthorizer authorizer) {
        this.shardSources = Map.copyOf(Objects.requireNonNull(shardSources, "shardSources"));
        this.shardReplaySources = Map.copyOf(Objects.requireNonNull(shardReplaySources, "shardReplaySources"));
        this.allGids = Objects.requireNonNull(allGids, "allGids").clone();
        this.shardResolver = Objects.requireNonNull(shardResolver, "shardResolver");
        if (topologyEpoch <= WatchCursor.EPOCH_UNSET) {
            throw new IllegalArgumentException(
                    "topologyEpoch must be in [1, 2^63) (0 is reserved-illegal): " + topologyEpoch);
        }
        this.topologyEpoch = topologyEpoch;
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.tlsManager = tlsManager; // null = plaintext (test/single-node)
        this.config = Objects.requireNonNull(config, "config");
        if (transportQueueFrames <= 0) {
            throw new IllegalArgumentException("transportQueueFrames must be positive: " + transportQueueFrames);
        }
        this.transportQueueFrames = transportQueueFrames;
        if (maxSessions <= 0) {
            throw new IllegalArgumentException("maxSessions must be positive: " + maxSessions);
        }
        this.maxSessions = maxSessions;
        this.governor = Objects.requireNonNull(governor, "governor");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.workerThreads = Integer.getInteger("configd.edge.netty.workerThreads",
                Math.max(2, Runtime.getRuntime().availableProcessors()));
        this.transport = NettyTransport.select();
        this.authorizer = authorizer; // nullable => no watch capability => driver fails closed
    }

    @Override
    public SlowConsumerGovernor governor() {
        return governor;
    }

    /** The active transport tier (io_uring / epoll / nio) - surfaced for logging + the CI proof. */
    public String transportTier() {
        return transport.tier();
    }

    @Override
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("NettyFanOutServer already started");
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
                        // Construct the per-connection handler FIRST so the outbound encoder can read its
                        // negotiated wire version: a watch connection (first WATCH_CREATE)
                        // flips conn.wireVersion to 0x02 and the encoder stamps it on every outbound frame.
                        FanOutConnection conn = new FanOutConnection();
                        if (tlsManager != null) {
                            ch.pipeline().addLast(newSslHandler());
                        }
                        ch.pipeline().addLast(new ByteToEdgeFrameDecoder());
                        ch.pipeline().addLast(new EdgeFrameToByteEncoder(() -> conn.wireVersion));
                        ch.pipeline().addLast(conn);
                    }
                });
        try {
            serverChannel = b.bind(bindAddress).sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted binding NettyFanOutServer", e);
        }
        LOG.info(() -> "NettyFanOutServer listening on " + serverChannel.localAddress()
                + (tlsManager != null ? " (mTLS)" : " (PLAINTEXT)") + " [tier=" + transport.tier() + "]");
    }

    /** Builds the server-mode mTLS {@link SslHandler} - the SAME SSLContext/protocols/ciphers as the JDK server. */
    private SslHandler newSslHandler() {
        SSLContext sslContext = tlsManager.currentContext();
        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(false);
        engine.setNeedClientAuth(true); // mTLS REQUIRED: the edge endpoint always demands a client cert
        TlsConfig tlsConfig = tlsManager.config();
        if (tlsConfig != null) {
            if (!tlsConfig.protocols().isEmpty()) {
                engine.setEnabledProtocols(tlsConfig.protocols().toArray(String[]::new));
            }
            if (!tlsConfig.ciphers().isEmpty()) {
                engine.setEnabledCipherSuites(tlsConfig.ciphers().toArray(String[]::new));
            }
        }
        return new SslHandler(engine);
    }

    @Override
    public int localPort() {
        Channel ch = serverChannel;
        return (ch != null && ch.localAddress() instanceof InetSocketAddress addr) ? addr.getPort() : -1;
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Channel ch = serverChannel;
        if (ch != null) {
            ch.close();
        }
        if (boss != null) {
            boss.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
        if (worker != null) {
            worker.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
    }

    // -----------------------------------------------------------------------
    // Per-connection handler (the JDK FanOutServer.Connection's Netty twin)
    // -----------------------------------------------------------------------

    /**
     * One edge subscriber connection: the event-loop body + the {@link TransportSink}; the brain is
     * the shared {@link FanOutConnectionDriver} on a dedicated virtual session thread. A new instance
     * per channel (holds per-connection state; event-loop methods need no synchronization, the
     * session-thread interactions go through the driver's concurrent queue / the atomics here).
     */
    private final class FanOutConnection extends SimpleChannelInboundHandler<EdgeFrame>
            implements TransportSink {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        /** In-flight (written, not yet flushed) frames. */
        private final AtomicInteger inFlight = new AtomicInteger();

        private volatile Channel channel;
        private volatile FanOutConnectionDriver driver;
        private boolean counted;          // event-loop-only: this channel incremented liveConnections
        private boolean started;          // event-loop-only: the session has been started
        private volatile boolean connectedCounted; // onSubscriberConnected fired (pairs with disconnect)

        /**
         * Negotiated OUTBOUND edge wire version. Default {@code 0x01} (legacy); flipped to
         * {@code 0x02} when this connection's FIRST inbound frame is a {@code WATCH_CREATE} (a watch
         * connection). The {@link EdgeFrameToByteEncoder} reads it (via the {@code initChannel} supplier
         * lambda) and stamps it on every outbound frame, so a {@code 0x02} client can decode the
         * server's {@code WATCH_*} frames. Written + read on the event loop; {@code volatile}
         * for clarity and the cross-handler supplier read. A legacy connection never flips it -> stays
         * {@code 0x01} -> byte-identical.
         */
        volatile byte wireVersion = EdgeFrameCodec.EDGE_WIRE_VERSION;

        /** Event-loop-only: whether the connection-type-deciding first inbound frame has been seen. */
        private boolean firstInboundSeen;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            this.channel = ctx.channel();
            // Admission bound BEFORE the handshake: half-open handshakes count, so a
            // slowloris cannot exhaust file descriptors and threads. Over the bound -> refuse + close (the client sees EOF).
            counted = true;
            if (liveConnections.incrementAndGet() > maxSessions) {
                metrics.onSessionRefused();
                alive.set(false); // so the imminent channelInactive teardown is a no-op
                ctx.close();
                return;
            }
            if (tlsManager == null) {
                startSession(ctx, "plaintext"); // plaintext: no handshake to await
            }
            ctx.fireChannelActive();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof SslHandshakeCompletionEvent handshake) {
                if (handshake.isSuccess()) {
                    String identity = resolveCertIdentity(ctx);
                    if (identity == null) {
                        rejectHandshake(ctx); // handshake "succeeded" but no verifiable peer cert
                    } else {
                        startSession(ctx, identity);
                    }
                } else {
                    // No cert / untrusted CA / expired / version-downgrade -> the handshake failed.
                    rejectHandshake(ctx);
                }
            }
            ctx.fireUserEventTriggered(evt);
        }

        /** The verified client-cert Subject DN (mTLS); null if no verifiable peer certificate. */
        private String resolveCertIdentity(ChannelHandlerContext ctx) {
            SslHandler ssl = ctx.pipeline().get(SslHandler.class);
            if (ssl == null) {
                return null;
            }
            try {
                return ssl.engine().getSession().getPeerPrincipal().getName();
            } catch (Exception e) {
                return null; // no verifiable client certificate (fail-closed)
            }
        }

        private void rejectHandshake(ChannelHandlerContext ctx) {
            if (alive.compareAndSet(true, false)) {
                metrics.onSessionClosed(ErrorCode.AUTH_FAIL.name());
            }
            ctx.close();
        }

        private void startSession(ChannelHandlerContext ctx, String identity) {
            if (started || !alive.get()) {
                return;
            }
            started = true;
            this.driver = new FanOutConnectionDriver(shardSources, shardReplaySources, allGids,
                    shardResolver, topologyEpoch, this, config, metrics, clock, governor, identity,
                    this::teardown, authorizer);
            metrics.onSubscriberConnected();
            connectedCounted = true;
            Thread.ofVirtual().name("edge-netty-session-" + identity)
                    .start(() -> driver.runSessionLoop(() -> alive.get() && running.get()));
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, EdgeFrame frame) {
            if (!firstInboundSeen) {
                firstInboundSeen = true;
                // Outbound flip: a WATCH_CREATE-first connection is a 0x02 watch connection, so the
                // encoder must stamp 0x02 for the client to decode the server's WATCH_* frames. A
                // 0x03-stamped SUBSCRIBE is a filtered-fan-out connection (ADR-0045), so the encoder
                // stamps 0x03 for the SUBSCRIBE_OK filtered confirm and every subsequent frame the
                // edge's 0x03-pinned reader decodes. A plain 0x01 SUBSCRIBE stays 0x01 (byte-
                // identical). Flip BEFORE routing, so it happens-before any outbound frame the driver
                // later produces. The decoder has already pinned this connection's inbound version.
                if (frame instanceof EdgeFrame.WatchCreate) {
                    wireVersion = EdgeFrameCodec.EDGE_WIRE_VERSION_V2;
                } else if (frame instanceof EdgeFrame.Subscribe) {
                    ByteToEdgeFrameDecoder dec = ctx.pipeline().get(ByteToEdgeFrameDecoder.class);
                    if (dec != null && dec.negotiatedVersion() == EdgeFrameCodec.EDGE_WIRE_VERSION_V3) {
                        wireVersion = EdgeFrameCodec.EDGE_WIRE_VERSION_V3;
                    }
                }
            }
            FanOutConnectionDriver d = driver;
            if (d != null) {
                d.onInboundFrame(frame); // routing is the driver's (SUBSCRIBE-first, CURSOR_ACK, ...)
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (counted) {
                liveConnections.decrementAndGet();
                counted = false;
            }
            teardown(ErrorCode.SERVER_SHUTDOWN, "channel inactive");
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (cause instanceof EdgeFrameCodec.CodecException ce) {
                teardown(ce.code(), "decode error: " + ce.getMessage());
            } else {
                if (alive.get()) {
                    LOG.fine(() -> "edge netty connection error: " + cause);
                }
                teardown(ErrorCode.SERVER_SHUTDOWN, "connection error");
            }
        }

        // ---- TransportSink (called from the session thread) ----

        @Override
        public boolean offer(EdgeFrame frame) {
            Channel ch = channel;
            if (!alive.get() || ch == null || !ch.isActive()) {
                return false;
            }
            // Bounded: never exceed transportQueueFrames written-not-flushed frames. A full transport
            // is backpressure (the session demotes) - never an unbounded outbound buffer.
            if (inFlight.get() >= transportQueueFrames) {
                return false;
            }
            inFlight.incrementAndGet();
            // In-pipeline encode on the event loop; the listener fires on flush completion.
            ch.writeAndFlush(frame).addListener(f -> inFlight.decrementAndGet());
            return true;
        }

        @Override
        public void close(ErrorCode code, String message) {
            teardown(code, message);
        }

        // ---- teardown (idempotent; callable from the event loop or the session thread) ----

        private void teardown(ErrorCode code, String message) {
            if (!alive.compareAndSet(true, false)) {
                return; // already torn down
            }
            Channel ch = channel;
            FanOutConnectionDriver d = driver;
            FanOutSessionCore s = (d != null) ? d.session() : null;
            boolean wantBye = ch != null && ch.isActive()
                    && s != null && s.state() != FanOutSessionCore.SessionState.CLOSED;
            if (ch != null) {
                if (wantBye) {
                    // Best-effort final ERROR_CLOSE, then close - on the event loop.
                    if (ch.eventLoop().inEventLoop()) {
                        writeByeThenClose(ch, code, message);
                    } else {
                        ch.eventLoop().execute(() -> writeByeThenClose(ch, code, message));
                    }
                } else {
                    ch.close();
                }
            }
            if (connectedCounted) {
                metrics.onSessionClosed(code.name());
                metrics.onSubscriberDisconnected();
            }
        }

        private void writeByeThenClose(Channel ch, ErrorCode code, String message) {
            if (ch.isActive()) {
                ch.writeAndFlush(new EdgeFrame.ErrorClose(code, message))
                        .addListener(ChannelFutureListener.CLOSE);
            } else {
                ch.close();
            }
        }
    }
}
