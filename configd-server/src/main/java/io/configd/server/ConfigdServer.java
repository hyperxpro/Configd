package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.AuditLog;
import io.configd.api.AuthInterceptor;
import io.configd.api.ConfigReadService;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.api.RateLimiter;
import io.configd.api.ReplayGuard;
import io.configd.common.Clock;
import io.configd.common.ConfigScope;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.HyParViewOverlay;
import io.configd.distribution.PlumtreeNode;
import io.configd.distribution.RolloutController;
import io.configd.distribution.SubscriptionManager;
import io.configd.distribution.WatchService;
import io.configd.observability.BurnRateAlertEvaluator;
import io.configd.observability.ConfigdMetrics;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.ProductionSloDefinitions;
import io.configd.observability.PropagationLivenessMonitor;
import io.configd.observability.SafeLog;
import io.configd.observability.SloTracker;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftTransport;
import io.configd.raft.RaftMessage;
import io.configd.raft.ProposeOutcome;
import io.configd.replication.MultiRaftDriver;
import io.configd.replication.OwnerExecutorPool;
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
    // RR-005: applied entries a Raft group may retain past its snapshot point before the tick
    // loop triggers Raft-LOG compaction (distinct from the snapshot-retention Compactor above).
    // Bounds WAL growth — without this trigger compaction was unreachable in the wired server
    // (the only triggerSnapshot caller is the circular sendInstallSnapshot) and the WAL grew for
    // the life of the process, eventually crash-looping recovery at the FileStorage 2 GiB read cap.
    private static final long RAFT_LOG_COMPACTION_THRESHOLD = 10_000;
    private static final int TLS_RELOAD_INTERVAL_MS = 60_000;  // every 60 seconds
    private static final int FANOUT_BUFFER_CAPACITY = 10_000;
    // RR-004 / ADR-0033: single end-to-end commit-confirmation deadline for a
    // write, in REAL milliseconds on the outcome future (NOT a tick count — it
    // must not route through the RR-006-affected tick-config path). 5 s default,
    // chosen >> worst-case re-election; revisit when RR-006 fixes the 10x tick-unit bug.
    private static final long WRITE_COMMIT_TIMEOUT_MS = 5_000;

    private final ServerConfig config;
    // Phase 0 — Workstream B (Stage 1B): R-01 (the single `configd-tick` thread) is DELETED.
    // Consensus now runs through the owner-executor pool — `ownerExecutor(gid) = pool[gid % N]` —
    // so every OWNER-ONLY entry point of a group's RaftNode runs on that group's owner thread, and
    // `bindOwnerThread()` activates the `assertOwnerThread()` net in production (a missed marshalling
    // hop now trips `raft_owner_thread`). At N=1 (this stage, `configd.raft.ownerPoolSize`, default 1)
    // a single owner thread does tick + co-tenant + marshalled work at the same cadence/FIFO as the
    // old `configd-tick` thread — behaviourally exact R-01, minus the deleted single-thread assumption.
    //
    //   ownerPool            — N single-thread owner executors; consensus tick (per-owner), inbound
    //                          handleMessage(), propose(), readIndex/flush all run on a group's owner.
    //                          At N=1 also rides the co-tenant housekeeping (watch/plumtree/
    //                          propagation/compactor) exactly as the old tick thread did.
    //   readDispatchExecutor — HTTP read handler → owner-thread marshalling; decouples HTTP threads
    //                          from owner-loop bursts (double-hop, H-2).
    //   tlsReloadExecutor    — slow I/O (cert reload every 60s); its latency must NEVER delay an
    //                          owner tick or reads.
    private final OwnerExecutorPool ownerPool;
    private final ScheduledExecutorService readDispatchExecutor;
    private final ScheduledExecutorService tlsReloadExecutor;
    private final MultiRaftDriver driver;
    private final ConfigStateMachine stateMachine;
    private final HttpApiServer httpApiServer;
    private final TcpRaftTransport tcpTransport; // nullable when peer addresses not configured
    /** C1 fan-out edge endpoint (ADR-0037); null when {@code --edge-port} is absent. */
    private final io.configd.server.fanout.FanOutServer fanOutServer;

    // Distribution layer
    private final WatchService watchService;
    private final FanOutBuffer fanOutBuffer;
    private final Compactor compactor;
    private final PlumtreeNode plumtreeNode;
    private final HyParViewOverlay hyParViewOverlay;
    private final SubscriptionManager subscriptionManager;
    private final RolloutController rolloutController;
    /** S6/WS-A: the live /metrics exporter — exposed via {@link #scrapeMetrics()} so a contract
     *  test can assert the running server emits the SLO series with real data (not zero). */
    private final io.configd.observability.PrometheusExporter prometheusExporter;

    private ConfigdServer(ServerConfig config, MultiRaftDriver driver,
                          ConfigStateMachine stateMachine,
                          OwnerExecutorPool ownerPool,
                          ScheduledExecutorService readDispatchExecutor,
                          ScheduledExecutorService tlsReloadExecutor,
                          HttpApiServer httpApiServer,
                          TcpRaftTransport tcpTransport,
                          io.configd.server.fanout.FanOutServer fanOutServer,
                          WatchService watchService,
                          FanOutBuffer fanOutBuffer,
                          Compactor compactor,
                          PlumtreeNode plumtreeNode,
                          HyParViewOverlay hyParViewOverlay,
                          SubscriptionManager subscriptionManager,
                          RolloutController rolloutController,
                          io.configd.observability.PrometheusExporter prometheusExporter) {
        this.config = config;
        this.driver = driver;
        this.stateMachine = stateMachine;
        this.ownerPool = ownerPool;
        this.readDispatchExecutor = readDispatchExecutor;
        this.tlsReloadExecutor = tlsReloadExecutor;
        this.httpApiServer = httpApiServer;
        this.tcpTransport = tcpTransport;
        this.fanOutServer = fanOutServer;
        this.watchService = watchService;
        this.fanOutBuffer = fanOutBuffer;
        this.compactor = compactor;
        this.plumtreeNode = plumtreeNode;
        this.hyParViewOverlay = hyParViewOverlay;
        this.subscriptionManager = subscriptionManager;
        this.rolloutController = rolloutController;
        this.prometheusExporter = prometheusExporter;
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
        // PA-2021 (ADR-0042): the at-rest integrity codec for the Raft durability
        // artifacts (snapshot blob, WAL records, raft.persistent_state). KEYED with
        // K_integrity derived from the cluster signing key — fail-closed: a tampered
        // artifact is refused on recovery. Built from keyStore below.
        io.configd.common.IntegrityEnvelope raftIntegrity;
        // S7/D-2 upgrade: the audit-log chain MAC key K_audit, derived from the
        // SAME cluster signing key as the Raft at-rest key but DOMAIN-SEPARATED by
        // a distinct HKDF info string so the two derived keys are independent.
        javax.crypto.SecretKey auditLogKey;
        try {
            Path keyFile = config.signingKeyFile() != null
                    ? config.signingKeyFile()
                    : dataDir.resolve("signing-key.bin");
            SigningKeyStore keyStore = SigningKeyStore.loadOrCreate(keyFile);
            configSigner = new ConfigSigner(keyStore.keyPair());
            raftIntegrity = deriveRaftIntegrityEnvelope(keyStore, keyFile, dataDir);
            auditLogKey = deriveAuditLogKey(keyStore);
        } catch (SecurityException se) {
            // D-1 (P1) fail-closed: surface the co-location refusal with its clear, actionable
            // message — do NOT wrap it as a generic "failed to load key" error.
            throw se;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load or create Ed25519 signing key", e);
        }

        // Initialize config store with empty initial snapshot
        ConfigSnapshot initialSnapshot = new ConfigSnapshot(
                HamtMap.empty(), 0L, clock.currentTimeMillis());
        VersionedConfigStore configStore = new VersionedConfigStore(initialSnapshot, clock);

        // ---------------------------------------------------------------
        // R-02: turn the runtime invariant safety net ON. Build the metrics
        // registry + InvariantMonitor HERE (before the state machine and Raft
        // node) so BOTH are fed a REAL checker instead of NOOP. The monitor
        // shares this registry, so violations surface at /metrics (the
        // PrometheusExporter reads the same registry). Prod is fail-open:
        // a violation increments a named metric + SEVERE log and keeps serving
        // (never throw in a running server). The two InvariantChecker SAMs
        // (RaftNode's and ConfigStateMachine's) both bridge to this monitor.
        // ---------------------------------------------------------------
        MetricsRegistry metricsRegistry = new MetricsRegistry();
        // S6/WS-A: JVM/process runtime gauges (heap, threads, FDs, GC) — the runtime dashboard
        // board + leak alerts query these; before S6 no JVM series were served.
        io.configd.observability.JvmMetrics.bind(metricsRegistry);
        InvariantMonitor invariantMonitor = new InvariantMonitor(metricsRegistry, false);
        ConfigStateMachine.InvariantChecker smInvariantChecker = invariantMonitor::check;
        RaftNode.InvariantChecker raftInvariantChecker = invariantMonitor::check;

        // S6/WS-A: build ConfigdMetrics HERE (before the state machine) so the SLO series are
        // actually RECORDED, not merely registered-at-zero (the S1 "9 SLO metrics hardwired to
        // zero" defect, which was still live: every record/increment handle was dead and the
        // raft-pending gauge was literally () -> 0L).
        //   - the apply path feeds it via ServerStateMachineMetrics (apply_seconds + snapshot ctrs);
        //   - the raft pending-apply gauge reads `pendingApplyEntries`, an AtomicLong published on
        //     the tick thread (RaftLog.commitIndex/lastApplied are non-volatile plain longs touched
        //     only on the tick thread — R-01 — so the scrape thread must read a published snapshot);
        //   - write_commit_* + the overload-reject counter are recorded at the raftProposer site;
        //   - raft_elections is incremented on the tick thread by positive currentTerm() deltas.
        // (RR-008/H-009 still use this same handle for the inbound + tick-loop throwable counters.)
        java.util.concurrent.atomic.AtomicLong pendingApplyEntries =
                new java.util.concurrent.atomic.AtomicLong(0L);
        ConfigdMetrics configdMetrics =
                new ConfigdMetrics(metricsRegistry, pendingApplyEntries::get);

        ConfigStateMachine stateMachine =
                new ConfigStateMachine(configStore, clock, smInvariantChecker, configSigner,
                        new ServerStateMachineMetrics(configdMetrics));

        // Initialize Raft with durable WAL storage.
        // RR-006: pass the real scheduler tick period (TICK_PERIOD_MS) so the
        // documented millisecond budgets (150-300ms election timeout, 50ms
        // heartbeat) are converted to the correct tick counts and realized at
        // runtime. Before this fix the ms values were consumed as raw tick
        // counts, inflating every interval 10x (re-election measured ~2.3s).
        // S7.5: Raft timing is operator-tunable via system properties (defaults = the documented
        // 150/300/50 ms). PART 2 found the as-built ceiling is leadership-churn / heartbeat
        // starvation under load, not fsync; a longer election timeout (etcd default is 1000 ms) and
        // shorter heartbeat give more headroom for tick-thread scheduling jitter. Exposed so the
        // tuning can be measured here and set by operators in production.
        int electionMinMs = Integer.getInteger("configd.raft.electionTimeoutMinMs", 150);
        int electionMaxMs = Integer.getInteger("configd.raft.electionTimeoutMaxMs", 300);
        int heartbeatMs = Integer.getInteger("configd.raft.heartbeatIntervalMs", 50);
        int maxInflight = Integer.getInteger("configd.raft.maxInflightAppends", 10);
        RaftConfig raftConfig = new RaftConfig(config.nodeId(), config.peers(),
                electionMinMs, electionMaxMs, heartbeatMs, 64, 256 * 1024, 1024, maxInflight,
                TICK_PERIOD_MS);
        System.out.println("  Raft timing  : election " + electionMinMs + "-" + electionMaxMs
                + "ms, heartbeat " + heartbeatMs + "ms, maxInflightAppends " + maxInflight);
        // PA-2021: the keyed integrity envelope authenticates the snapshot blob and
        // WAL records written/recovered through this RaftLog.
        RaftLog raftLog = new RaftLog(storage, raftIntegrity);
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
                random, storage, raftInvariantChecker, raftIntegrity);

        // Initialize multi-raft driver
        MultiRaftDriver driver = new MultiRaftDriver(config.nodeId(), clock);
        driver.addGroup(DEFAULT_RAFT_GROUP, raftNode);

        // ---------------------------------------------------------------
        // Phase 0 — Workstream B (Stage 1B): the R-01 single-`configd-tick`-thread DELETION.
        // Create the owner-executor pool HERE — before wiring the transport — so the inbound Raft
        // handler can marshal onto the GROUP'S OWNER (`driver.ownerExecutor(gid)`), not a global
        // alias. The pool replaces the old single `tickExecutor`:
        //   - ownerPool (N owners, default N=1 via `configd.raft.ownerPoolSize`): each group binds to
        //     `ownerExecutor(gid) = pool[gid % N]`; ALL of that group's OWNER-ONLY RaftNode work —
        //     per-owner tick, inbound handleMessage(), propose(), readIndex/flush — runs on its owner
        //     thread, so the unsynchronised RaftNode is still only ever touched by one thread PER
        //     GROUP. `bindOwnerThread()` (below, first task on the owner) activates the
        //     assertOwnerThread() net in production: a missed hop now trips `raft_owner_thread`.
        //   - readDispatchExecutor: HTTP read handler marshalling (double-hop onto the owner, H-2)
        //   - tlsReloadExecutor: slow cert I/O
        //
        // CRITICAL invariant (F-0010 + R-01′): ALL RaftNode access for a group — ticks, inbound
        // messages, proposals, and ReadIndexState reads — happens ONLY on that group's owner thread.
        // readDispatchExecutor and the inbound/propose handlers never touch the node directly; they
        // marshal via `driver.ownerExecutor(gid).execute(...)`. At N=1 a single owner thread does all
        // of it at the same cadence/FIFO as the deleted `configd-tick` thread (behaviourally exact).
        // ---------------------------------------------------------------
        OwnerExecutorPool ownerPool =
                new OwnerExecutorPool(Integer.getInteger("configd.raft.ownerPoolSize", 1));
        driver.setOwnerPool(ownerPool);
        System.out.println("  Owner pool   : " + ownerPool.size()
                + " owner thread(s) [Phase 0 B Stage 1B — R-01 deleted, consensus via ownerExecutor(gid)]");
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
        // S7.5 PART 2 — group commit. Dispatch the coalescing durability flush onto the GROUP'S OWNER
        // executor (R-01′: all of a group's RaftNode mutation stays on its one owner thread — Stage 1B
        // retarget from the deleted single `tickExecutor` to `driver.ownerExecutor(gid)`). Entries
        // proposed concurrently are appended no-sync (RaftNode.propose -> RaftLog.appendNoSync) and
        // force-synced together by one flush task — amortizing the per-op force(true) that PART 1
        // showed was serializing the consensus thread (heartbeat starvation -> election churn ->
        // ~380 commits/s while the NVMe sat ~86% idle). Tunables (system properties) drive the
        // sizing curve and an apples-to-apples before/after on THIS binary:
        //   -Dconfigd.groupCommit.enabled=false -> keep synchronous per-op fsync (the PART 1 baseline)
        //   -Dconfigd.groupCommit.maxBatch=N     -> cap entries per fsync (default 4096; bounds latency)
        //   -Dconfigd.groupCommit.lingerMicros=T -> linger to grow the batch (default 0 = flush ASAP)
        // ---------------------------------------------------------------
        // The owner executor for the default group — every marshalling hop below (flush, inbound,
        // propose, read) targets THIS, expressed as ownerExecutor(gid), so the group's RaftNode is
        // only ever touched on its bound owner thread (the assertOwnerThread() net backstops it).
        ScheduledExecutorService defaultGroupOwner = driver.ownerExecutor(DEFAULT_RAFT_GROUP);
        boolean groupCommitEnabled = Boolean.parseBoolean(
                System.getProperty("configd.groupCommit.enabled", "true"));
        if (groupCommitEnabled) {
            int groupCommitMaxBatch = Integer.getInteger("configd.groupCommit.maxBatch", 4096);
            long groupCommitLingerMicros = Long.getLong("configd.groupCommit.lingerMicros", 0L);
            raftNode.setGroupCommit(
                    (flush, delayMicros) -> {
                        if (delayMicros <= 0) {
                            defaultGroupOwner.execute(flush);
                        } else {
                            defaultGroupOwner.schedule(flush, delayMicros, TimeUnit.MICROSECONDS);
                        }
                    },
                    groupCommitMaxBatch, groupCommitLingerMicros);
            System.out.println("  Group commit : ENABLED (maxBatch=" + groupCommitMaxBatch
                    + ", lingerMicros=" + groupCommitLingerMicros + ")");
        } else {
            System.out.println("  Group commit : DISABLED (synchronous per-op fsync — PART 1 baseline)");
        }

        // RR-008 (S4): the inbound-routing handler needs the `configdMetrics` handle (built
        // earlier, before the state machine — S6/WS-A) so a Throwable escaping
        // driver.routeMessage (e.g. a disk write failing during applyCommitted -> apply on a
        // follower) is surfaced as a counter + SEVERE log rather than swallowed by the executor
        // (mute zombie). The handle is passed to the inbound handler registration below.

        // ---------------------------------------------------------------
        // H-6 (Phase 0 B Stage 1B) — BIND THE OWNER. Submit raftNode.bindOwnerThread() as the FIRST
        // task on the default group's owner executor, BEFORE the inbound handler is published (the
        // transport accept loop below) and BEFORE the tick loop is scheduled (further down). Because
        // the owner is a single-thread executor with FIFO ordering, this bind runs before any inbound
        // routing / propose / tick task that is enqueued later — even an inbound message arriving the
        // instant tcpTransport.start() returns marshals BEHIND this already-submitted bind. NEVER bind
        // in the constructor (it runs on `main` and legitimately touches state during recovery).
        // After this task runs, assertOwnerThread() is ACTIVE for this RaftNode: any off-owner entry
        // trips `raft_owner_thread` (metric + SEVERE in prod, throw in sim) — the R-01′ net, live.
        // (C2: the bind + the addGroup ConcurrentHashMap put give the happens-before edge that keeps
        // monitorView() non-null for the off-owner scrape — H-5.)
        defaultGroupOwner.execute(raftNode::bindOwnerThread);

        // Register inbound message handler on TCP transport.
        // R-01′: marshal inbound routing onto the GROUP'S OWNER (driver.ownerExecutor(gid)) so
        // node.handleMessage() (and applyCommitted -> stateMachine.apply) never runs concurrently
        // with the per-owner tick on the non-synchronized RaftNode. Registration stays BEFORE start()
        // so the handler is published (behind the bind task) before the accept loop begins.
        if (tcpTransport != null) {
            RaftTransportAdapter adapter = (RaftTransportAdapter) transport;
            adapter.registerInboundHandler(
                    raftInboundHandler(driver, DEFAULT_RAFT_GROUP,
                            driver.ownerExecutor(DEFAULT_RAFT_GROUP), configdMetrics));
            try {
                tcpTransport.start();
            } catch (Exception e) {
                throw new RuntimeException("Failed to start TCP Raft transport on " + config.bindAddress() + ":" + config.bindPort(), e);
            }
        }

        // ---------------------------------------------------------------
        // Wire distribution layer
        // ---------------------------------------------------------------
        // §4.6 / ADR-0034: the fan-out buffer is the bounded hot-path cache
        // implementing CommitNotificationSource. Drop-oldest overflow increments
        // fanout_buffer_dropped_total so a lagging Session-3 consumer's GAP is
        // observable; the log+snapshot (via SnapshotReplaySource) is the source
        // of truth it replays from.
        MetricsRegistry.Counter fanOutDroppedCounter =
                metricsRegistry.counter("fanout.buffer.dropped");
        FanOutBuffer fanOutBuffer =
                new FanOutBuffer(FANOUT_BUFFER_CAPACITY, fanOutDroppedCounter::increment);
        Compactor compactor = new Compactor();
        WatchService watchService = new WatchService(clock);
        SubscriptionManager subscriptionManager = new SubscriptionManager();
        // S6/WS-A: subscribed-prefix capacity gauge (sampled snapshot; benign-race size() read).
        configdMetrics.bindSubscriptionPrefixGauge(subscriptionManager::prefixCount);
        RolloutController rolloutController = new RolloutController(clock);
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
            // §4.6 / ADR-0034 + ADR-0035: publish the full commit notification.
            // `version` is the ADR-0033 applied-mutation seq S (the listener fires
            // only on mutating applies). The commit timestamp is the leader's wall
            // clock captured here on the apply thread — the single authoritative
            // §2 staleness clock ADR-0035 redefined (NOT a per-entry HLC). This
            // runs on the leader's apply path, so `clock.currentTimeMillis()` is
            // the leader-assigned commit timestamp the edge measures staleness
            // against.
            long commitTimestampMillis = clock.currentTimeMillis();
            fanOutBuffer.publish(new CommitNotification(version, commitTimestampMillis, delta));
            compactor.addSnapshot(configStore.snapshot());
        });

        // Register state machine listener: feed WatchService for push notifications
        stateMachine.addListener(watchService::onConfigChange);

        // ---------------------------------------------------------------
        // Wire observability
        // ---------------------------------------------------------------
        // (metricsRegistry + invariantMonitor were created earlier, before the
        // state machine, so the runtime invariant net could be wired — see R-02.)
        SloTracker sloTracker = new SloTracker();
        ProductionSloDefinitions.register(sloTracker);
        BurnRateAlertEvaluator burnRateAlertEvaluator = new BurnRateAlertEvaluator(sloTracker);
        PropagationLivenessMonitor propagationMonitor =
                new PropagationLivenessMonitor(1000, metricsRegistry);
        // (H-009 + RR-008) `configdMetrics` is constructed earlier, before the inbound
        // handler is registered, so both the tick-loop and inbound-routing throwable
        // handlers have a stable metrics handle. Eager construction populates the SLO
        // counter families for the first scrape.

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
        // R-01′: marshal proposals onto the GROUP'S OWNER (driver.ownerExecutor(gid)) so node.propose()
        // (log/term/commitIndex mutation + applyCommitted -> stateMachine.apply) never races the
        // per-owner tick or the inbound handler. RR-004 / ADR-0033: the SAME marshalled task also
        // registers the commit-outcome callback, capturing (index,term) INSIDE the task (H-1 — the
        // synchronous result never crosses the marshalling boundary); the HTTP write thread blocks on
        // one end-to-end WRITE_COMMIT_TIMEOUT_MS deadline and gets a commit-confirmed answer
        // (Committed/Lost/NotLeader/Indeterminate/Overloaded).
        ConfigWriteService.RaftProposer proposer =
                raftProposer(driver, DEFAULT_RAFT_GROUP,
                        driver.ownerExecutor(DEFAULT_RAFT_GROUP), WRITE_COMMIT_TIMEOUT_MS,
                        configdMetrics);
        // F-0054: default write rate limit = 10_000/s globally. Docs in
        // ADR-0017 and performance.md reflect this value; a startup line
        // prints the effective rate so operators can audit at boot.
        final int writeRatePerSec = 10_000;
        final int writeBurst = 10_000;
        RateLimiter rateLimiter = new RateLimiter(clock, writeRatePerSec, writeBurst);
        System.out.println("  Write rate   : " + writeRatePerSec + "/s (burst " + writeBurst + ")");
        // S7.5 per-principal rate limiting: each authenticated principal gets its OWN token bucket
        // (same params as the global), so one noisy/hostile tenant cannot consume the whole write
        // budget and starve others. The global rateLimiter remains the fallback for unauthenticated /
        // overflow requests. Gate stays before the Raft proposal (RR-002-safe).
        ConfigWriteService writeService = new ConfigWriteService(proposer, null, rateLimiter,
                () -> raftNode.leaderId(),
                () -> new RateLimiter(clock, writeRatePerSec, writeBurst));

        // (Tick / read-dispatch / TLS-reload executors are created earlier,
        // right after the multi-raft driver, so the inbound Raft handler can
        // marshal onto the tick executor — see the F-0023 + R-01 block above.)

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
            // marshals the owner-thread work — the HTTP thread never touches
            // ReadIndexState directly.
            //
            // R-01′ (H-2): the read keeps its double-hop, but the inner hop now targets the GROUP'S
            // OWNER (driver.ownerExecutor(gid)), not the deleted single tick executor. The owner calls
            // whenReadReady(readId, cb), which fires the callback as soon as the read transitions to
            // ready or the node steps down. On timeout, we clean up via the owner.
            java.util.concurrent.CompletableFuture<Boolean> resultFuture =
                    new java.util.concurrent.CompletableFuture<>();
            // Shared slot so the timeout path can tell the owner which readId to clean up. Written
            // only from the owner thread; read only after the timeout expires (guarded by a volatile
            // via the AtomicLong memory-model semantics).
            java.util.concurrent.atomic.AtomicLong readIdRef =
                    new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
            readDispatchExecutor.execute(() -> defaultGroupOwner.execute(() -> {
                try {
                    long readId = raftNode.readIndex();
                    if (readId < 0) {
                        resultFuture.complete(false); // Not leader
                        return;
                    }
                    readIdRef.set(readId);
                    // whenReadReady fires synchronously if already ready,
                    // otherwise registers a one-shot callback fired from
                    // the owner thread after confirmPendingReads / apply.
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
                // Abandon the read; dispatch cleanup to the group's owner so
                // ReadIndexState mutation stays single-owner-threaded (F-0010 / R-01′).
                long readId = readIdRef.get();
                if (readId != Long.MIN_VALUE) {
                    final long finalReadId = readId;
                    defaultGroupOwner.execute(() -> raftNode.completeRead(finalReadId));
                }
                return false;
            }
        });

        // ---------------------------------------------------------------
        // Start HTTP API server
        // ---------------------------------------------------------------
        // S6/WS-A: pass ConfigdMetrics.histogramSchedules() so the SLO histograms render
        // `_bucket{le=...}` lines (write_commit/apply/propagation) — the exact series the
        // burn-rate alerts query. Without the schedules the exporter emits quantile lines instead
        // and the alert bucket series are empty (a blind-dashboard defect that survived F5/H-001).
        io.configd.observability.PrometheusExporter prometheusExporter =
                new io.configd.observability.PrometheusExporter(
                        metricsRegistry, ConfigdMetrics.histogramSchedules());
        // RR-020 / ADR-0030 INV-1: strong-read (GLOBAL/security) key class is
        // config-driven via --strong-read-prefixes (default "secure/"); those
        // keys are served fail-closed linearizable, with raftNode.leaderId() as
        // the X-Leader-Hint source for retries.
        StrongReadPolicy strongReadPolicy = new StrongReadPolicy(config.strongReadPrefixes());
        System.out.println("  Strong reads : " + strongReadPolicy.prefixes()
                + " (fail-closed linearizable, ADR-0030 INV-1)");

        // S7/D-2: tamper-evident security audit log. Enabled whenever auth is on
        // (the audit trail only has subjects to record once there are principals).
        // KEYED HMAC-SHA256 chain under K_audit (derived above), so a file-rewriting
        // attacker (threat A2/T3) cannot forge a consistent chain. Backed by the
        // durable, append+CRC Storage; bounded to AuditLog.DEFAULT_MAX_RECORDS.
        AuditLog auditLog = (authInterceptor != null) ? new AuditLog(storage, clock, auditLogKey) : null;
        if (auditLog != null) {
            System.out.println("  Audit log    : security-audit (KEYED HMAC-SHA256 chain, append-only, cap "
                    + AuditLog.DEFAULT_MAX_RECORDS + ")");
        }
        // S7/D-3: replay protection. OPT-IN (default OFF for pre-production
        // back-compat); enabled via -Dconfigd.replay.enabled=true so no new CLI/
        // ServerConfig surface is added. Defends only against PASSIVE
        // capture-and-replay; a token holder can still mint fresh requests
        // (recommend per-request HMAC content signing for S8).
        ReplayGuard replayGuard = null;
        if (Boolean.getBoolean("configd.replay.enabled")) {
            replayGuard = new ReplayGuard(clock);
            System.out.println("  Replay guard : ON (window " + ReplayGuard.DEFAULT_WINDOW_MS
                    + "ms, nonce cap " + ReplayGuard.DEFAULT_MAX_NONCES + ")");
        }

        HttpApiServer httpApiServer;
        try {
            httpApiServer = new HttpApiServer(
                    config.apiPort(), sslContext, healthService, prometheusExporter,
                    configStore, writeService, readService, authInterceptor, aclService,
                    strongReadPolicy, () -> raftNode.leaderId(), auditLog, replayGuard);
            httpApiServer.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start HTTP API server on port " + config.apiPort(), e);
        }

        // ---------------------------------------------------------------
        // C1 fan-out edge endpoint (ADR-0037), optional (--edge-port). It
        // drives the SAME FanOutSessionCore the simulator drives, pulling via
        // the ADR-0034 readSince/ReplaySource seams ONLY — no work on the apply
        // path. Reuses the Raft TlsManager (REQUIRED mTLS when TLS is on;
        // plaintext for single-node/test, matching the Raft transport policy).
        // ---------------------------------------------------------------
        io.configd.server.fanout.FanOutServer fanOutServer = null;
        if (config.edgeEnabled()) {
            io.configd.server.fanout.RegistryFanOutSessionMetrics fanOutMetrics =
                    new io.configd.server.fanout.RegistryFanOutSessionMetrics(metricsRegistry);
            io.configd.distribution.ReplaySource edgeReplaySource =
                    new io.configd.distribution.SnapshotReplaySource(stateMachine.store()::snapshot);
            // C4: the slow-consumer governor (per-cert-identity quarantine / unhealthy
            // policy, architecture §7 ladder) — consulted by the FanOutServer at
            // SUBSCRIBE and fed by the per-session demotion/ack/queue signals.
            io.configd.distribution.fanout.SlowConsumerGovernor slowConsumerGovernor =
                    new io.configd.distribution.fanout.SlowConsumerGovernor(
                            io.configd.distribution.fanout.SlowConsumerPolicyConfig.defaults(),
                            fanOutMetrics);
            fanOutServer = new io.configd.server.fanout.FanOutServer(
                    new InetSocketAddress(config.bindAddress(), config.edgePort()),
                    tlsManager, fanOutBuffer, edgeReplaySource,
                    io.configd.distribution.fanout.FanOutConfig.defaults(),
                    io.configd.server.fanout.FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                    io.configd.server.fanout.FanOutServer.DEFAULT_MAX_SESSIONS,
                    slowConsumerGovernor, fanOutMetrics, clock);
            // F-0050-style fail-closed: if TLS is enabled on the CLI but the
            // edge endpoint did not receive a TlsManager, refuse to start
            // (no plaintext edge traffic in a TLS deployment).
            if (config.tlsEnabled() && tlsManager == null) {
                throw new IllegalStateException(
                        "TLS is enabled but FanOutServer has no TlsManager — refusing to start "
                                + "to avoid plaintext edge traffic");
            }
            try {
                fanOutServer.start();
                System.out.println("  Edge port    : " + fanOutServer.localPort()
                        + (tlsManager != null ? " (mTLS)" : " (PLAINTEXT)") + " [C1 fan-out, ADR-0037]");
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to start FanOutServer on port " + config.edgePort(), e);
            }
        }

        // ---------------------------------------------------------------
        // Start the consensus tick loop on owner[0] (Phase 0 B Stage 1B — R-01 deleted).
        // ---------------------------------------------------------------
        ConfigdServer server = new ConfigdServer(
                config, driver, stateMachine,
                ownerPool, readDispatchExecutor, tlsReloadExecutor,
                httpApiServer, tcpTransport, fanOutServer,
                watchService, fanOutBuffer, compactor, plumtreeNode, hyParViewOverlay,
                subscriptionManager, rolloutController, prometheusExporter);

        final int[] tickCount = {0};
        // S6/WS-A: tracks the highest term observed locally so the elections counter advances by
        // the positive delta each tick (a term bump == an election / leadership change). Read from
        // the owner-published monitor snapshot (H-3), so it is safe off the group's owner thread.
        final long[] lastSeenTerm = {0L};
        // Phase 0 B Stage 2 (M1) — PER-OWNER tick generalization. Schedule a consensus tick on EVERY
        // owner thread: owner[i] runs driver.tickOwner(i)/maybeCompactOwner(i,...), which iterate
        // exactly the groups bound to owner[i] (ownerExecutor(gid) = pool[floorMod(gid, N)]). So each
        // node.tick()/maybeCompact() runs ON its group's owner thread — R-01' holds per group, and the
        // assertOwnerThread() net (ACTIVE in production after the H-6 bind) catches any cross-group
        // access (a group's entry point invoked on the wrong owner). At N=1 the loop runs once for
        // owner[0] and is behaviourally EXACT to the deleted Stage-1B single-owner schedule (same
        // cadence/FIFO/work). At N>1 in production (single group 0, owned by owner[0] since 0 % N == 0)
        // owners 1..N-1 tick zero groups (no-op) until Phase 1 sharding fans groups across them; the
        // multi-group owner-isolation surface is proven by OwnerIsolationMultiOwnerTest. (Workstream C
        // measures throughput on real hardware.)
        //
        // The SINGLETON housekeeping — the H-3 scrape of DEFAULT_RAFT_GROUP + the co-tenant riders
        // (propagation/watch/plumtree/compactor) — must run EXACTLY ONCE per tick, so it rides
        // owner[0] only (the owner == 0 branch), exactly as it did under Stage 1B. The riders do NOT
        // touch any RaftNode (threading-contract §3.7/§5 H-4 recon), so owner[0] is a safe home; the
        // scrape uses monitorView() (S-set, safe cross-owner). tickCount[0]/lastSeenTerm[0] are mutated
        // only in this owner[0]-only branch on owner[0]'s single thread → no cross-owner sharing.
        for (int ownerIdx = 0; ownerIdx < ownerPool.size(); ownerIdx++) {
            final int owner = ownerIdx;
            ownerPool.ownerByIndex(owner).scheduleAtFixedRate(() -> {
                try {
                    driver.tickOwner(owner);

                    if (owner == 0) {
                        // S6/WS-A + H-3: publish the apply backlog (committed-not-applied) for the
                        // raft_pending_apply_entries gauge and advance the election counter, both from
                        // the owner-published monitor snapshot (monitorView() — one volatile load of an
                        // immutable, coherent, <= one-tick-stale view; safe cross-owner). DEFAULT_RAFT_-
                        // GROUP is owned by owner[0] (0 % N == 0) so this read is on-owner here. Kept
                        // immediately after tickOwner(0) — which republished the view it reads — to be
                        // ORDER-EXACT to the deleted Stage-1B schedule. See h3-monitor-view-design.md.
                        io.configd.raft.RaftNode tickNode = driver.getGroup(DEFAULT_RAFT_GROUP);
                        if (tickNode != null) {
                            io.configd.raft.RaftMetrics view = tickNode.monitorView();
                            pendingApplyEntries.set(Math.max(0L, view.commitIndex() - view.lastApplied()));
                            long term = view.currentTerm();
                            if (term > lastSeenTerm[0]) {
                                configdMetrics.raftElections().increment(term - lastSeenTerm[0]);
                                lastSeenTerm[0] = term;
                            }
                        }
                    }

                    // RR-005: threshold-gated Raft-LOG compaction for THIS owner's groups so the WAL
                    // stays bounded (was unreachable in the wired server). Cheap O(groups-on-owner)
                    // check each tick; a group only snapshots when over the threshold, via the RR-003
                    // persist-before-truncate path (durable_prefix_no_gap preserved).
                    driver.maybeCompactOwner(owner, RAFT_LOG_COMPACTION_THRESHOLD);

                    if (owner == 0) {
                        // Co-tenant riders (singleton; do NOT touch RaftNode — H-4 recon). Ride owner[0]
                        // exactly as under Stage 1B (after maybeCompact, order-exact at N=1).
                        propagationMonitor.checkAll();
                        watchService.tick();
                        plumtreeNode.tick();

                        // Compact snapshot history every ~10 seconds.
                        tickCount[0]++;
                        if (tickCount[0] % COMPACTION_INTERVAL_TICKS == 0) {
                            compactor.compact();
                        }
                    }
                } catch (Throwable t) {
                    // H-009 (iter-2): ScheduledExecutorService silently cancels future executions of
                    // THIS owner's tick on an uncaught throwable. The tick loop drives consensus
                    // (elections, heartbeats, replication) — if an owner's tick dies, the groups it
                    // owns become zombies. Replace printStackTrace(System.err) — invisible to log
                    // aggregation — with a structured SEVERE record AND a Prometheus counter increment
                    // so SREs alert on (per-owner) tick-loop instability rather than discover it
                    // post-mortem.
                    handleTickLoopThrowable(t, configdMetrics);
                }
            }, TICK_PERIOD_MS, TICK_PERIOD_MS, TimeUnit.MILLISECONDS);
        }

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
     * Shuts down the server, stopping the HTTP API, owner pool, and releasing resources.
     * <p>
     * F-0023 / R-01′: shutdown order matters. We must drain {@code readDispatchExecutor}
     * FIRST so no new read tasks are marshalled onto an owner thread. Then we shut the
     * owner pool (each owner also owns its groups' ReadIndexState + per-owner tick) so any
     * in-flight reads complete. Finally the {@code tlsReloadExecutor} is the slowest to
     * drain and is stopped last.
     */
    public void shutdown() {
        // C1 edge endpoint FIRST: it is a pure consumer of the ADR-0034 readSince/replay
        // seams, so closing it before the HTTP API / owner pool / Raft teardown lets edge
        // subscribers receive a clean SERVER_SHUTDOWN and stops any new readSince/replay
        // pulls against a store/consensus engine that is about to be torn down. (Order is
        // safe either way — the fan-out never touches the apply path — but closing it first
        // gives the cleanest edge-visible teardown.)
        if (fanOutServer != null) {
            fanOutServer.close();
        }
        if (httpApiServer != null) {
            httpApiServer.stop(2);
        }
        // Stop accepting new read marshals first (so nothing new is enqueued onto an owner).
        shutdownExecutor(readDispatchExecutor, "read-dispatch", 2);
        // Then stop the owner pool (each owner drains its per-owner tick + pending read-index /
        // commit-outcome callbacks). Per-owner shutdown reuses the same shutdownNow()-on-timeout
        // fallback as the other executors, preserving the prior single-`tickExecutor` semantics.
        for (int i = 0; i < ownerPool.size(); i++) {
            shutdownExecutor(ownerPool.ownerByIndex(i), "raft-owner-" + i, 5);
        }
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
     * PA-2021 (ADR-0042): derives the keyed at-rest {@link io.configd.common.IntegrityEnvelope}
     * for the Raft durability artifacts from the cluster signing key, and enforces
     * the D-1 key-location requirement.
     * <p>
     * {@code K_integrity = HKDF-SHA256(IKM = signing private-key encoding,
     * salt = keyId bytes, info = "configd/raft-at-rest-integrity/v2", len = 32)} —
     * derived from the EXISTING cluster-shared signing key, so no new key file and
     * no new key-distribution channel is introduced (charter §10.3). The verify
     * side runs the identical derivation.
     * <p>
     * <b>D-1 (P1) mitigation — FAIL-CLOSED (S7.5):</b> {@code K_integrity}'s secrecy depends on the
     * signing key living OUTSIDE attacker-writable snapshot/WAL/backup storage. If the resolved
     * {@code keyFile} is co-located inside {@code dataDir}, a T3/A2 writer who can tamper the
     * artifacts can also read the key and recompute a valid MAC — Layer B is worthless.
     * {@link #enforceSigningKeyNotColocated} therefore REFUSES TO START by default (the
     * {@code configd.security.allowColocatedSigningKey} opt-out downgrades to a loud warning for
     * dev/test/single-node only); production mounts the key on separate storage — see ADR-0043.
     *
     * @param keyStore the loaded cluster signing key store
     * @param keyFile  the resolved signing-key file path
     * @param dataDir  the Raft data directory (where artifacts live)
     * @return a keyed, fail-closed integrity envelope
     */
    private static io.configd.common.IntegrityEnvelope deriveRaftIntegrityEnvelope(
            SigningKeyStore keyStore, Path keyFile, Path dataDir) {
        // D-1 (P1) FAIL-CLOSED: refuse to derive the at-rest integrity key from a signing key
        // co-located inside the data dir it protects, BEFORE doing any crypto. Default = refuse to
        // start; the dev/test/single-node opt-out (system property OR env var, the latter for CI /
        // docker-compose where -D is awkward) downgrades to a loud warning.
        boolean allowColocated = Boolean.getBoolean("configd.security.allowColocatedSigningKey")
                || "true".equalsIgnoreCase(System.getenv("CONFIGD_ALLOW_COLOCATED_SIGNING_KEY"));
        enforceSigningKeyNotColocated(keyFile, dataDir, allowColocated);
        byte[] ikm = keyStore.keyPair().getPrivate().getEncoded();
        java.util.UUID keyId = keyStore.keyId();
        byte[] salt = java.nio.ByteBuffer.allocate(16)
                .putLong(keyId.getMostSignificantBits())
                .putLong(keyId.getLeastSignificantBits())
                .array();
        byte[] info = "configd/raft-at-rest-integrity/v2"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] k = io.configd.common.Hkdf.deriveKey(ikm, salt, info, 32);
        var integrity = new io.configd.common.IntegrityEnvelope(
                new javax.crypto.spec.SecretKeySpec(k, "HmacSHA256"));
        return integrity;
    }

    /**
     * D-1 (P1) FAIL-CLOSED guard (PA-2021). The at-rest integrity key {@code K_integrity} is
     * HKDF-derived from the cluster signing key, so that signing key MUST NOT live inside the data
     * directory holding the snapshot/WAL/state it protects: a storage-tampering / full-host adversary
     * (threat A2/T3) who can write those artifacts could then ALSO read the co-located key and
     * recompute a valid MAC, making the integrity layer worthless.
     * <p>
     * <b>Default behavior is to REFUSE TO START</b> ({@link SecurityException}). The fail-closed
     * refusal IS the deliverable (charter §2). {@code allowColocated} — wired from the system property
     * {@code configd.security.allowColocatedSigningKey} — downgrades this to a loud warning for
     * dev/test/single-node ONLY; production must mount the signing key on separate storage
     * (KMS/HSM/mounted secret, see ADR-0043) and leave the opt-out unset.
     *
     * @param keyFile        the resolved signing-key file path
     * @param dataDir        the Raft data directory (where the protected artifacts live)
     * @param allowColocated dev/test opt-out; when false (production default) co-location throws
     * @throws SecurityException if the key is co-located inside the data dir and the opt-out is unset
     */
    static void enforceSigningKeyNotColocated(Path keyFile, Path dataDir, boolean allowColocated) {
        if (!isInsideDataDir(keyFile, dataDir)) {
            return; // key is on separate storage — the correct production layout
        }
        if (!allowColocated) {
            throw new SecurityException(
                    "D-1 fail-closed: the cluster signing key is CO-LOCATED inside the data directory"
                            + " it protects (signingKey=" + keyFile.toAbsolutePath()
                            + ", dataDir=" + dataDir.toAbsolutePath() + "). The PA-2021 at-rest"
                            + " integrity key is derived from this signing key, so a storage-tampering"
                            + " adversary could read it and forge a valid MAC, defeating snapshot/WAL/"
                            + "state integrity. Mount the signing key on SEPARATE storage (e.g."
                            + " --signing-key-file /secrets/signing-key.bin) for production; see"
                            + " ADR-0043. For dev/test/single-node ONLY, set"
                            + " -Dconfigd.security.allowColocatedSigningKey=true.");
        }
        // Opt-out explicitly set: proceed, but warn loudly that this layout is insecure.
        String banner = "************************************************************";
        System.err.println("WARNING: " + banner);
        System.err.println("WARNING: At-rest integrity key (PA-2021) is DERIVED FROM the cluster signing");
        System.err.println("WARNING: key, which is CO-LOCATED inside the data directory (INSECURE):");
        System.err.println("WARNING:   signing key : " + keyFile.toAbsolutePath());
        System.err.println("WARNING:   data dir    : " + dataDir.toAbsolutePath());
        System.err.println("WARNING: Permitted only because configd.security.allowColocatedSigningKey=true.");
        System.err.println("WARNING: A storage-tampering adversary (A2/T3) can read the key and forge a");
        System.err.println("WARNING: valid MAC. Mount the signing key on SEPARATE storage for production.");
        System.err.println("WARNING: " + banner);
        LOG.log(Level.SEVERE,
                "PA-2021: integrity key co-located with protected artifacts (signingKey={0}, dataDir={1})"
                        + " — permitted only by the dev opt-out; production must mount the key separately",
                new Object[]{keyFile.toAbsolutePath(), dataDir.toAbsolutePath()});
    }

    /**
     * S7/D-2: derives the audit-log chain MAC key {@code K_audit} from the cluster
     * signing key using the SAME HKDF construction as
     * {@link #deriveRaftIntegrityEnvelope} but with a DISTINCT {@code info} string
     * — {@code "configd/audit-log-integrity/v1"} vs the Raft
     * {@code "configd/raft-at-rest-integrity/v2"} — so the two derived keys are
     * domain-separated and independent (compromise/analysis of one does not yield
     * the other). Same IKM (signing private-key encoding) and salt (keyId bytes).
     * Residual: an attacker who holds the cluster signing key can recompute
     * {@code K_audit} and forge the chain — the same fence as PA-2021 §5.1 (the
     * co-location warning is emitted once by {@code deriveRaftIntegrityEnvelope}).
     *
     * @param keyStore the loaded cluster signing key store
     * @return the HMAC-SHA256 audit-log key
     */
    private static javax.crypto.SecretKey deriveAuditLogKey(SigningKeyStore keyStore) {
        byte[] ikm = keyStore.keyPair().getPrivate().getEncoded();
        java.util.UUID keyId = keyStore.keyId();
        byte[] salt = java.nio.ByteBuffer.allocate(16)
                .putLong(keyId.getMostSignificantBits())
                .putLong(keyId.getLeastSignificantBits())
                .array();
        byte[] info = "configd/audit-log-integrity/v1"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] k = io.configd.common.Hkdf.deriveKey(ikm, salt, info, 32);
        return new javax.crypto.spec.SecretKeySpec(k, "HmacSHA256");
    }

    /** True if {@code keyFile} resolves to a path within {@code dataDir} (D-1 co-location check). */
    private static boolean isInsideDataDir(Path keyFile, Path dataDir) {
        try {
            Path kf = keyFile.toAbsolutePath().normalize();
            Path dd = dataDir.toAbsolutePath().normalize();
            return kf.startsWith(dd);
        } catch (RuntimeException e) {
            // If path normalization fails for any reason, do not suppress the warning
            // silently — err toward warning (treat as co-located) is too noisy, but a
            // failure here is unexpected; log and treat as not-inside (best effort).
            LOG.log(Level.WARNING, "PA-2021: could not compare key/data paths for co-location check", e);
            return false;
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

    /**
     * R-01: builds the inbound Raft message handler that marshals routing onto
     * the single Raft executor (the tick executor), so {@code node.handleMessage}
     * — and the {@code applyCommitted -> stateMachine.apply} it can trigger —
     * never runs concurrently with {@code node.tick()} on the explicitly
     * non-synchronized {@link io.configd.raft.RaftNode} (ADR-0009).
     * <p>
     * Package-private static so the regression/stress test can exercise the
     * real marshalling decision directly without standing up a whole server.
     * Reverting this to a direct {@code driver.routeMessage(...)} call (dropping
     * {@code raftExecutor.execute}) reintroduces the race and makes that test fail.
     */
    static java.util.function.BiConsumer<NodeId, RaftMessage> raftInboundHandler(
            MultiRaftDriver driver, int groupId, java.util.concurrent.Executor raftExecutor) {
        return raftInboundHandler(driver, groupId, raftExecutor, null);
    }

    /**
     * RR-008 (S4): same marshalling as above, but the routing task is wrapped so a
     * Throwable escaping {@code driver.routeMessage} (e.g. a disk write failing during
     * {@code applyCommitted -> apply} on a follower) is SURFACED via
     * {@link #handleInboundRoutingThrowable} — a counter + structured SEVERE log — instead
     * of being swallowed by the executor (which sends it to the worker's default
     * uncaught handler / stderr, invisible to log aggregation, with no metric, and drops
     * the message with no ack: the registered "mute zombie"). The H-009 tick-loop fix
     * covered only the tick lambda; this closes the inbound-routing path.
     */
    static java.util.function.BiConsumer<NodeId, RaftMessage> raftInboundHandler(
            MultiRaftDriver driver, int groupId, java.util.concurrent.Executor raftExecutor,
            ConfigdMetrics metrics) {
        return (from, message) -> raftExecutor.execute(() -> {
            try {
                driver.routeMessage(groupId, message);
            } catch (Throwable t) {
                handleInboundRoutingThrowable(t, metrics);
            }
        });
    }

    /**
     * RR-008 (S4): handles a Throwable that escaped {@code driver.routeMessage} on the
     * inbound-routing task. Mirrors {@link #handleTickLoopThrowable}: a
     * cardinality-bounded {@code configd_inbound_routing_throwable_total{class}} counter
     * increment plus a SEVERE log record with the throwable attached. Package-private
     * static so the regression test ({@code InboundRoutingThrowableHandlerTest}) can drive
     * it directly. Defensive against a null throwable / null metrics — the handler must
     * never itself become a new failure source.
     *
     * @param t       the unhandled throwable (may be {@code null})
     * @param metrics the metrics handle (may be {@code null}; counter increment skipped)
     */
    static void handleInboundRoutingThrowable(Throwable t, ConfigdMetrics metrics) {
        String simpleName;
        if (t == null) {
            simpleName = "unknown";
        } else {
            String s = t.getClass().getSimpleName();
            simpleName = (s == null || s.isEmpty()) ? t.getClass().getName() : s;
        }
        String label = (metrics != null)
                ? metrics.onInboundRoutingThrowable(simpleName)
                : SafeLog.cardinalityGuard(simpleName);
        LOG.log(Level.SEVERE,
                "inbound raft routing unhandled throwable: class=" + simpleName
                        + " bucket=" + label + " — a follower whose message handling throws"
                        + " (e.g. disk fault during apply) would otherwise drop this message"
                        + " with no ack and no signal",
                t);
    }

    /**
     * R-01 + RR-004 / ADR-0033: builds the commit-confirmed write proposer.
     * <p>
     * A SINGLE marshalled tick task performs {@code driver.propose} AND, on
     * acceptance, registers {@code whenCommitOutcome(index, term, cb)} on the
     * owning {@link io.configd.raft.RaftNode} — atomically, in the same task,
     * capturing {@code (index, term)} <em>inside</em> the task (review finding f:
     * a slow tick queue must not lose the position; the at-least-once append must
     * not force Indeterminate when the entry actually appended). All node mutation
     * (propose, the apply it may trigger, and the seam registration/firing) stays
     * on the single tick thread, preserving the R-01 single-thread invariant; the
     * tick thread never waits on the HTTP future (registration is fire-and-return,
     * like {@code whenReadReady}).
     * <p>
     * The calling (HTTP write) thread blocks on ONE end-to-end
     * {@code writeCommitTimeoutMs} deadline (real milliseconds, not a tick count —
     * not subject to the RR-006 10x bug). On commit it returns
     * {@code Committed(seq)}; on definite loss {@code Lost}; on pre-append
     * rejection {@code NotLeader}/{@code Overloaded}; on deadline expiry
     * {@code Indeterminate}, after dispatching a tick-thread cleanup that cancels
     * the now-abandoned one-shot callback (no map-entry leak; no double-complete —
     * the future is already terminal).
     * <p>
     * Package-private static so the regression test can drive the real
     * marshalling decision; reverting to a direct {@code driver.propose(...)} call
     * reintroduces the write-vs-tick race.
     */
    static ConfigWriteService.RaftProposer raftProposer(
            MultiRaftDriver driver, int groupId,
            java.util.concurrent.Executor raftExecutor, long writeCommitTimeoutMs) {
        // Test-convenience overload: records into a throwaway ConfigdMetrics; the production boot
        // path always passes the server's real handle. Kept so the existing marshalling/commit
        // regression tests need not thread a metrics fixture through every call site.
        return raftProposer(driver, groupId, raftExecutor, writeCommitTimeoutMs,
                new ConfigdMetrics(new MetricsRegistry(), () -> 0L));
    }

    /** Production proposer (S6/WS-A): same commit-confirmed semantics as the overload above, plus
     *  end-to-end write-commit metric recording into {@code metrics} (D-2). */
    static ConfigWriteService.RaftProposer raftProposer(
            MultiRaftDriver driver, int groupId,
            java.util.concurrent.Executor raftExecutor, long writeCommitTimeoutMs,
            ConfigdMetrics metrics) {
        // S7.5 admission control (§11): bound the proposals concurrently in-flight on the single tick
        // executor so a sustained write flood cannot starve the periodic heartbeat — the PART 2 churn
        // cause (heartbeat slips past the election timeout → leadership churn → 503 collapse, with the
        // disk and CPU idle). Excess is shed as Overloaded (→ 429 + Retry-After) on the HTTP thread
        // BEFORE the proposal ever reaches the executor, so the leader stays stable and throughput
        // holds at the sustainable rate instead of inverting. Default 0 = OFF (opt-in / A-B via
        // -Dconfigd.write.maxInflightProposals=N); the permit is held only for the bounded
        // writeCommitTimeoutMs wait, so it bounds the executor backlog directly.
        int maxInflightProposals = Integer.getInteger("configd.write.maxInflightProposals", 0);
        java.util.concurrent.Semaphore admission =
                maxInflightProposals > 0 ? new java.util.concurrent.Semaphore(maxInflightProposals) : null;
        return (scope, command) -> {
            // S6/WS-A: end-to-end write-commit latency is measured HERE (HTTP write thread, OFF
            // the R-01 tick hot path) from request entry to outcome — the true "write commit p99"
            // SLO signal (S5: ~16 ms), NOT the apply duration. Recorded via recordWriteOutcome.
            long t0 = System.nanoTime();
            if (admission != null && !admission.tryAcquire()) {
                // In-flight bound reached → graceful shed. This path creates NO tick-executor task,
                // so the heartbeat is never queued behind a flood — the leader stays stable (§11).
                ConfigWriteService.ProposeCommitResult shed =
                        new ConfigWriteService.ProposeCommitResult.Overloaded();
                recordWriteOutcome(metrics, shed, System.nanoTime() - t0);
                return shed;
            }
            java.util.concurrent.CompletableFuture<ConfigWriteService.ProposeCommitResult> f =
                    new java.util.concurrent.CompletableFuture<>();
            // Captured inside the marshalled task so the timeout path can cancel
            // the exact pending callback on the tick thread (mirrors the read
            // path's readIdRef). -1 until an entry is appended.
            java.util.concurrent.atomic.AtomicLong indexRef =
                    new java.util.concurrent.atomic.AtomicLong(-1L);
            raftExecutor.execute(() -> {
                try {
                    ProposeOutcome outcome = driver.propose(groupId, command);
                    if (!outcome.accepted()) {
                        f.complete(switch (outcome.result()) {
                            case OVERLOADED -> new ConfigWriteService.ProposeCommitResult.Overloaded();
                            // NOT_LEADER and TRANSFER_IN_PROGRESS are both pre-append,
                            // definite, redirect-and-retry — surfaced as NotLeader.
                            default -> new ConfigWriteService.ProposeCommitResult.NotLeader();
                        });
                        return;
                    }
                    long index = outcome.index();
                    indexRef.set(index);
                    io.configd.raft.RaftNode node = driver.getGroup(groupId);
                    if (node == null) {
                        // Group vanished between propose and registration — treat as
                        // indeterminate (the append may still commit elsewhere).
                        f.complete(new ConfigWriteService.ProposeCommitResult.Indeterminate());
                        return;
                    }
                    // Register the one-shot commit-outcome callback atomically with
                    // the accepted append, on the tick thread. Fires inline if the
                    // outcome is already decidable (single-node immediate commit).
                    node.whenCommitOutcome(index, outcome.term(), commitOutcome -> {
                        f.complete(switch (commitOutcome.kind()) {
                            case COMMITTED -> new ConfigWriteService.ProposeCommitResult.Committed(commitOutcome.seq());
                            case LOST -> new ConfigWriteService.ProposeCommitResult.Lost();
                            case INDETERMINATE_LOCALLY -> new ConfigWriteService.ProposeCommitResult.Indeterminate();
                        });
                    });
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
            try {
                ConfigWriteService.ProposeCommitResult result =
                        f.get(writeCommitTimeoutMs, TimeUnit.MILLISECONDS);
                recordWriteOutcome(metrics, result, System.nanoTime() - t0);
                return result;
            } catch (java.util.concurrent.TimeoutException e) {
                // Deadline expired with the outcome unknown. Cancel the abandoned
                // one-shot callback on the tick thread so its map entry cannot leak
                // (an isolated leader may never step down or apply). complete()
                // below is a no-op race-wise: the future is returned as
                // Indeterminate regardless.
                long index = indexRef.get();
                if (index >= 0) {
                    raftExecutor.execute(() -> {
                        io.configd.raft.RaftNode node = driver.getGroup(groupId);
                        if (node != null) {
                            node.cancelCommitOutcome(index);
                        }
                    });
                }
                metrics.writeCommitFailed().increment();
                return new ConfigWriteService.ProposeCommitResult.Indeterminate();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                metrics.writeCommitFailed().increment();
                return new ConfigWriteService.ProposeCommitResult.Indeterminate();
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re; // preserve validation exceptions (e.g. IllegalArgumentException)
                }
                throw new RuntimeException("propose failed", cause);
            } finally {
                // S7.5 admission control: release the permit when the HTTP thread finishes waiting
                // (commit, loss, timeout, or error). Holding it only for the bounded
                // writeCommitTimeoutMs wait is what bounds the executor backlog → keeps heartbeats
                // timely. No-op when admission control is disabled (admission == null).
                if (admission != null) {
                    admission.release();
                }
            }
        };
    }

    /**
     * S6/WS-A — records the end-to-end write outcome on the HTTP write thread. Latency is recorded
     * ONLY for a confirmed commit (a failure/redirect would skew the latency histogram); the
     * counters partition outcomes for the control-plane availability SLO (failed/(failed+total))
     * and the sustained-429-rate alert (write_rejected_overloaded, per D-1/D-2).
     */
    private static void recordWriteOutcome(ConfigdMetrics metrics,
            ConfigWriteService.ProposeCommitResult result, long elapsedNanos) {
        switch (result) {
            case ConfigWriteService.ProposeCommitResult.Committed c -> {
                metrics.writeCommitSeconds().record(elapsedNanos);
                metrics.writeCommitTotal().increment();
            }
            case ConfigWriteService.ProposeCommitResult.Overloaded o ->
                    metrics.writeRejectedOverloaded().increment();
            case ConfigWriteService.ProposeCommitResult.Lost l ->
                    metrics.writeCommitFailed().increment();
            case ConfigWriteService.ProposeCommitResult.Indeterminate i ->
                    metrics.writeCommitFailed().increment();
            // NotLeader is a pre-append redirect (retry elsewhere), not a failed commit attempt.
            case ConfigWriteService.ProposeCommitResult.NotLeader n -> { }
            default -> { }
        }
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
     * The C1 fan-out edge endpoint (ADR-0037), or {@code null} when {@code --edge-port} is
     * absent. Exposed for tests and operational checks.
     */
    public io.configd.server.fanout.FanOutServer fanOutServer() {
        return fanOutServer;
    }

    /** The actual bound HTTP API port (resolves an ephemeral {@code --api-port 0}). */
    public int apiPort() {
        return httpApiServer.port();
    }

    /**
     * S6/WS-A: renders the current Prometheus exposition text (identical content to the live
     * {@code /metrics} endpoint, via the same exporter wired with the SLO histogram schedules).
     * Exposed so a contract test can assert the running server emits the SLO series with REAL data
     * — closing the S1 "metrics hardwired to zero" defect at the integration boundary.
     */
    String scrapeMetrics() {
        return prometheusExporter.export();
    }

    /**
     * §4.6 / ADR-0034: the commit-notification boundary Session 3's data plane
     * consumes. Backed by {@link #fanOutBuffer()} (the bounded hot-path cache);
     * cursor-based, replayable, with the drop-oldest overflow contract.
     */
    public CommitNotificationSource commitNotificationSource() {
        return fanOutBuffer;
    }

    /**
     * §4.6 / ADR-0034: the authoritative recovery seam a consumer replays from
     * on a {@link CommitNotificationSource#readSince(long)} GAP. A
     * snapshot-equivalent replay over the live config store.
     */
    public ReplaySource replaySource() {
        return new SnapshotReplaySource(stateMachine.store()::snapshot);
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
