package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.AuthInterceptor;
import io.configd.api.ConfigReadService;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.api.RateLimiter;
import io.configd.common.Clock;
import io.configd.common.ConfigScope;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.HyParViewOverlay;
import io.configd.distribution.PlumtreeNode;
import io.configd.distribution.RolloutController;
import io.configd.distribution.SlowConsumerPolicy;
import io.configd.distribution.SubscriptionManager;
import io.configd.distribution.WatchService;
import io.configd.observability.BurnRateAlertEvaluator;
import io.configd.observability.ConfigdMetrics;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.ProductionSloDefinitions;
import io.configd.observability.PropagationLivenessMonitor;
import io.configd.observability.SafeLog;
import io.configd.observability.SloTracker;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftTransport;
import io.configd.raft.ProposalResult;
import io.configd.replication.MultiRaftDriver;
import io.configd.store.Compactor;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigSigner;
import io.configd.store.ConfigSnapshot;
import io.configd.store.ConfigStateMachine;
import io.configd.store.HamtMap;
import io.configd.store.SigningKeyStore;
import io.configd.store.VersionedConfigStore;
import io.configd.transport.TcpRaftTransport;
import io.configd.transport.TlsConfig;
import io.configd.transport.TlsManager;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Main entry point for the Configd server.
 * <p>
 * Initializes all core subsystems (storage, Raft consensus, config state machine,
 * multi-raft driver, security, observability, HTTP API) and runs the tick loop
 * on a scheduled executor.
 * <p>
 * This is a thin orchestrator -- all domain logic lives in the respective modules.
 */
public final class ConfigdServer {

    /**
     * H-009 (iter-2) — emits the SEVERE record produced by
     * {@link #handleTickLoopThrowable(Throwable, ConfigdMetrics)}. Tests
     * attach a {@link java.util.logging.Handler} to this logger to assert
     * the structured-log path replaces the historical
     * {@code printStackTrace(System.err)} silent-failure mode.
     */
    private static final Logger LOG = Logger.getLogger(ConfigdServer.class.getName());

    private static final int TICK_PERIOD_MS = 10;
    private static final int DEFAULT_RAFT_GROUP = 0;
    private static final int COMPACTION_INTERVAL_TICKS = 1000; // every ~10 seconds
    private static final int TLS_RELOAD_INTERVAL_MS = 60_000;  // every 60 seconds
    private static final int FANOUT_BUFFER_CAPACITY = 10_000;

    private final ServerConfig config;
    // F-0023: three separate single-threaded executors prevent head-of-line
    // blocking between consensus tick, read dispatch, and slow TLS reload.
    //
    //   tickExecutor         — consensus tick, watch tick, plumtree tick.
    //                          Also owns ALL ReadIndexState mutations (F-0010).
    //   readDispatchExecutor — HTTP read handler → tick thread marshalling;
    //                          decouples HTTP threads from tick-loop bursts.
    //   tlsReloadExecutor    — slow I/O (cert reload every 60s); its latency
    //                          must NEVER delay tick or reads.
    private final ScheduledExecutorService tickExecutor;
    private final ScheduledExecutorService readDispatchExecutor;
    private final ScheduledExecutorService tlsReloadExecutor;
    private final MultiRaftDriver driver;
    private final ConfigStateMachine stateMachine;
    private final HttpApiServer httpApiServer;
    private final TcpRaftTransport tcpTransport; // nullable when peer addresses not configured

    // Distribution layer
    private final WatchService watchService;
    private final FanOutBuffer fanOutBuffer;
    private final Compactor compactor;
    private final PlumtreeNode plumtreeNode;
    private final HyParViewOverlay hyParViewOverlay;
    private final SubscriptionManager subscriptionManager;
    private final SlowConsumerPolicy slowConsumerPolicy;
    private final RolloutController rolloutController;

