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
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
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
    /** WH-11 pre-SUBSCRIBE first-frame deadline (ms); shared config with the JDK transport. */
    private final int firstFrameDeadlineMs = FanOutServer.firstFrameDeadlineMs();
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

    /**
     * The edge token-authentication posture, or {@code null} when only mTLS / plaintext is configured.
     * When non-null the pipeline installs the {@link EdgeAuthGateHandler}, the decoder enforces the
     * pre-auth frame ceiling, and the edge TLS is {@code wantClientAuth} (a certificate-less token
     * client may connect). When {@code null} the endpoint is byte-identical to the pre-token edge.
     */
    private final EdgeAuthConfig edgeAuth;

    /**
     * The edge client-cert validity gate ({@link EdgeCertGate#OFF} = no online revocation + no active cert
     * expiry, byte-identical). Applied at admission on BOTH the mTLS-only path and the token-edge cert path.
     * Never constructed for the Raft interior, so the {@code exemptInterNode} invariant holds by construction.
     */
    private final EdgeCertGate certGate;

    private final NettyTransport.Selection transport;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Live connections INCLUDING mid-handshake (the bound is applied before the handshake). */
    private final AtomicInteger liveConnections = new AtomicInteger();

    private volatile EventLoopGroup boss;
    private volatile EventLoopGroup worker;
    private volatile Channel serverChannel;
    /**
     * Bounded worker pool for OFF-event-loop credential resolution. Basic verification is a deliberately
     * expensive PBKDF2 (~50-150ms); running it inline on a shared Netty worker would stall every other
     * connection on that loop (an unauthenticated event-loop-stall DoS, amplified by REFRESH_AUTH spam).
     * Bounded (not virtual-thread-per-task) because the work is CPU-bound: more concurrent PBKDF2 than
     * cores only thrashes, so the pool caps total verification CPU. Null when token auth is not configured
     * (byte-identical mTLS-only / plaintext edge).
     */
    private volatile java.util.concurrent.ExecutorService authWorker;

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
                config, transportQueueFrames, maxSessions, governor, metrics, clock, authorizer, null,
                EdgeCertGate.OFF);
    }

    /**
     * The multi-shard constructor: the per-shard commit sources + replay sources + shard set +
     * resolver the fan-out/fan-in coordinator fans a watch across. At {@code N = 1} the single-source
     * constructors delegate here with single-entry maps and the single-shard resolver, so one core is
     * the single-shard drain (byte-identical). {@code ConfigdServer} threads the real per-shard maps.
     *
     * @param edgeAuth the edge token-authentication posture, or {@code null} for the mTLS-only /
     *                 plaintext posture (byte-identical to the pre-token edge: no gate, no pre-auth
     *                 ceiling, {@code needClientAuth})
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
                             WatchAuthorizer authorizer,
                             EdgeAuthConfig edgeAuth,
                             EdgeCertGate certGate) {
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
        this.edgeAuth = edgeAuth; // nullable => mTLS-only / plaintext => byte-identical to the pre-token edge
        this.certGate = Objects.requireNonNullElse(certGate, EdgeCertGate.OFF);
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
        if (edgeAuth != null) {
            authWorker = newAuthWorkerPool();
        }
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
                        // Token-auth: the decoder enforces the pre-auth ceiling while UNAUTHENTICATED,
                        // and the gate (after the encoder, before the connection) admits exactly one
                        // authentication before any business frame reaches the session. Without it the
                        // pipeline is byte-identical to the pre-token edge.
                        ch.pipeline().addLast(edgeAuth != null
                                ? new ByteToEdgeFrameDecoder(true, edgeAuth.preAuthMaxFrameBytes())
                                : new ByteToEdgeFrameDecoder());
                        ch.pipeline().addLast(new EdgeFrameToByteEncoder(() -> conn.wireVersion));
                        if (edgeAuth != null) {
                            ch.pipeline().addLast(
                                    new EdgeAuthGateHandler(edgeAuth, certGate, metrics, clock, authWorker));
                        }
                        ch.pipeline().addLast(conn);
                    }
                });
        boolean bound = false;
        try {
            try {
                serverChannel = b.bind(bindAddress).sync().channel();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted binding NettyFanOutServer", e);
            }
            LOG.info(() -> "NettyFanOutServer listening on " + serverChannel.localAddress()
                    + (tlsManager != null ? " (mTLS)" : " (PLAINTEXT)") + " [tier=" + transport.tier() + "]");
            bound = true;
        } finally {
            if (!bound) {
                // A mid-start failure (bind refused / port in use, including a BindException sneak-thrown by
                // sync()) must not leak the non-daemon boss/worker event loops or the auth-worker pool just
                // created. close() resets running and shuts them all (idempotent, serverChannel null-guarded),
                // so a failed start() leaves nothing behind; the original failure propagates.
                close();
            }
        }
    }

    /**
     * The bounded off-loop credential-resolution pool. Sized to the CPU count (PBKDF2 is CPU-bound, so
     * more threads than cores only thrashes), overridable via {@code configd.edge.authWorkerThreads}, and
     * floored at 1. Daemon threads so they never hold the JVM up.
     *
     * <p>The pending-verification QUEUE is bounded too ({@code configd.edge.authWorkerQueueDepth}, default a
     * small multiple of the pool): a credential-verification flood (many connections each sending an AUTH
     * behind a deliberately slow PBKDF2) must not grow the backlog without bound and exhaust the heap. A
     * saturated queue makes {@code execute()} throw {@link java.util.concurrent.RejectedExecutionException},
     * which {@link EdgeAuthGateHandler} turns into a fail-closed close (pre-auth) or a dropped refresh -
     * never an inline event-loop resolution. Combined with the gate skipping the PBKDF2 for a connection that
     * is already dead, this bounds both the queue depth and the wasted work under a churn/AUTH flood.
     *
     * <p>All credential types share this one pool, so a sustained flood of the EXPENSIVE type (Basic PBKDF2)
     * saturates the queue and legitimate token clients are shed fail-closed ({@code AUTH_UNAVAILABLE}) rather
     * than the server exhausting the heap or stalling the event loop. That graceful shedding is the deliberate
     * behaviour: bounding the total verification work per node is a correctness requirement, and per-IP /
     * per-tenant admission control against an auth flood is an ingress-layer concern (load balancer / WAF /
     * rate limiter), as it is for etcd, Vault, and Keycloak - none run an unbounded KDF in the verifier.
     */
    private static java.util.concurrent.ExecutorService newAuthWorkerPool() {
        int threads = Math.max(1, Integer.getInteger(
                "configd.edge.authWorkerThreads", Runtime.getRuntime().availableProcessors()));
        int queueDepth = Math.max(1, Integer.getInteger(
                "configd.edge.authWorkerQueueDepth", threads * 8));
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        return new java.util.concurrent.ThreadPoolExecutor(
                threads, threads, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(queueDepth),
                r -> {
                    Thread t = new Thread(r, "configd-edge-auth-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    /** Builds the server-mode mTLS {@link SslHandler} - the SAME SSLContext/protocols/ciphers as the JDK server. */
    private SslHandler newSslHandler() {
        SSLContext sslContext = tlsManager.currentContext();
        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(false);
        if (edgeAuth != null) {
            // Token auth is configured: a certificate-less token client must be able to connect, so the
            // client cert becomes WANTED not REQUIRED. A presented cert is still validated against the
            // trust store (a bad cert fails the handshake); a certless client proceeds to its AUTH frame.
            engine.setWantClientAuth(true);
        } else {
            engine.setNeedClientAuth(true); // mTLS REQUIRED: the edge endpoint always demands a client cert
        }
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
        java.util.concurrent.ExecutorService aw = authWorker;
        if (aw != null) {
            aw.shutdownNow();
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

        /**
         * WH-11: the one-shot pre-SUBSCRIBE first-frame reap task, armed on session start
         * (post-mTLS / plaintext admission) and cancelled when the first routed frame arrives.
         * Event-loop-only (armed, cancelled, and fired all on the event loop).
         */
        private ScheduledFuture<?> firstFrameDeadline;

        /**
         * The mTLS-only cert-{@code notAfter} expiry one-shot, armed post-handshake when
         * {@code enforceCertNotAfter} is on (the token-edge cert path is instead handled by the
         * {@code EdgeAuthGateHandler}). Event-loop-only. A fired task closes the connection
         * {@code CREDENTIAL_EXPIRED} (a reconnect signal). Null when enforcement is off (byte-identical).
         * Volatile: armed on the event loop, cancelled by {@link #teardown} which may run on the session thread.
         */
        private volatile ScheduledFuture<?> certExpiry;

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
            if (tlsManager == null && edgeAuth == null) {
                startSession(ctx, "plaintext", Set.of()); // plaintext: no handshake to await
            }
            // With token auth on, the session start is deferred to the gate's EdgeAuthenticated event -
            // even in plaintext, where the connection's first frame must be an AUTH.
            ctx.fireChannelActive();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (edgeAuth != null) {
                // Token auth: the gate owns authentication and fires EdgeAuthenticated (from a verified
                // client cert at the handshake, or from an accepted AUTH frame). The raw
                // SslHandshakeCompletionEvent is consumed by the gate and never reaches here, so the
                // session starts only once identity is established.
                if (evt instanceof EdgeAuthenticated authenticated) {
                    startSession(ctx, authenticated.principal().id(), authenticated.principal().roles());
                }
                ctx.fireUserEventTriggered(evt);
                return;
            }
            if (evt instanceof SslHandshakeCompletionEvent handshake) {
                if (handshake.isSuccess()) {
                    String identity = resolveCertIdentity(ctx);
                    List<X509Certificate> chain = verifiedPeerChain(ctx);
                    if (identity == null) {
                        rejectHandshake(ctx); // handshake "succeeded" but no verifiable peer cert
                    } else if (!certGate.admit(chain)) {
                        // Edge client cert revoked (or unreachable-under-strict): reject at admission. Edge
                        // plane only; the Raft interior never consults a responder (exemptInterNode).
                        rejectHandshake(ctx);
                    } else {
                        // Legacy mTLS: roles stay empty (the ACL resolves the DN's config-bound roles
                        // internally) - byte-identical to before. When cert-notAfter enforcement is on, arm
                        // a mid-connection close at notAfter + leeway (NO_EXPIRY otherwise = byte-identical).
                        startSession(ctx, identity, Set.of());
                        armCertExpiry(ctx, certGate.certCloseDeadlineMillis(chain));
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

        /** The verified peer certificate chain (leaf-first), or an empty list if the peer presented none. */
        private List<X509Certificate> verifiedPeerChain(ChannelHandlerContext ctx) {
            SslHandler ssl = ctx.pipeline().get(SslHandler.class);
            if (ssl == null) {
                return List.of();
            }
            try {
                Certificate[] certs = ssl.engine().getSession().getPeerCertificates();
                List<X509Certificate> chain = new ArrayList<>(certs.length);
                for (Certificate c : certs) {
                    if (c instanceof X509Certificate x) {
                        chain.add(x);
                    }
                }
                return chain;
            } catch (Exception e) {
                return List.of(); // no verifiable client certificate (revocation gate treats as nothing-to-check)
            }
        }

        private void rejectHandshake(ChannelHandlerContext ctx) {
            if (alive.compareAndSet(true, false)) {
                metrics.onSessionClosed(ErrorCode.AUTH_FAIL.name());
            }
            ctx.close();
        }

        private void startSession(ChannelHandlerContext ctx, String identity, Set<String> roles) {
            if (started || !alive.get()) {
                return;
            }
            started = true;
            this.driver = new FanOutConnectionDriver(shardSources, shardReplaySources, allGids,
                    shardResolver, topologyEpoch, this, config, metrics, clock, governor, identity, roles,
                    this::teardown, authorizer);
            metrics.onSubscriberConnected();
            connectedCounted = true;
            Thread.ofVirtual().name("edge-netty-session-" + identity)
                    .start(() -> driver.runSessionLoop(() -> alive.get() && running.get()));
            // WH-11: arm the pre-SUBSCRIBE first-frame deadline now that the connection is admitted
            // (post-mTLS / plaintext). A one-shot event-loop task reaps a peer that never sends its
            // first routed frame; channelRead0 cancels it on the first frame (an established
            // subscriber is idle by design and relies on the server->client HEARTBEAT for liveness).
            firstFrameDeadline = ctx.executor().schedule(
                    () -> onFirstFrameDeadline(ctx), firstFrameDeadlineMs, TimeUnit.MILLISECONDS);
        }

        /** WH-11 reap: fires on the event loop if no routed frame arrived within the deadline. */
        private void onFirstFrameDeadline(ChannelHandlerContext ctx) {
            if (!firstInboundSeen && alive.get()) {
                metrics.onFirstFrameTimeout();
                teardown(ErrorCode.PROTOCOL_VIOLATION, "pre-SUBSCRIBE first-frame deadline elapsed");
            }
        }

        /**
         * Arms the mTLS-only cert-{@code notAfter} expiry one-shot on the event loop. A
         * {@link AuthState#NO_EXPIRY} deadline (enforcement off) arms nothing - byte-identical to Gate 3.
         * The delay is {@code max(0, deadline - now)} so a clock already past {@code notAfter} fires
         * promptly rather than scheduling a negative delay.
         */
        private void armCertExpiry(ChannelHandlerContext ctx, long deadlineMillis) {
            if (deadlineMillis == AuthState.NO_EXPIRY || !alive.get()) {
                return;
            }
            long delay = Math.max(0L, deadlineMillis - clock.currentTimeMillis());
            certExpiry = ctx.executor().schedule(
                    () -> teardown(ErrorCode.CREDENTIAL_EXPIRED, EdgeCertGate.CERT_EXPIRED_MESSAGE),
                    delay, TimeUnit.MILLISECONDS);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, EdgeFrame frame) {
            if (!firstInboundSeen) {
                firstInboundSeen = true;
                // WH-11 disarm: the first routed frame arrived; cancel the reap task so an
                // established, legitimately-idle subscriber is never read-idle-reaped.
                if (firstFrameDeadline != null) {
                    firstFrameDeadline.cancel(false);
                    firstFrameDeadline = null;
                }
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
            // A decode failure reaches here wrapped: the decoder throws a CodecException (a
            // RuntimeException), which Netty's ByteToMessageDecoder re-throws inside a DecoderException, so
            // the real ErrorCode is one link down the cause chain. Unwrap it (matching the JDK reader,
            // which catches the CodecException directly) so a corrupt/oversize/bad-version frame closes
            // with its true code (FRAME_CORRUPT / FRAME_TOO_LARGE / BAD_WIRE_VERSION) rather than the
            // catch-all SERVER_SHUTDOWN. Wire-behavior change on the mTLS/plaintext edge: a post-admission
            // decode error that previously (buggily) closed SERVER_SHUTDOWN now closes with the frame's
            // real code, matching the JDK transport; no correct client depends on the old catch-all code.
            EdgeFrameCodec.CodecException ce = CodecExceptions.unwrap(cause);
            if (ce != null) {
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
            ScheduledFuture<?> ce = certExpiry;
            if (ce != null) {
                ce.cancel(false); // a fired task is a teardown no-op, but cancel avoids retaining ctx to notAfter
            }
            // Cancel the pre-SUBSCRIBE first-frame reaper if it is still pending (a connection torn down
            // before it sent its first routed frame - a RST during the pre-SUBSCRIBE window). channelRead0
            // cancels it on the first frame; without this cancel, connect/RST churn retains ~rate x 10s
            // scheduled tasks on the event loop until they individually fire (mirrors the gate cancelling
            // its preAuthDeadline in handlerRemoved).
            ScheduledFuture<?> ff = firstFrameDeadline;
            if (ff != null) {
                ff.cancel(false);
                firstFrameDeadline = null;
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