    private ConfigdServer(ServerConfig config, MultiRaftDriver driver,
                          ConfigStateMachine stateMachine,
                          ScheduledExecutorService tickExecutor,
                          ScheduledExecutorService readDispatchExecutor,
                          ScheduledExecutorService tlsReloadExecutor,
                          HttpApiServer httpApiServer,
                          TcpRaftTransport tcpTransport,
                          WatchService watchService,
                          FanOutBuffer fanOutBuffer,
                          Compactor compactor,
                          PlumtreeNode plumtreeNode,
                          HyParViewOverlay hyParViewOverlay,
                          SubscriptionManager subscriptionManager,
                          SlowConsumerPolicy slowConsumerPolicy,
                          RolloutController rolloutController) {
        this.config = config;
        this.driver = driver;
        this.stateMachine = stateMachine;
        this.tickExecutor = tickExecutor;
        this.readDispatchExecutor = readDispatchExecutor;
        this.tlsReloadExecutor = tlsReloadExecutor;
        this.httpApiServer = httpApiServer;
        this.tcpTransport = tcpTransport;
        this.watchService = watchService;
        this.fanOutBuffer = fanOutBuffer;
        this.compactor = compactor;
        this.plumtreeNode = plumtreeNode;
        this.hyParViewOverlay = hyParViewOverlay;
        this.subscriptionManager = subscriptionManager;
        this.slowConsumerPolicy = slowConsumerPolicy;
        this.rolloutController = rolloutController;
    }

    /**
     * Creates and starts a Configd server from the given configuration.
     *
     * @param config the server configuration
     * @return the running server instance
     */
    public static ConfigdServer start(ServerConfig config) {
        // Ensure data directory exists
        Path dataDir = config.dataDir();
        try {
            Files.createDirectories(dataDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create data directory: " + dataDir, e);
        }

        // Initialize storage
        Storage storage = Storage.file(dataDir);
        Clock clock = Clock.system();

        // Initialize config signing (Ed25519) — must be created before ConfigStateMachine.
        //
        // F-0052 fix: persist the keypair across restarts instead of generating
        // a fresh ephemeral key each boot. Operators can supply
        // --signing-key-file; if omitted, the key is kept under the data
        // directory as "signing-key.bin" so restarts keep the chain valid.
        ConfigSigner configSigner;
        try {
            Path keyFile = config.signingKeyFile() != null
                    ? config.signingKeyFile()
                    : dataDir.resolve("signing-key.bin");
            SigningKeyStore keyStore = SigningKeyStore.loadOrCreate(keyFile);
            configSigner = new ConfigSigner(keyStore.keyPair());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load or create Ed25519 signing key", e);
        }

        // Initialize config store with empty initial snapshot
        ConfigSnapshot initialSnapshot = new ConfigSnapshot(
                HamtMap.empty(), 0L, clock.currentTimeMillis());
        VersionedConfigStore configStore = new VersionedConfigStore(initialSnapshot, clock);
        ConfigStateMachine stateMachine = new ConfigStateMachine(configStore, clock, configSigner);

        // Initialize Raft with durable WAL storage
        RaftConfig raftConfig = RaftConfig.of(config.nodeId(), config.peers());
        RaftLog raftLog = new RaftLog(storage);
        RandomGenerator random = RandomGeneratorFactory.getDefault().create(
                config.nodeId().id() * 31L + System.nanoTime());

        // ---------------------------------------------------------------
        // Wire TLS (must happen BEFORE TcpRaftTransport so Raft traffic
        // uses mTLS when --tls-* flags are supplied).
        //
        // F-0050 fix: previously, the Raft transport was constructed with
        // null TlsManager even when TLS was enabled on the CLI, causing
        // plaintext Raft traffic in production. TLS wiring is now lifted
        // above the Raft transport and the same TlsManager is shared.
        // ---------------------------------------------------------------
        final TlsManager tlsManager;
        SSLContext sslContext = null;
        if (config.tlsEnabled()) {
            try {
                TlsConfig tlsConfig = TlsConfig.mtls(
                        config.tlsCertPath(), config.tlsKeyPath(), config.tlsTrustStorePath());
                tlsManager = new TlsManager(tlsConfig);
                sslContext = tlsManager.currentContext();
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize TLS", e);
            }
        } else {
            tlsManager = null;
        }

        // Wire real TCP transport when peer addresses are configured,
        // otherwise fall back to no-op for single-node / test scenarios.
        TcpRaftTransport tcpTransport = null;
        RaftTransport transport;

        Map<NodeId, InetSocketAddress> peerAddresses = config.peerAddresses();
        if (peerAddresses != null && !peerAddresses.isEmpty()) {
            InetSocketAddress bindAddr = new InetSocketAddress(config.bindAddress(), config.bindPort());
            tcpTransport = new TcpRaftTransport(
                    config.nodeId(), bindAddr, peerAddresses, tlsManager, null);
            // F-0050 fix: fail-closed — refuse to start if the operator asked
            // for TLS but the transport did not receive a TlsManager. This
            // catches accidental regressions of the wiring.
            if (config.tlsEnabled() && tcpTransport.tlsManager() == null) {
                throw new IllegalStateException(
                        "TLS is enabled on the CLI but TcpRaftTransport has no TlsManager — "
                                + "refusing to start to avoid plaintext Raft traffic");
            }
            RaftTransportAdapter adapter = new RaftTransportAdapter(tcpTransport, DEFAULT_RAFT_GROUP);
            transport = adapter;
        } else {
            transport = (target, message) -> {
                // No-op: peer addresses not configured (single-node or test mode)
            };
        }

        RaftNode raftNode = new RaftNode(
                raftConfig, raftLog, transport, stateMachine,
                random, storage, RaftNode.InvariantChecker.NOOP);

        // Initialize multi-raft driver
        MultiRaftDriver driver = new MultiRaftDriver(config.nodeId(), clock);
        driver.addGroup(DEFAULT_RAFT_GROUP, raftNode);

        // Register inbound message handler on TCP transport
        if (tcpTransport != null) {
            RaftTransportAdapter adapter = (RaftTransportAdapter) transport;
            adapter.registerInboundHandler((from, message) -> driver.routeMessage(DEFAULT_RAFT_GROUP, message));
            try {
                tcpTransport.start();
            } catch (Exception e) {
                throw new RuntimeException("Failed to start TCP Raft transport on " + config.bindAddress() + ":" + config.bindPort(), e);
            }
        }

        // ---------------------------------------------------------------
        // Wire distribution layer
        // ---------------------------------------------------------------
        FanOutBuffer fanOutBuffer = new FanOutBuffer(FANOUT_BUFFER_CAPACITY);
        Compactor compactor = new Compactor();
        WatchService watchService = new WatchService(clock);
        SubscriptionManager subscriptionManager = new SubscriptionManager();
        RolloutController rolloutController = new RolloutController(clock);
        SlowConsumerPolicy slowConsumerPolicy = new SlowConsumerPolicy(clock);
        PlumtreeNode plumtreeNode = new PlumtreeNode(config.nodeId(), 10_000, 100);
        HyParViewOverlay hyParViewOverlay = new HyParViewOverlay(
                config.nodeId(), 6, 30, 8, 4, random);

        // Wire HyParView active view changes → Plumtree eager/lazy peer sets
        hyParViewOverlay.setViewChangeListener((peer, added) -> {
            if (added) {
                plumtreeNode.addEagerPeer(peer);
            } else {
                plumtreeNode.removePeer(peer);
            }
        });

        // Register state machine listener: build ConfigDelta and feed fan-out buffer + compactor
        stateMachine.addListener((mutations, version) -> {
            long fromVersion = version - 1;
            byte[] signature = stateMachine.lastSignature();
            // F-0052: attach the monotonic epoch + nonce bound into the
            // signature so edges can reject replays.
            long epoch = stateMachine.lastEpoch();
            byte[] nonce = stateMachine.lastNonce();
            ConfigDelta delta;
            if (signature != null && nonce != null) {
                delta = new ConfigDelta(fromVersion, version, mutations, signature, epoch, nonce);
            } else {
                delta = new ConfigDelta(fromVersion, version, mutations, signature);
            }
            fanOutBuffer.append(delta);
            compactor.addSnapshot(configStore.snapshot());
        });

        // Register state machine listener: feed WatchService for push notifications
        stateMachine.addListener(watchService::onConfigChange);

        // ---------------------------------------------------------------
        // Wire observability
        // ---------------------------------------------------------------
        MetricsRegistry metricsRegistry = new MetricsRegistry();
        SloTracker sloTracker = new SloTracker();
        ProductionSloDefinitions.register(sloTracker);
        BurnRateAlertEvaluator burnRateAlertEvaluator = new BurnRateAlertEvaluator(sloTracker);
        PropagationLivenessMonitor propagationMonitor =
                new PropagationLivenessMonitor(1000, metricsRegistry);
        // H-009 (iter-2) — eager registration of the SLO metric family +
        // the new tick_loop_throwable counter so the very first scrape
        // returns a populated `# TYPE` line and the catch-block has a
        // stable handle to call `onTickLoopThrowable` against.
        ConfigdMetrics configdMetrics = new ConfigdMetrics(metricsRegistry, () -> 0L);

        // ---------------------------------------------------------------
        // Wire security (TLS already initialized above, before the Raft
        // transport — see F-0050 fix).
        // ---------------------------------------------------------------
        AuthInterceptor authInterceptor = null;
        AclService aclService = null;
        if (!config.authEnabled()) {
            System.err.println("WARNING: ************************************************************");
            System.err.println("WARNING: Authentication is DISABLED (--auth-token not set).");
            System.err.println("WARNING: All write/delete/admin endpoints are unauthenticated.");
            System.err.println("WARNING: DO NOT run in production without --auth-token.");
            System.err.println("WARNING: ************************************************************");
        }
        if (config.authEnabled()) {
            String expectedToken = config.authToken();
            authInterceptor = new AuthInterceptor(token -> {
                // F-V7-01 fix: Use constant-time comparison to prevent
                // timing side-channel attacks on the auth token.
                if (java.security.MessageDigest.isEqual(
                        expectedToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        token.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                    return new AuthInterceptor.AuthResult.Authenticated("root", Set.of("admin"));
                }
                return new AuthInterceptor.AuthResult.Denied("invalid token");
            });
            aclService = new AclService();
            // Grant root principal full access to all keys
            aclService.grant("", "root", EnumSet.allOf(AclService.Permission.class));
        }

        // ---------------------------------------------------------------
        // Wire health service
        // ---------------------------------------------------------------
        HealthService healthService = new HealthService();
        healthService.registerReadinessCheck(() -> {
            NodeId leader = raftNode.leaderId();
            if (leader != null) {
                return HealthService.CheckResult.healthy("raft-leader");
            }
            return HealthService.CheckResult.unhealthy("raft-leader", "no leader elected");
        });

        // ---------------------------------------------------------------
        // Wire config write service
        // ---------------------------------------------------------------
        ConfigWriteService.RaftProposer proposer = (scope, command) -> {
            ProposalResult result = driver.propose(DEFAULT_RAFT_GROUP, command);
            return result == ProposalResult.ACCEPTED;
        };
        // F-0054: default write rate limit = 10_000/s globally. Docs in
        // ADR-0017 and performance.md reflect this value; a startup line
        // prints the effective rate so operators can audit at boot.
        final int writeRatePerSec = 10_000;
        final int writeBurst = 10_000;
        RateLimiter rateLimiter = new RateLimiter(clock, writeRatePerSec, writeBurst);
        System.out.println("  Write rate   : " + writeRatePerSec + "/s (burst " + writeBurst + ")");
        ConfigWriteService writeService = new ConfigWriteService(proposer, null, rateLimiter,
                () -> raftNode.leaderId());

        // ---------------------------------------------------------------
        // F-0023: Create three dedicated single-threaded executors. They
        // isolate three latency domains:
        //   - tickExecutor: consensus progress (MUST NOT be blocked)
        //   - readDispatchExecutor: HTTP read handler marshalling
        //   - tlsReloadExecutor: slow cert I/O
        //
        // CRITICAL invariant (F-0010): ReadIndexState access is ONLY from
        // the tick thread. readDispatchExecutor never touches it directly
        // — it only marshals dispatch requests from HTTP threads to the
        // tick thread via `tickExecutor.execute(...)`.
        // ---------------------------------------------------------------
        ScheduledExecutorService tickExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "configd-tick");
            t.setDaemon(true);
            return t;
        });
        ScheduledExecutorService readDispatchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "configd-read-dispatch");
            t.setDaemon(true);
            return t;
        });
        ScheduledExecutorService tlsReloadExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "configd-tls-reload");
            t.setDaemon(true);
            return t;
        });

        // ---------------------------------------------------------------
        // Wire config read service with linearizable read support
        //
        // F-0009 fix: The ReadIndex protocol requires:
        //   1. Record commit index (readIndex())
        //   2. Confirm leadership via heartbeat quorum
        //   3. Wait until lastApplied >= readIndex
        //   4. THEN serve the read
        //
        // Previously, readIndex() was called and the result discarded —
        // the read was served immediately without waiting for steps 2-3,
        // making it equivalent to a stale read.
        //
        // F-0010 fix: readIndex() and isReadReady() access ReadIndexState
        // (a non-thread-safe LinkedHashMap). These must be dispatched to
        // the tick thread, not called directly from HTTP handler threads.
        // ---------------------------------------------------------------
        ConfigReadService.ConfigReader configReader = new ConfigReadService.ConfigReader() {
            @Override public io.configd.store.ReadResult get(String key) { return configStore.get(key); }
            @Override public io.configd.store.ReadResult get(String key, long minVersion) { return configStore.get(key, minVersion); }
            @Override public java.util.Map<String, io.configd.store.ReadResult> getPrefix(String prefix) { return configStore.getPrefix(prefix); }
            @Override public long currentVersion() { return configStore.currentVersion(); }
        };
        ConfigReadService readService = new ConfigReadService(configReader, () -> {
            // F-0022 fix: single-future completion-driven pattern.
            // Allocates 1 CompletableFuture per linearizable read (was ~150
            // under stall from the previous polling loop).
            //
            // F-0023 fix: dispatch goes through readDispatchExecutor which
            // marshals the tick-thread work — the HTTP thread never touches
            // ReadIndexState directly.
            //
            // The tick thread calls whenReadReady(readId, cb), which fires
            // the callback as soon as the read transitions to ready or the
            // node steps down. On timeout, we clean up via the tick thread.
            java.util.concurrent.CompletableFuture<Boolean> resultFuture =
                    new java.util.concurrent.CompletableFuture<>();
            // Shared slot so the timeout path can tell the tick thread which
            // readId to clean up. Written only from the tick thread; read
            // only after the timeout expires (guarded by a volatile via the
            // AtomicLong memory-model semantics).
            java.util.concurrent.atomic.AtomicLong readIdRef =
                    new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
            readDispatchExecutor.execute(() -> tickExecutor.execute(() -> {
                try {
                    long readId = raftNode.readIndex();
                    if (readId < 0) {
                        resultFuture.complete(false); // Not leader
                        return;
                    }
                    readIdRef.set(readId);
                    // whenReadReady fires synchronously if already ready,
                    // otherwise registers a one-shot callback fired from
                    // the tick thread after confirmPendingReads / apply.
                    raftNode.whenReadReady(readId, () -> {
                        boolean ready = raftNode.isReadReady(readId);
                        raftNode.completeRead(readId);
                        resultFuture.complete(ready);
                    });
                } catch (Throwable t) {
                    resultFuture.completeExceptionally(t);
                }
            }));
            try {
                return resultFuture.get(150, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (java.util.concurrent.ExecutionException e) {
                return false;
            } catch (java.util.concurrent.TimeoutException e) {
                // Abandon the read; dispatch cleanup to the tick thread so
                // ReadIndexState mutation stays single-threaded (F-0010).
                long readId = readIdRef.get();
                if (readId != Long.MIN_VALUE) {
                    final long finalReadId = readId;
                    tickExecutor.execute(() -> raftNode.completeRead(finalReadId));
                }
                return false;
            }
        });

        // ---------------------------------------------------------------
        // Start HTTP API server
        // ---------------------------------------------------------------
        io.configd.observability.PrometheusExporter prometheusExporter =
                new io.configd.observability.PrometheusExporter(metricsRegistry);
        HttpApiServer httpApiServer;
        try {
            httpApiServer = new HttpApiServer(
                    config.apiPort(), sslContext, healthService, prometheusExporter,
                    configStore, writeService, readService, authInterceptor, aclService);
            httpApiServer.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start HTTP API server on port " + config.apiPort(), e);
        }

        // ---------------------------------------------------------------
        // Start tick loop on the dedicated tickExecutor (F-0023).
        // ---------------------------------------------------------------
        ConfigdServer server = new ConfigdServer(
                config, driver, stateMachine,
                tickExecutor, readDispatchExecutor, tlsReloadExecutor,
                httpApiServer, tcpTransport,
                watchService, fanOutBuffer, compactor, plumtreeNode, hyParViewOverlay,
                subscriptionManager, slowConsumerPolicy, rolloutController);

        final int[] tickCount = {0};
        tickExecutor.scheduleAtFixedRate(() -> {
            try {
                driver.tick();
                propagationMonitor.checkAll();
                watchService.tick();
                plumtreeNode.tick();

                // Compact snapshot history every ~10 seconds
                tickCount[0]++;
                if (tickCount[0] % COMPACTION_INTERVAL_TICKS == 0) {
                    compactor.compact();
                }
            } catch (Throwable t) {
                // H-009 (iter-2): ScheduledExecutorService silently cancels future
                // executions on uncaught exceptions. The tick loop drives consensus
                // (elections, heartbeats, replication) — if it dies, the node
                // becomes a zombie. Replace the historical printStackTrace(System.err)
                // — invisible to centralized log aggregation — with a structured
                // SEVERE record AND a Prometheus counter increment so SREs can
                // alert on tick-loop instability rather than discover it post-mortem.
                handleTickLoopThrowable(t, configdMetrics);
            }
        }, TICK_PERIOD_MS, TICK_PERIOD_MS, TimeUnit.MILLISECONDS);

        // Schedule TLS certificate hot reload when TLS is enabled.
        //
        // F-0023 fix: TLS reload (potentially slow cert / keystore I/O) runs
        // on its OWN executor so it cannot delay the 10ms tick loop or the
        // linearizable read dispatch.
        if (tlsManager != null) {
            tlsReloadExecutor.scheduleAtFixedRate(() -> {
                try {
                    tlsManager.reload();
                } catch (Exception e) {
                    System.err.println("WARNING: TLS reload failed (continuing with current context): "
                            + e.getMessage());
                }
            }, TLS_RELOAD_INTERVAL_MS, TLS_RELOAD_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Configd shutting down...");
            server.shutdown();
        }, "configd-shutdown"));

        return server;
    }

    /**
     * Shuts down the server, stopping the HTTP API, tick loop, and releasing resources.
     * <p>
     * F-0023: shutdown order matters. We must drain {@code readDispatchExecutor}
     * FIRST so no new read tasks are marshalled onto the tick thread. Then we
     * shut the {@code tickExecutor} (which also owns ReadIndexState) so any
     * in-flight reads complete. Finally the {@code tlsReloadExecutor} is the
     * slowest to drain and is stopped last.
     */
    public void shutdown() {
        if (httpApiServer != null) {
            httpApiServer.stop(2);
        }
        // Stop accepting new read marshals first.
        shutdownExecutor(readDispatchExecutor, "read-dispatch", 2);
        // Then stop consensus tick (also drains pending read-index callbacks).
        shutdownExecutor(tickExecutor, "tick", 5);
        // Slow I/O executor can be shut down last — it is independent.
        shutdownExecutor(tlsReloadExecutor, "tls-reload", 2);
        if (tcpTransport != null) {
            try {
                tcpTransport.close();
            } catch (Exception e) {
                System.err.println("Error closing TCP transport: " + e.getMessage());
            }
        }
    }

    /**
     * H-009 (iter-2): handles an unhandled throwable that escaped the tick
     * loop body. This is package-private static so the regression test
     * ({@code TickLoopThrowableHandlerTest}) can drive it directly without
     * standing up an entire {@link ConfigdServer} + scheduler — the catch
     * block in {@code start()} is a one-line call into this method.
     *
     * <p>Two visible side-effects (both load-bearing for SRE alerting):
     * <ol>
     *   <li>Increments {@code configd_tick_loop_throwable_total{class}} via
     *       {@link ConfigdMetrics#onTickLoopThrowable(String)}; the {@code class}
     *       label is {@link SafeLog#cardinalityGuard cardinality-bounded} so a
     *       hostile-input throwable family cannot blow up the series count.</li>
     *   <li>Emits a SEVERE log record with the throwable attached so the
     *       JUL formatter prints the stack trace — replaces the historical
     *       {@code printStackTrace(System.err)} which was invisible to
     *       centralized log aggregation.</li>
     * </ol>
     *
     * <p>Defensive: a {@code null} throwable is treated as
     * {@code class="unknown"} rather than NPE — the handler must NOT itself
     * become a new tick-loop killer.
     *
     * @param t       the unhandled throwable (may be {@code null})
     * @param metrics the metrics registry handle (may be {@code null} in
     *                degenerate test paths; counter increment is skipped)
     */
    static void handleTickLoopThrowable(Throwable t, ConfigdMetrics metrics) {
        String simpleName;
        if (t == null) {
            simpleName = "unknown";
        } else {
            String s = t.getClass().getSimpleName();
            simpleName = (s == null || s.isEmpty()) ? t.getClass().getName() : s;
        }
        String label = (metrics != null)
                ? metrics.onTickLoopThrowable(simpleName)
                : SafeLog.cardinalityGuard(simpleName);
        LOG.log(Level.SEVERE,
                "tick loop unhandled throwable: class=" + simpleName + " bucket=" + label,
                t);
    }

    private static void shutdownExecutor(ScheduledExecutorService exec, String name, int timeoutSec) {
        if (exec == null) return;
        exec.shutdown();
        try {
            if (!exec.awaitTermination(timeoutSec, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns the multi-raft driver for this server.
     */
    public MultiRaftDriver driver() {
        return driver;
    }

    /**
     * Returns the underlying {@link TcpRaftTransport} when peer addresses
     * were configured, or {@code null} for single-node / test mode.
     * <p>
     * Exposed so integration tests (F-0050 regression) can verify that the
     * transport holds a non-null {@link TlsManager} when TLS is enabled.
     */
    public TcpRaftTransport tcpTransport() {
        return tcpTransport;
    }

    /**
     * Returns the config state machine for this server.
     */
    public ConfigStateMachine stateMachine() {
        return stateMachine;
    }

    /**
     * Returns the server configuration.
     */
    public ServerConfig config() {
        return config;
    }

    /**
     * Returns the watch service for push notifications.
     */
    public WatchService watchService() {
        return watchService;
    }

    /**
     * Returns the fan-out buffer for delta distribution.
     */
    public FanOutBuffer fanOutBuffer() {
        return fanOutBuffer;
    }

    /**
     * Returns the compactor for snapshot retention.
     */
    public Compactor compactor() {
        return compactor;
    }

    /**
     * Returns the Plumtree broadcast node.
     */
    public PlumtreeNode plumtreeNode() {
        return plumtreeNode;
    }

    /**
     * Returns the HyParView overlay network manager.
     */
    public HyParViewOverlay hyParViewOverlay() {
        return hyParViewOverlay;
    }

    /**
     * Returns the subscription manager for edge node subscriptions.
     */
    public SubscriptionManager subscriptionManager() {
        return subscriptionManager;
    }

    /**
     * Returns the slow consumer policy.
     */
    public SlowConsumerPolicy slowConsumerPolicy() {
        return slowConsumerPolicy;
    }

    /**
     * Returns the rollout controller.
     */
    public RolloutController rolloutController() {
        return rolloutController;
    }

    private static void printBanner(ServerConfig config) {
        System.out.println("""
                \u256d\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256e
                \u2502            C O N F I G D                  \u2502
                \u2502     Distributed Configuration System      \u2502
                \u2570\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256f
                """);
        System.out.println("  Node ID      : " + config.nodeId());
        System.out.println("  Bind address : " + config.bindAddress() + ":" + config.bindPort());
        System.out.println("  API port     : " + config.apiPort());
        System.out.println("  Data dir     : " + config.dataDir());
        System.out.println("  Peers        : " + config.peers());
        System.out.println("  TLS          : " + (config.tlsEnabled() ? "enabled" : "disabled"));
        System.out.println("  Auth         : " + (config.authEnabled() ? "enabled" : "disabled"));
        System.out.println("  Tick period  : " + TICK_PERIOD_MS + "ms");
        System.out.println("  Distribution : Plumtree + HyParView (wired)");
        System.out.println("  Compaction   : every " + (COMPACTION_INTERVAL_TICKS * TICK_PERIOD_MS / 1000) + "s");
        System.out.println();
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: configd-server --node-id <id> --data-dir <path> --peers <id,id,...>"
                    + " [--bind-address <addr>] [--bind-port <port>] [--api-port <port>]"
                    + " [--tls-cert <path>] [--tls-key <path>] [--tls-trust-store <path>]"
                    + " [--auth-token <token>]");
            System.exit(1);
        }

        ServerConfig config;
        try {
            config = ServerConfig.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }

        printBanner(config);

        ConfigdServer server = start(config);

        System.out.println("Configd server started successfully.");

        // Block main thread until shutdown
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.shutdown();
        }
    }
}
