package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.AdminService;
import io.configd.api.AuditLog;
import io.configd.api.AuthInterceptor;
import io.configd.api.ConfigReadService;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.api.RateLimiter;
import io.configd.api.ReplayGuard;
import io.configd.common.Clock;
import io.configd.common.ConfigScope;
import io.configd.common.IntegrityEnvelope;
import io.configd.common.NodeId;
import io.configd.common.SegmentKeyManager;
import io.configd.common.Storage;
import io.configd.common.auth.AuthenticatorChain;
import io.configd.common.config.ConfigException;
import io.configd.common.config.ConfigSource;
import io.configd.common.config.EnvConfigSource;
import io.configd.common.config.LayeredConfigSource;
import io.configd.common.config.SystemPropertyConfigSource;
import io.configd.common.kms.RootKey;
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
import io.configd.raft.AnchorWitness;
import io.configd.raft.AppendEntriesRequest;
import io.configd.raft.CoalescingRaftTransport;
import io.configd.raft.PeerQuorumAnchorWitness;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftMetrics;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.raft.RaftTransport;
import io.configd.raft.RaftMessage;
import io.configd.raft.ProposeOutcome;
import io.configd.raft.NodeAnchorFile;
import io.configd.raft.NodeKeyring;
import io.configd.raft.TopologyDescriptor;
import io.configd.replication.CrossShardBatchException;
import io.configd.replication.CrossShardWriteGuard;
import io.configd.replication.MultiRaftDriver;
import io.configd.replication.OwnerExecutorPool;
import io.configd.replication.StaticShardMap;
import io.configd.server.balance.LeaderBalanceConfig;
import io.configd.server.balance.LeaderBalanceLoop;
import io.configd.server.balance.LeaderBalanceMetrics;
import io.configd.server.balance.LeaderView;
import io.configd.store.Compactor;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigSigner;
import io.configd.store.ConfigSnapshot;
import io.configd.store.ConfigStateMachine;
import io.configd.store.HamtMap;
import io.configd.store.SigningKeyStore;
import io.configd.store.VersionedConfigStore;
import io.configd.netty.NettyRaftTransport;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import io.configd.transport.PeerIdentityPolicy;
import io.configd.transport.RaftTransportEndpoint;
import io.configd.transport.RaftTransportMetrics;
import io.configd.transport.TcpRaftTransport;
import io.configd.transport.TlsConfig;
import io.configd.transport.TlsManager;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
     * Emits the SEVERE record produced by
     * {@link #handleTickLoopThrowable(Throwable, ConfigdMetrics)}. Tests
     * attach a {@link java.util.logging.Handler} to this logger to assert
     * a tick-loop failure is logged through here rather than swallowed silently.
     */
    private static final Logger LOG = Logger.getLogger(ConfigdServer.class.getName());

    private static final int TICK_PERIOD_MS = 10;
    private static final int DEFAULT_RAFT_GROUP = 0;

    // The break-glass root principal - the identity that is authenticated and statically
    // granted allOf - aliased to AclConfigPolicyLoader.RESERVED_PRINCIPAL_ROOT, the single source of truth
    // for the reserved-name guard (the loader's reload path) and the write-time gate
    // (AdminApiHandler.validateAclWrite). The principal granted allOf is therefore provably the SAME
    // literal the loader reserves and the gate validates - a rename can't silently break the un-carveable-
    // root guarantee, and write-time / reload-time reject the identical reserved set.
    private static final String ROOT_PRINCIPAL = AclConfigPolicyLoader.RESERVED_PRINCIPAL_ROOT;
    /**
     * Ceiling on the number of shards a single deploy may configure via {@code configd.raft.shardCount}.
     * Around 10-11 leaders saturate a 16-vCPU node, so N<=16 across a few nodes is a sane upper bound
     * without core overcommit. N is fixed at deploy; changing it requires a manual reshard. Default is
     * {@code 1} (a single group, byte-identical to the single-shard path).
     */
    private static final int MAX_SHARD_COUNT = 16;
    /**
     * The authenticated, versioned topology descriptor file under the data dir. Holds the deploy-time
     * shard count N (the fixed-at-deploy guard) and the topology epoch. Wrapped in the Raft integrity
     * envelope - replacing a plaintext marker file - so the reshard guard and the epoch are
     * tamper-evident under a key.
     */
    private static final String TOPOLOGY_DESCRIPTOR_FILE = "topology-descriptor.dat";
    /**
     * Per-group RNG seed stride (the SplitMix64 / golden-ratio increment). Each group's RaftNode RNG is
     * seeded {@code nodeId*31 + gid*GID_RNG_STRIDE + nanoTime()} so the groups' election timeouts stagger
     * (correlated-election-storm mitigation). At {@code gid == 0} the stride term is 0, so the
     * seed formula is identical to the single-group seed.
     */
    private static final long GID_RNG_STRIDE = 0x9E3779B97F4A7C15L;
    private static final int COMPACTION_INTERVAL_TICKS = 1000;
    // Applied entries a Raft group may retain past its snapshot point before the tick
    // loop triggers Raft-log compaction (distinct from the snapshot-retention Compactor above).
    // Bounds WAL growth - without this trigger, compaction is unreachable in the wired server
    // (the only triggerSnapshot caller is the circular sendInstallSnapshot) and the WAL grows for
    // the life of the process, eventually crash-looping recovery at the FileStorage 2 GiB read cap.
    private static final long RAFT_LOG_COMPACTION_THRESHOLD = 10_000;
    private static final int TLS_RELOAD_INTERVAL_MS = 60_000;
    private static final int FANOUT_BUFFER_CAPACITY = 10_000;
    // Single end-to-end commit-confirmation deadline for a write, in REAL milliseconds on the
    // outcome future (NOT a tick count - it must not route through a tick-config path). 5 s default,
    // chosen >> worst-case re-election.
    private static final long WRITE_COMMIT_TIMEOUT_MS = 5_000;

    // Write-admission cap: the number of proposals allowed concurrently in-flight (awaiting commit)
    // before further writes are shed as Overloaded (HTTP 429) on the HTTP thread, BEFORE they reach an
    // owner executor. This is self-protection: an unbounded write flood can no longer queue behind the
    // periodic heartbeat and starve it into election churn. On by default.
    //
    // Value = the Raft-level maxPendingProposals depth (RaftConfig, 1024) so admission never sheds a write
    // that Raft itself would have accepted - it just moves the same backpressure one hop earlier, off the
    // owner thread. It is far above the measured steady in-flight count (single-box throughput is ~800-1100
    // writes/s and commit latency is single-digit ms, so only tens of proposals are ever in flight at
    // once), so normal and bursty load never sheds; only a pathological flood of >1024 concurrent slow
    // writes is bounded. Operators tune it with -Dconfigd.write.maxInflightProposals=N (0 disables).
    // Package-private so WriteAdmissionDefaultTest can assert the on-by-default value and drive the cap.
    static final int DEFAULT_MAX_INFLIGHT_PROPOSALS = 1024;

    private final ServerConfig config;
    // All RaftNode access for a group - ticks, inbound messages, proposals, and ReadIndex reads -
    // happens ONLY on that group's owner thread. Consensus runs through the owner-executor pool
    // (ownerExecutor(gid) = pool[gid % N]), so every owner-only entry point of a group's RaftNode
    // runs on that group's owner thread. At N=1 a single owner thread does tick + co-tenant +
    // marshalled work.
    //
    //   ownerPool            - N single-thread owner executors; consensus tick (per-owner), inbound
    //                          handleMessage(), propose(), readIndex/flush all run on a group's owner.
    //                          At N=1 also rides the co-tenant housekeeping (watch/plumtree/
    //                          propagation/compactor).
    //   readDispatchExecutor - HTTP read handler -> owner-thread marshalling; decouples HTTP threads
    //                          from owner-loop bursts (double-hop).
    //   tlsReloadExecutor    - slow I/O (cert reload every 60s); its latency must NEVER delay an
    //                          owner tick or reads.
    //   nodeAnchorExecutor   - off-ack-path periodic refresh of the node-level `node-anchor` (audit
    //                          head + shard-liveness digest, K-records-or-T-ms cadence); a slow/failed
    //                          refresh must never delay an owner tick or a read.
    private final OwnerExecutorPool ownerPool;
    private final ScheduledExecutorService readDispatchExecutor;
    private final ScheduledExecutorService tlsReloadExecutor;
    private final ScheduledExecutorService nodeAnchorExecutor;
    /** The node-level durability anchor (topology + audit head + shard-liveness digest); closed on shutdown. */
    private final NodeAnchorFile nodeAnchor;
    /** AnchorWitness SPI realization (peer-quorum provider). The actual rollback-detection logic runs
     *  per-node; this field is the SPI seam for an external-store composition. */
    private final AnchorWitness anchorWitness;
    private final MultiRaftDriver driver;
    private final ConfigStateMachine stateMachine;
    private final NettyHttpApiServer httpApiServer; // admin API on Netty
    private final RaftTransportEndpoint tcpTransport; // Netty consensus transport; nullable when peer addresses not configured
    /** Fan-out edge endpoint; null when {@code --edge-port} is absent. */
    private final io.configd.server.fanout.FanOutEndpoint fanOutServer;
    /**
     * The config-policy loader ({@code null} when auth is disabled). Retained so {@link #shutdown()} can
     * drain its worker thread; at N&gt;1 the loader owns a daemon {@code configd-acl-policy-loader} thread
     * that would otherwise park for the process lifetime (a leak across repeated N&gt;1 start/stop cycles).
     */
    private final AclConfigPolicyLoader aclPolicyLoader;

    // Distribution layer
    private final WatchService watchService;
    private final FanOutBuffer fanOutBuffer;
    private final Compactor compactor;
    private final PlumtreeNode plumtreeNode;
    private final HyParViewOverlay hyParViewOverlay;
    private final SubscriptionManager subscriptionManager;
    private final RolloutController rolloutController;
    /** The live /metrics exporter - exposed via {@link #scrapeMetrics()} so a contract
     *  test can assert the running server emits the SLO series with real data (not zero). */
    private final io.configd.observability.PrometheusExporter prometheusExporter;
    /** The decentralized leadership auto-balance loop; {@code null} at N=1 / single-node / when the kill
     *  switch is off (see the wiring gate). Owns its own dedicated executor; closed on shutdown. */
    private final LeaderBalanceLoop leaderBalanceLoop;
    /**
     * Drain flag. Flipped to {@code true} by {@link #shutdown()} BEFORE anything is closed, so the
     * readiness check reports NOT-ready (HTTP 503) and an LB/orchestrator stops routing while in-flight
     * work drains. Shared with the readiness lambda (captured at wiring time), so the two references are
     * the same {@link java.util.concurrent.atomic.AtomicBoolean} instance. {@code /health/live} is
     * unaffected: liveness is not readiness.
     */
    private final java.util.concurrent.atomic.AtomicBoolean draining;
    /**
     * Bounded quiet-period (milliseconds) {@link #shutdown()} pauses AFTER flipping {@link #draining} and
     * BEFORE closing anything, giving an LB one readiness-probe interval to observe the 503 and stop
     * routing. {@code 0} disables it (the default at N=1 and in tests); the pause is always bounded so
     * shutdown never blocks unboundedly. Configurable via {@code configd.shutdown.drainQuietMs}.
     */
    private final long drainQuietMs;

    private ConfigdServer(ServerConfig config, MultiRaftDriver driver,
                          ConfigStateMachine stateMachine,
                          OwnerExecutorPool ownerPool,
                          ScheduledExecutorService readDispatchExecutor,
                          ScheduledExecutorService tlsReloadExecutor,
                          ScheduledExecutorService nodeAnchorExecutor,
                          NodeAnchorFile nodeAnchor,
                          AnchorWitness anchorWitness,
                          NettyHttpApiServer httpApiServer,
                          RaftTransportEndpoint tcpTransport,
                          io.configd.server.fanout.FanOutEndpoint fanOutServer,
                          AclConfigPolicyLoader aclPolicyLoader,
                          WatchService watchService,
                          FanOutBuffer fanOutBuffer,
                          Compactor compactor,
                          PlumtreeNode plumtreeNode,
                          HyParViewOverlay hyParViewOverlay,
                          SubscriptionManager subscriptionManager,
                          RolloutController rolloutController,
                          io.configd.observability.PrometheusExporter prometheusExporter,
                          LeaderBalanceLoop leaderBalanceLoop,
                          java.util.concurrent.atomic.AtomicBoolean draining,
                          long drainQuietMs) {
        this.config = config;
        this.driver = driver;
        this.stateMachine = stateMachine;
        this.ownerPool = ownerPool;
        this.readDispatchExecutor = readDispatchExecutor;
        this.tlsReloadExecutor = tlsReloadExecutor;
        this.nodeAnchorExecutor = nodeAnchorExecutor;
        this.nodeAnchor = nodeAnchor;
        this.anchorWitness = anchorWitness;
        this.httpApiServer = httpApiServer;
        this.tcpTransport = tcpTransport;
        this.aclPolicyLoader = aclPolicyLoader;
        this.fanOutServer = fanOutServer;
        this.watchService = watchService;
        this.fanOutBuffer = fanOutBuffer;
        this.compactor = compactor;
        this.plumtreeNode = plumtreeNode;
        this.hyParViewOverlay = hyParViewOverlay;
        this.subscriptionManager = subscriptionManager;
        this.rolloutController = rolloutController;
        this.prometheusExporter = prometheusExporter;
        this.leaderBalanceLoop = leaderBalanceLoop;
        this.draining = draining;
        this.drainQuietMs = drainQuietMs;
    }

    /**
     * Creates and starts a Configd server from the given configuration, resolving config against the
     * ambient system-property + environment source (no YAML file). With no YAML layer this reads
     * {@code -D} properties and env vars directly. {@link #main} uses the {@code ConfigSource}-taking
     * overload to add the optional {@code --config} YAML layer.
     *
     * @param config the server configuration
     * @return the running server instance
     */
    public static ConfigdServer start(ServerConfig config) {
        return start(config, ConfigSource.system());
    }

    /**
     * Creates and starts a Configd server, resolving all configuration through the supplied
     * {@link ConfigSource}. The source layers system properties over the environment over an optional
     * YAML file (see {@link #loadBootConfig}); with no YAML layer it is byte-identical to the ambient
     * {@link ConfigSource#system()}.
     *
     * @param config the server configuration
     * @param cfg    the resolved configuration source (highest-precedence first internally)
     * @return the running server instance
     */
    public static ConfigdServer start(ServerConfig config, ConfigSource cfg) {
        // A fail-closed boot must not leave a half-started process alive. Long-lived resources (the
        // Netty consensus/API/edge transports, whose event loops are NON-daemon, plus the owner pool
        // and helper executors) register a teardown action as they come up; if any later step throws
        // (an OIDC discovery failure while building the auth chain, a missing provider module, a
        // port-in-use, a TLS-without-manager refusal), the resources are closed in reverse creation
        // order before the failure propagates, so no live event loop or bound port outlives the failed
        // boot. main() turns the propagated failure into a non-zero System.exit; embedders/tests see a
        // clean throw with nothing leaked. On success the accumulator is abandoned and the returned
        // server's shutdown() owns teardown from that point. Registering is failure-path-only
        // bookkeeping - the success path's behaviour and wire output are unchanged.
        java.util.Deque<Runnable> bootTeardown = new java.util.ArrayDeque<>();
        try {
            return startInternal(config, cfg, bootTeardown);
        } catch (RuntimeException | Error failure) {
            closeBootResources(bootTeardown);
            throw failure;
        }
    }

    private static ConfigdServer startInternal(
            ServerConfig config, ConfigSource cfg, java.util.Deque<Runnable> bootTeardown) {
        // Footgun guard: refuse to SILENTLY expose an unauthenticated store on a public interface (the
        // Redis/etcd "default-open" class). Evaluated FIRST - before any directory creation or port
        // bind - so a misconfigured deployment fails fast and cheaply. A loopback bind, an authenticated
        // store, or the explicit acknowledgement all pass through; only a non-loopback bind with auth OFF
        // and no acknowledgement refuses to start. This is NOT "auth required by default": a deliberate
        // no-auth public deployment stays possible via the override, it just cannot happen by accident.
        enforceBindNotSilentlyPublic(
                config.bindAddress(),
                isAuthEnabled(cfg, config),
                cfg.anyLayerTrue("configd.security.allowInsecurePublicBind"));

        Path dataDir = config.dataDir();
        try {
            Files.createDirectories(dataDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create data directory: " + dataDir, e);
        }

        // Deploy-time shard count N (static-N sharding). Default 1 = a single Raft group (the common
        // case). N is config-derived (a system property, consistent with the other `configd.raft.*`
        // tunables), validated to [1, MAX_SHARD_COUNT], and fixed at deploy (see resolveShardCount). The
        // StaticShardMap routes (scope,key)->shard.
        //
        // At N>1 with the edge endpoint enabled, the fan-out coordinator serves multi-shard WATCH across
        // all N shards (one FanOutSessionCore per shard, a (gid,S)-tagged cursor vector, per-shard
        // resume). The co-resident legacy whole-store SUBSCRIBE plane serves the primary shard only, so
        // the fan-out driver refuses a legacy SUBSCRIBE per connection at N>1 (BAD_SUBSCRIBE) unless the
        // operator sets -Dconfigd.edge.allowPartialShardView; a WATCH is never refused. See the
        // fanOutConfig.withAllowPartialShardView wiring below. At N=1 (one shard is the whole keyspace)
        // the refusal never fires - byte-identical.
        int shardCount = resolveShardCount(cfg);
        // The StaticShardMap is constructed BELOW, after the Raft integrity envelope exists: its
        // epoch() authority is the authenticated topology descriptor, which is read/verified with
        // that same K_integrity envelope. Building the map here would have to hardcode the epoch,
        // defeating the tamper-evident descriptor.

        Storage storage = Storage.file(dataDir);
        Clock clock = Clock.system();

        // Initialize config signing (Ed25519) - must be created before ConfigStateMachine.
        //
        // Persist the keypair across restarts instead of generating a fresh ephemeral key each boot.
        // Operators can supply --signing-key-file; if omitted, the key is kept under the data
        // directory as "signing-key.bin" so restarts keep the chain valid.
        ConfigSigner configSigner;
        // The at-rest integrity codec for the Raft durability artifacts (snapshot blob, WAL records,
        // raft.persistent_state). KEYED with K_integrity derived from the cluster signing key -
        // fail-closed: a tampered artifact is refused on recovery. Built from keyStore below.
        io.configd.common.IntegrityEnvelope raftIntegrity;
        // The audit-log chain MAC key K_audit, derived from the SAME cluster signing key as the Raft
        // at-rest key but DOMAIN-SEPARATED by a distinct HKDF info string so the two derived keys are
        // independent.
        javax.crypto.SecretKey auditLogKey;
        try {
            Path keyFile = config.signingKeyFile() != null
                    ? config.signingKeyFile()
                    : dataDir.resolve("signing-key.bin");
            SigningKeyStore keyStore = SigningKeyStore.loadOrCreate(keyFile);
            configSigner = new ConfigSigner(keyStore.keyPair());
            raftIntegrity = deriveRaftIntegrityEnvelope(keyStore, keyFile, dataDir, cfg);
            auditLogKey = deriveAuditLogKey(keyStore);
        } catch (SecurityException se) {
            // Fail-closed: surface the co-location refusal with its clear, actionable
            // message - do NOT wrap it as a generic "failed to load key" error.
            throw se;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load or create Ed25519 signing key", e);
        }

        // Topology descriptor: the authenticated, versioned replacement for a plaintext
        // raft-shard-count.meta marker. Now that the Raft integrity envelope exists, read (or, on first
        // boot, write) topology-descriptor.dat under the SAME K_integrity. It enforces the fixed-at-deploy
        // N (a changed N is a loud, tamper-evident reshard rejection) AND yields the topology epoch that
        // StaticShardMap.epoch() returns and every edge resume token binds. At N=1 the epoch is the
        // deploy constant (TopologyDescriptor.INITIAL_EPOCH); the guard/epoch behave the same as a plain
        // marker file would, just tamper-evident.
        long topologyEpoch = enforceTopologyDescriptor(shardCount, dataDir, raftIntegrity);
        StaticShardMap shardMap = new StaticShardMap(shardCount, topologyEpoch);
        System.out.println("  Shard map    : " + shardMap + " [Multi-Raft Phase 1 C4a; N fixed at deploy,"
                + " ceiling " + MAX_SHARD_COUNT + "]");

        // The config store and state machine are per-group, built inside buildRaftGroup (one per
        // shard). At N=1 the single group 0 reuses the node-level `storage` instance below, so its
        // WAL/snapshot bytes are byte-identical. The singletons (fan-out/watch/read/write/http) bind
        // to the PRIMARY group's store/SM after the bring-up loop.

        // Turn the runtime invariant safety net ON. Build the metrics registry + InvariantMonitor HERE
        // (before the state machine and Raft node) so BOTH are fed a REAL checker instead of NOOP. The
        // monitor shares this registry, so violations surface at /metrics (the PrometheusExporter reads
        // the same registry). Prod is fail-open: a violation increments a named metric + SEVERE log and
        // keeps serving (never throw in a running server). The two InvariantChecker SAMs (RaftNode's and
        // ConfigStateMachine's) both bridge to this monitor.
        MetricsRegistry metricsRegistry = new MetricsRegistry();
        // JVM/process runtime gauges (heap, threads, FDs, GC) - the runtime dashboard and leak
        // alerts query these.
        io.configd.observability.JvmMetrics.bind(metricsRegistry);
        InvariantMonitor invariantMonitor = new InvariantMonitor(metricsRegistry, false);
        ConfigStateMachine.InvariantChecker smInvariantChecker = invariantMonitor::check;
        RaftNode.InvariantChecker raftInvariantChecker = invariantMonitor::check;

        // Build ConfigdMetrics HERE (before the state machine) so the SLO series are actually
        // RECORDED, not merely registered-at-zero.
        //   - the apply path feeds it via ServerStateMachineMetrics (apply_seconds + snapshot ctrs);
        //   - the raft pending-apply gauge reads `pendingApplyEntries`, an AtomicLong published on
        //     the tick thread (RaftLog.commitIndex/lastApplied are non-volatile plain longs touched
        //     only on the tick thread, so the scrape thread must read a published snapshot);
        //   - write_commit_* + the overload-reject counter are recorded at the raftProposer site;
        //   - raft_elections is incremented on the tick thread by positive currentTerm() deltas.
        java.util.concurrent.atomic.AtomicLong pendingApplyEntries =
                new java.util.concurrent.atomic.AtomicLong(0L);
        // The last-applied Raft index is likewise published from the tick thread (a plain long touched
        // only there) so the scrape sees a coherent value. Backs configd_raft_last_applied_index, which
        // the restore-conformance check reads to confirm a restored node replayed the log to the snapshot.
        java.util.concurrent.atomic.AtomicLong lastAppliedIndex =
                new java.util.concurrent.atomic.AtomicLong(0L);
        ConfigdMetrics configdMetrics =
                new ConfigdMetrics(metricsRegistry, pendingApplyEntries::get);
        configdMetrics.bindRaftLastAppliedGauge(lastAppliedIndex::get);

        // The per-group ConfigStateMachine is built in buildRaftGroup, fed THIS configdMetrics
        // via ServerStateMachineMetrics and the shared smInvariantChecker.

        // Initialize Raft with durable WAL storage. Pass the real scheduler tick period (TICK_PERIOD_MS)
        // so the documented millisecond budgets (150-300ms election timeout, 50ms heartbeat) are
        // converted to the correct tick counts and realized at runtime. Raft timing is
        // operator-tunable via system properties (defaults = the documented 150/300/50 ms). The
        // as-built ceiling is leadership-churn / heartbeat starvation under load, not fsync; a longer
        // election timeout and shorter heartbeat give more headroom for tick-thread scheduling jitter.
        int electionMinMs = cfg.getInt("configd.raft.electionTimeoutMinMs", 150);
        int electionMaxMs = cfg.getInt("configd.raft.electionTimeoutMaxMs", 300);
        int heartbeatMs = cfg.getInt("configd.raft.heartbeatIntervalMs", 50);
        int maxInflight = cfg.getInt("configd.raft.maxInflightAppends", 10);
        RaftConfig raftConfig = new RaftConfig(config.nodeId(), config.peers(),
                electionMinMs, electionMaxMs, heartbeatMs, 64, 256 * 1024, 1024, maxInflight,
                TICK_PERIOD_MS);
        System.out.println("  Raft timing  : election " + electionMinMs + "-" + electionMaxMs
                + "ms, heartbeat " + heartbeatMs + "ms, maxInflightAppends " + maxInflight);
        // The keyed integrity envelope authenticates the snapshot blob and WAL records of every group's
        // RaftLog (built per-shard in buildRaftGroup). The envelope is node-level (the at-rest key is
        // derived from the node signing key), shared across shards. `random` stays here for the
        // distribution overlay (HyParView); each RaftNode gets its OWN RandomGenerator in buildRaftGroup
        // so no two groups share an RNG instance (a cross-owner-thread data race at N>1) and election
        // timeouts stagger per shard.
        RandomGenerator random = RandomGeneratorFactory.getDefault().create(
                config.nodeId().id() * 31L + System.nanoTime());

        // Wire TLS. This must happen BEFORE TcpRaftTransport so Raft traffic uses mTLS when --tls-*
        // flags are supplied - constructing the transport first would leave Raft traffic in plaintext
        // even when TLS is enabled. The same TlsManager built here is shared with the Raft transport.
        final TlsManager tlsManager;
        SSLContext sslContext = null;
        if (config.tlsEnabled()) {
            try {
                TlsConfig tlsConfig = TlsConfig.mtls(
                        config.tlsCertPath(), config.tlsKeyPath(), config.tlsTrustStorePath());
                // Optional SEPARATE peer trust anchor for the Raft interior (etcd --peer-trusted-ca-file /
                // ZooKeeper ssl.quorum.trustStore). When set, the Raft transport trusts the peer CA instead
                // of the shared client/edge CA, so a client certificate that does not chain to the peer CA
                // cannot complete the peer handshake. Unset -> the shared trust store (byte-identical).
                String peerTrust = cfg.getString(PeerIdentityPolicy.TRUST_STORE_PROP).orElse("").trim();
                if (peerTrust.isEmpty()) {
                    tlsManager = new TlsManager(tlsConfig);
                } else {
                    char[] peerTrustPassword = cfg.getString(PeerIdentityPolicy.TRUST_STORE_PASSWORD_PROP)
                            .map(String::toCharArray).orElse(null);
                    tlsManager = new TlsManager(tlsConfig, Path.of(peerTrust), peerTrustPassword);
                    System.out.println("  Raft peer CA : separate peer trust store ("
                            + PeerIdentityPolicy.TRUST_STORE_PROP + ")");
                }
                sslContext = tlsManager.currentContext();
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize TLS", e);
            }
        } else {
            tlsManager = null;
        }

        // Wire real TCP transport when peer addresses are configured. The transport is NODE-LEVEL (one
        // bind/port for the whole node); inbound frames are demultiplexed to their group by groupId,
        // and each group gets its OWN outbound RaftTransportAdapter stamping its gid. For single-node /
        // test scenarios (no peers) each group's transport falls back to a no-op, handled in
        // buildRaftGroup.
        RaftTransportEndpoint tcpTransport = null;

        Map<NodeId, InetSocketAddress> peerAddresses = config.peerAddresses();
        if (peerAddresses != null && !peerAddresses.isEmpty()) {
            InetSocketAddress bindAddr = new InetSocketAddress(config.bindAddress(), config.bindPort());
            // Production consensus transport is Netty. The byte-identical RaftWireProtocol wire + the
            // RaftTransportEndpoint interface make this a single-line swap from `new TcpRaftTransport(...)`;
            // the JDK TcpRaftTransport remains as a tested fallback if reverting to it is ever needed.
            //
            // Peer authentication: when TLS is enabled this is mTLS with client-auth
            // (NettyRaftTransport.newServerSslHandler sets needClientAuth=true; the client handler sets
            // EndpointIdentificationAlgorithm=HTTPS). So a frame's attacker-influenceable groupId is only
            // ever demultiplexed for an AUTHENTICATED peer - an unauthenticated/untrusted-cert peer cannot
            // complete the handshake, so its frames never reach the demux (proven by negative test).
            // Peer-identity binding: when an allow-list is configured
            // (configd.raft.peerIdentity.allowedNodes), the transport verifies each accepted peer's
            // TLS cert identity and binds its senderId; unset keeps CA-chain-only with a one-time
            // warning. The same policy gates the in-body leaderId/candidateId check in the per-group
            // RaftTransportAdapter (via tcpTransport.peerIdentityEnforced()), and both share the
            // ServerRaftTransportMetrics sink so all rejections increment configd_raft_peer_identity_mismatch.
            PeerIdentityPolicy peerIdentityPolicy = PeerIdentityPolicy.fromConfig(cfg);
            // Node-join gate: an authenticated cluster with TLS on the Raft interior MUST enumerate its
            // peers. Without an allow-list, any client cert the CA trusts could forge a peer's senderId
            // and join consensus, so refuse to boot. Auth-disabled or plaintext-interior deployments keep
            // the loud-warning open gate instead (this returns without throwing).
            peerIdentityPolicy.requireEnforcedUnderAuth(isAuthEnabled(cfg, config), config.tlsEnabled());
            // Shared-CA assumption note: with peer-identity enforced under auth + TLS but NO separate peer
            // trust store, the Raft interior trusts the SAME CA as the client/edge plane. Peer
            // authorization then rests entirely on the allow-list marker AND on that CA never issuing a
            // node-marker (or an allow-listed identity) to a client cert - a single-CA operator invariant.
            // A separate peer trust store removes the assumption (a client cert cannot chain to the peer CA
            // at all). Recommend it for a hardened deployment.
            boolean separatePeerTrust =
                    !cfg.getString(PeerIdentityPolicy.TRUST_STORE_PROP).orElse("").trim().isEmpty();
            if (peerIdentityPolicy.enforced() && isAuthEnabled(cfg, config) && config.tlsEnabled()
                    && !separatePeerTrust) {
                System.out.println("  Raft peer CA : SHARED with the client/edge CA (no "
                        + PeerIdentityPolicy.TRUST_STORE_PROP + "); peer authorization then relies on the CA "
                        + "never issuing an allow-listed node identity to a client cert. Recommend a separate "
                        + "peer trust store for a hardened deployment.");
            }
            RaftTransportMetrics raftTransportMetrics = new ServerRaftTransportMetrics(configdMetrics);
            tcpTransport = new NettyRaftTransport(
                    config.nodeId(), bindAddr, peerAddresses, tlsManager, null,
                    peerIdentityPolicy, raftTransportMetrics);
            // Fail-closed: refuse to start if the operator asked for TLS but the transport did not
            // receive a TlsManager. This catches accidental regressions of the wiring.
            if (config.tlsEnabled() && tcpTransport.tlsManager() == null) {
                throw new IllegalStateException(
                        "TLS is enabled on the CLI but the Netty Raft transport has no TlsManager — "
                                + "refusing to start to avoid plaintext Raft traffic");
            }
        }

        // Initialize multi-raft driver (groups are registered by the bring-up loop below).
        MultiRaftDriver driver = new MultiRaftDriver(config.nodeId(), clock);

        // Create the owner-executor pool HERE - before wiring the transport - so the inbound Raft
        // handler can marshal onto the GROUP'S OWNER (`driver.ownerExecutor(gid)`), not a global alias.
        //   - ownerPool (N owners, default N=1 via `configd.raft.ownerPoolSize`): each group binds to
        //     `ownerExecutor(gid) = pool[gid % N]`; ALL of that group's OWNER-ONLY RaftNode work -
        //     per-owner tick, inbound handleMessage(), propose(), readIndex/flush - runs on its owner
        //     thread, so the unsynchronised RaftNode is only ever touched by one thread PER GROUP.
        //     `bindOwnerThread()` (below, first task on the owner) activates the assertOwnerThread()
        //     net in production: a missed hop trips `raft_owner_thread`.
        //   - readDispatchExecutor: HTTP read handler marshalling (double-hop onto the owner)
        //   - tlsReloadExecutor: slow cert I/O
        //
        // CRITICAL invariant: ALL RaftNode access for a group - ticks, inbound messages, proposals,
        // and ReadIndexState reads - happens ONLY on that group's owner thread. readDispatchExecutor
        // and the inbound/propose handlers never touch the node directly; they marshal via
        // `driver.ownerExecutor(gid).execute(...)`. At N=1 a single owner thread does all of it.
        OwnerExecutorPool ownerPool =
                new OwnerExecutorPool(cfg.getInt("configd.raft.ownerPoolSize", 1));
        bootTeardown.push(ownerPool::shutdown);
        driver.setOwnerPool(ownerPool);
        System.out.println("  Owner pool   : " + ownerPool.size()
                + " owner thread(s) [Phase 0 B Stage 1B — R-01 deleted, consensus via ownerExecutor(gid)]");

        // Coalesced heartbeats. Each owner's per-tick drain sends one message per peer carrying every
        // group's heartbeat, instead of one per group per peer. At N=1 (production) every drain has
        // exactly ONE group, so each heartbeat goes out as a normal AppendEntries frame and the wire is
        // byte-for-byte unchanged; coalescing only collapses sends at N>1. Enabled only on the real
        // transport - inert in single-node/test mode (no peers = nothing to coalesce).
        if (tcpTransport != null) {
            final RaftTransportEndpoint tcp = tcpTransport;
            driver.enableHeartbeatCoalescing((peer, groupHeartbeats) -> {
                try {
                    tcp.send(peer, frameHeartbeatDrain(groupHeartbeats));
                } catch (RuntimeException ignored) {
                    // codec reject / transport drop - fire-and-forget, Raft retransmits (mirrors send path)
                }
            });
            // Each group's CoalescingRaftTransport is bound to its owner's coalescer in the bring-up
            // loop below - resolving the CURRENT owner's coalescer per record, rehoming-aware;
            // at N=1 this is always owner 0.
        }
        ScheduledExecutorService readDispatchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "configd-read-dispatch");
            t.setDaemon(true);
            return t;
        });
        bootTeardown.push(readDispatchExecutor::shutdownNow);
        ScheduledExecutorService tlsReloadExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "configd-tls-reload");
            t.setDaemon(true);
            return t;
        });
        bootTeardown.push(tlsReloadExecutor::shutdownNow);
        // Off-ack-path node-anchor refresh (audit head + shard-liveness digest). Its own single thread
        // so a slow/failed refresh never delays an owner tick or a read.
        ScheduledExecutorService nodeAnchorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "configd-node-anchor");
            t.setDaemon(true);
            return t;
        });
        bootTeardown.push(nodeAnchorExecutor::shutdownNow);

        // Group commit (per group). Each group's coalescing durability flush dispatches onto THAT group's
        // owner executor (all of a group's RaftNode mutation stays on its one owner thread). Entries
        // proposed concurrently are appended no-sync (RaftNode.propose -> RaftLog.appendNoSync) and
        // force-synced together by one flush task - amortizing the per-op force(true) that would otherwise
        // serialize the consensus thread (heartbeat starvation -> election churn). Tunables (system
        // properties) are read once and applied to every group (the setGroupCommit call itself is in
        // buildRaftGroup):
        //   -Dconfigd.groupCommit.enabled=false -> keep synchronous per-op fsync (the baseline)
        //   -Dconfigd.groupCommit.maxBatch=N     -> cap entries per fsync (default 4096; bounds latency)
        //   -Dconfigd.groupCommit.lingerMicros=T -> linger to grow the batch (default 0 = flush ASAP)
        // Lenient parse (default "true", any non-"true" is false); the strict cfg.getBoolean is
        // deliberately not used here.
        boolean groupCommitEnabled = Boolean.parseBoolean(
                cfg.getString("configd.groupCommit.enabled").orElse("true"));
        int groupCommitMaxBatch = cfg.getInt("configd.groupCommit.maxBatch", 4096);
        long groupCommitLingerMicros = cfg.getLong("configd.groupCommit.lingerMicros", 0L);
        if (groupCommitEnabled) {
            System.out.println("  Group commit : ENABLED (maxBatch=" + groupCommitMaxBatch
                    + ", lingerMicros=" + groupCommitLingerMicros + ")");
        } else {
            System.out.println("  Group commit : DISABLED (synchronous per-op fsync — PART 1 baseline)");
        }

        // N-group consensus bring-up loop. Build one RaftGroupRuntime per shard via the single
        // buildRaftGroup path (no duplication of the intricate storage/log/store/SM/node/transport/
        // group-commit wiring), register it on the driver, bind its owner thread, and bind its coalescer.
        // At N=1 (the production default) this runs exactly once for group 0 and is byte-identical to
        // the single-group bring-up.
        int[] gids = shardMap.shardIds().toArray(); // StaticShardMap: [0, N)
        // Thread-safety: a startup warning when N>1 groups would under-provision the owner pool (P < N) -
        // they then serialize on too few owner threads. Safe (per-group single-writer holds), but it
        // forfeits the sharding throughput gain. Loud, not silent.
        if (shardCount > 1 && ownerPool.size() < shardCount) {
            System.err.println("WARNING: ************************************************************");
            System.err.println("WARNING: shardCount=" + shardCount + " (N>1) with ownerPoolSize="
                    + ownerPool.size() + " (< N) — shards serialize on fewer owner threads than shards.");
            System.err.println("WARNING: Set configd.raft.ownerPoolSize>=" + shardCount
                    + " for the multi-shard throughput gain.");
            System.err.println("WARNING: ************************************************************");
        }
        List<RaftGroupRuntime> runtimes = new ArrayList<>(gids.length);
        // Node-anchor inputs, captured on THIS (boot) thread as each group is built - after the RaftLog's
        // per-shard anchor recovery, before the owner thread is bound. Reading lastDurableIndex here is
        // race-free (single-threaded, pre-bind); doing it later would race the owner. bootDurableIndex is
        // the shard-liveness digest input; freshShards are the gids whose raft-anchor was ABSENT (booted
        // FRESH) - the wipe signature the node-anchor cross-check keys on.
        Map<Integer, Long> bootDurableIndex = new java.util.HashMap<>(gids.length * 2);
        Set<Integer> freshShards = new java.util.HashSet<>();
        // Partial-bring-up cleanup: if a group's bring-up throws for gid=k>0, groups 0..k-1 are already
        // registered + owner-bound. Release them on failure (remove from the driver + shut the owner
        // pool) so a failed boot does not leak driver registrations / owner-bound nodes, then rethrow
        // the original cause. At N=1 the prior-group cleanup loop is a no-op (one group), though the
        // catch itself still runs on a failed single-group boot (harmless - it shuts the pool + rethrows
        // the same cause). Catches Throwable so an Error mid-boot also cleans up.
        try {
            for (int gid : gids) {
                RaftGroupRuntime rt = buildRaftGroup(
                        gid, shardCount, dataDir, storage, raftIntegrity, clock, configSigner,
                        smInvariantChecker, raftInvariantChecker, configdMetrics, raftConfig, config.nodeId(),
                        tcpTransport, groupCommitEnabled, groupCommitMaxBatch, groupCommitLingerMicros, driver);
                driver.addGroup(gid, rt.raftNode());
                // Track the runtime the instant it is registered on the driver - BEFORE the binds below - so
                // a throw from bindCoalescer/execute (register-but-fail-to-bind) is still cleaned up.
                runtimes.add(rt);
                // Capture the node-anchor inputs now (pre-bind, race-free): this shard's recovered durable
                // head and whether its raft-anchor was absent (FRESH). Used by enforceNodeAnchor below.
                bootDurableIndex.put(gid, rt.raftLog().lastDurableIndex());
                if (!rt.raftLog().anchorExistedAtOpen()) {
                    freshShards.add(gid);
                }
                // Bind this group's CoalescingRaftTransport to its CURRENT owner's coalescer
                // (rehoming-aware; resolved per record). DORMANT for outbound at N=1 (owner 0). Only when
                // a real TCP transport exists (peer mode) does the group carry a coalescing decorator.
                if (rt.coalescingTransport() != null) {
                    rt.coalescingTransport().bindCoalescer(
                            () -> driver.heartbeatCoalescer(driver.currentOwnerIndex(gid)));
                }
                // BIND THE OWNER. Submit bindOwnerThread() as the FIRST task on this group's owner
                // executor, BEFORE the inbound demux is published (tcpTransport.start below) and BEFORE
                // the per-owner tick loop is scheduled. Single-thread FIFO then orders any later
                // inbound-routing / propose / tick task AFTER the bind - even a frame arriving the
                // instant start() returns marshals BEHIND this already-submitted bind. NEVER bind in the
                // constructor (it runs on `main` and legitimately touches state during recovery). After
                // this task runs, assertOwnerThread() is ACTIVE for the group's RaftNode.
                // (The bind + the addGroup ConcurrentHashMap put give the happens-before edge that keeps
                // monitorView() non-null for the off-owner scrape.)
                driver.ownerExecutor(gid).execute(rt.raftNode()::bindOwnerThread);
            }
        } catch (Throwable bringUpFailed) {
            for (RaftGroupRuntime built : runtimes) {
                try {
                    driver.removeGroup(built.groupId());
                } catch (RuntimeException ignored) {
                    // best-effort cleanup - surface the ORIGINAL bring-up failure below
                }
            }
            ownerPool.shutdown();
            throw bringUpFailed;
        }

        // The PRIMARY group (DEFAULT_RAFT_GROUP = 0) is the home for the singletons not yet sharded -
        // fan-out/watch listeners, the read/write services, the HTTP API, health, audit, snapshot replay.
        // Rebinding these locals from the primary keeps every downstream wiring statement unchanged; at
        // N=1 the primary is the only group. Selected by IDENTITY (groupId == DEFAULT_RAFT_GROUP), not
        // list position, so the singletons can never split-brain onto a non-zero group even if a future
        // ShardMap returned an unordered shardIds().
        RaftGroupRuntime primaryGroup = null;
        for (RaftGroupRuntime rt : runtimes) {
            if (rt.groupId() == DEFAULT_RAFT_GROUP) {
                primaryGroup = rt;
                break;
            }
        }
        if (primaryGroup == null) {
            throw new IllegalStateException(
                    "primary Raft group " + DEFAULT_RAFT_GROUP + " was not built — shardIds() must include "
                            + DEFAULT_RAFT_GROUP);
        }
        ConfigStateMachine stateMachine = primaryGroup.stateMachine();
        VersionedConfigStore configStore = primaryGroup.configStore();
        // Bind the state-machine digest info gauge to the primary group now that its state machine is
        // resolved. The digest is over the primary group's snapshot payload - the state a restore
        // bootstraps and the restore-conformance check compares against.
        configdMetrics.bindStateMachineHashGauge(stateMachine::stateMachineHashHex);
        // gid -> RaftGroupRuntime for the sharded read path (per-shard configStore + scatter-gather
        // getPrefix). Built once, immutable thereafter, captured by the read closures. At N=1 it holds
        // the single primary entry.
        Map<Integer, RaftGroupRuntime> runtimesByGid = new java.util.HashMap<>();
        for (RaftGroupRuntime rt : runtimes) {
            runtimesByGid.put(rt.groupId(), rt);
        }

        // Per-shard health gauges (per-group leader/term/commit-index/apply-lag + the per-node leader
        // count), read from each group's monitorView() on scrape (off the hot path). At N=1 this is
        // exactly the group-0 series - purely additive; the existing global group-0 scrape is unchanged.
        registerPerShardMetrics(metricsRegistry, driver, runtimes);

        // Register the inbound DEMULTIPLEXER ONCE on the shared node-level transport. The handler
        // carries `configdMetrics` so a Throwable escaping driver.routeMessage (e.g. a disk fault during
        // applyCommitted -> apply on a follower) is surfaced as a counter + SEVERE log, not swallowed by
        // the executor. It routes each frame to ITS group's owner by frame.groupId() (not a captured
        // constant 0). Per-group adapters are OUTBOUND-only; registerInboundHandler delegates to the
        // shared transport.registerHandler (which REPLACES), so ONE registration via the primary's adapter
        // covers every group. Registered AFTER every group's owner bind and BEFORE start() publishes the
        // accept loop, so an inbound frame marshals behind the binds. At N=1 every frame is group 0.
        if (tcpTransport != null) {
            // Export the transport's outbound-drop / inbound-refuse saturation counters (counted inside
            // the transport, otherwise never surfaced at /metrics).
            registerTransportSaturationGauges(metricsRegistry, tcpTransport);
            primaryGroup.adapter().registerInboundHandler(raftDemuxInboundHandler(driver, configdMetrics));
            try {
                tcpTransport.start();
            } catch (Exception e) {
                throw new RuntimeException("Failed to start TCP Raft transport on "
                        + config.bindAddress() + ":" + config.bindPort(), e);
            }
            // The transport's non-daemon Netty event loops (and its bound port) are now live: a
            // fail-closed throw further down must close them or the JVM cannot exit.
            final RaftTransportEndpoint startedTransport = tcpTransport;
            bootTeardown.push(() -> {
                try {
                    startedTransport.close();
                } catch (Exception ignored) {
                    // best-effort teardown of a failed boot
                }
            });
        }

        // The fan-out buffer is the bounded hot-path cache implementing CommitNotificationSource.
        // Drop-oldest overflow increments fanout_buffer_dropped_total so a lagging consumer's GAP is
        // observable; the log+snapshot (via SnapshotReplaySource) is the source of truth it replays from.
        MetricsRegistry.Counter fanOutDroppedCounter =
                metricsRegistry.counter("fanout.buffer.dropped");
        // PER-SHARD fan-out. Build one FanOutBuffer + Compactor per shard and register each group's
        // commit listener (ConfigDelta -> publish + addSnapshot) on ITS OWN state machine, so each
        // shard's committed stream feeds its own buffer ON ITS OWN OWNER THREAD - the FanOutBuffer
        // single-writer invariant holds per shard with NO lock, because apply -> notifyListeners runs
        // only on the group's owner thread. Per-shard sequences + cursor vector; NO fabricated
        // cross-shard global order. The shared dropped counter is LongAdder-backed (thread-safe) and
        // stays the aggregate fanout_buffer_dropped_total. At N=1 this builds exactly ONE buffer +
        // compactor for the primary group.
        ShardedFanOut shardedFanOut =
                registerShardedFanOut(runtimes, clock, fanOutDroppedCounter, FANOUT_BUFFER_CAPACITY);
        Map<Integer, FanOutBuffer> shardFanOutBuffers = shardedFanOut.buffers();
        Map<Integer, Compactor> shardCompactors = shardedFanOut.compactors();
        // The primary group's buffer + compactor are the home for the not-yet-sharded edge endpoint, the
        // ConfigdServer fields, and the fanOutBuffer()/compactor()/replaySource() accessors. At N=1 this
        // is the only group; at N>1 the per-shard sources serve the multi-shard-aware edge client and the
        // edge endpoint warns it serves the primary shard only.
        FanOutBuffer fanOutBuffer = shardFanOutBuffers.get(DEFAULT_RAFT_GROUP);
        Compactor compactor = shardCompactors.get(DEFAULT_RAFT_GROUP);
        WatchService watchService = new WatchService(clock);
        SubscriptionManager subscriptionManager = new SubscriptionManager();
        // Subscribed-prefix capacity gauge (sampled snapshot; benign-race size() read).
        configdMetrics.bindSubscriptionPrefixGauge(subscriptionManager::prefixCount);
        RolloutController rolloutController = new RolloutController(clock);
        PlumtreeNode plumtreeNode = new PlumtreeNode(config.nodeId(), 10_000, 100);
        HyParViewOverlay hyParViewOverlay = new HyParViewOverlay(
                config.nodeId(), 6, 30, 8, 4, random);

        // Wire HyParView active view changes -> Plumtree eager/lazy peer sets
        hyParViewOverlay.setViewChangeListener((peer, added) -> {
            if (added) {
                plumtreeNode.addEagerPeer(peer);
            } else {
                plumtreeNode.removePeer(peer);
            }
        });

        // The per-group fan-out commit listeners - ConfigDelta -> publish + addSnapshot, one per shard
        // on its own buffer/compactor - were registered above by registerShardedFanOut.

        // Register state machine listener: feed WatchService for push notifications. Bound to the PRIMARY
        // group ONLY. WatchService is single-threaded by contract (no synchronization) and uses a single
        // version cursor that collides across shards, and it has no production register() path (dormant
        // infrastructure). Binding it to the primary keeps onConfigChange on ONE owner thread (no race at
        // N>1) and is byte-identical at N=1. Cross-shard watch aggregation rides the multi-shard-aware
        // edge client.
        stateMachine.addListener(watchService::onConfigChange);

        // (metricsRegistry + invariantMonitor were created earlier, before the state machine,
        // so the runtime invariant net could be wired.)
        SloTracker sloTracker = new SloTracker();
        ProductionSloDefinitions.register(sloTracker);
        BurnRateAlertEvaluator burnRateAlertEvaluator = new BurnRateAlertEvaluator(sloTracker);
        PropagationLivenessMonitor propagationMonitor =
                new PropagationLivenessMonitor(1000, metricsRegistry);
        // `configdMetrics` is constructed earlier, before the inbound handler is registered,
        // so both the tick-loop and inbound-routing throwable handlers have a stable metrics handle.
        // Eager construction populates the SLO counter families for the first scrape.

        // Wire security. TLS is already initialized above, before the Raft transport.
        AuthInterceptor authInterceptor = null;
        AuthenticatorChain authChain = null;
        AclService aclService = null;
        AclConfigPolicyLoader aclPolicyLoader = null;

        // The pluggable authenticator chain (configd.auth.mode / configd.auth.providers). When configured it
        // is THE auth mechanism (basic / mtls, or a mixed chain) and supersedes the legacy static
        // --auth-token. When absent, the posture falls back to a static --auth-token, or auth off.
        java.util.List<String> authProviders = AuthenticatorChain.configuredProviders(cfg);
        boolean noAuthMode = authProviders.equals(java.util.List.of("none"));
        if (noAuthMode) {
            // Explicit auth-disabled mode. It MUST produce the same handler state as the legacy auth-off
            // branch (no chain, no interceptor, no ACL -> the open gate, which still refuses a
            // reserved-prefix `_acl/` WRITE). Routing no-auth THROUGH the handler chain would 401 a
            // credential-less request - denying exactly what this mode exists to allow - so the chain is
            // deliberately NOT wired. 'none' mixed with real providers is rejected at build time (fail-loud).
            System.err.println("WARNING: ************************************************************");
            System.err.println("WARNING: Authentication is DISABLED (configd.auth.mode=none).");
            System.err.println("WARNING: All write/delete/admin endpoints are unauthenticated.");
            System.err.println("WARNING: Front this deployment with a trusted reverse proxy.");
            System.err.println("WARNING: ************************************************************");
        } else if (!authProviders.isEmpty()) {
            // Fail-loud on an unknown provider / a missing optional module / 'none' mixed with others.
            authChain = AuthenticatorChain.build(authProviders, cfg);
            // Authorization stays in-core: the chain yields a Principal; the AclService (seeded from the
            // replicated `_acl/` policy) decides access on it. No static break-glass root grant here - the
            // SPI modes are policy-governed (that grant is a property of the legacy --auth-token path).
            aclService = new AclService();
            System.out.println("  Auth (SPI)   : providers=" + authChain.providerTypes());
        } else if (config.authEnabled()) {
            String expectedToken = config.authToken();
            authInterceptor = new AuthInterceptor(token -> {
                // Constant-time comparison to prevent a timing side-channel attack on the auth token.
                if (java.security.MessageDigest.isEqual(
                        expectedToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        token.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                    // Root asserts NO roles - its break-glass authority is purely the static principal
                    // grant below, decoupled from any config-loadable role so no `_acl/` role can carve
                    // it via assertion.
                    return new AuthInterceptor.AuthResult.Authenticated(ROOT_PRINCIPAL, Set.of());
                }
                return new AuthInterceptor.AuthResult.Denied("invalid token");
            });
            aclService = new AclService();
            aclService.grant("", ROOT_PRINCIPAL, EnumSet.allOf(AclService.Permission.class));
        } else {
            System.err.println("WARNING: ************************************************************");
            System.err.println("WARNING: Authentication is DISABLED (--auth-token not set).");
            System.err.println("WARNING: All write/delete/admin endpoints are unauthenticated.");
            System.err.println("WARNING: DO NOT run in production without --auth-token.");
            System.err.println("WARNING: ************************************************************");
        }

        if (aclService != null) {
            // Config-sourced policy under `_acl/`. ADDITIVE on top of any static grant above (no `_acl/`
            // keys in production = empty snapshot = byte-identical). Registered BEFORE the tick loop so it
            // observes every `_acl/`-touching apply; the snapshot-install hook covers follower catch-up;
            // the boot seed catches a snapshot-restored prefix. Fail-closed-to-last-good on malformed
            // policy; the reserved role/principal names neutralize the carve footgun.
            if (shardCount == 1) {
                // N=1: single-store loader on the primary state machine - byte-identical to the prior wiring.
                aclPolicyLoader = new AclConfigPolicyLoader(
                        aclService, stateMachine.store(),
                        AclConfigPolicyLoader.RESERVED_ROLES, AclConfigPolicyLoader.RESERVED_PRINCIPALS,
                        metricsRegistry);
                stateMachine.addListener(aclPolicyLoader::onConfigChange);
                stateMachine.addSnapshotListener(aclPolicyLoader::onSnapshotInstalled);
            } else {
                // N>1: `_acl/` keys hash-scatter across all groups, so the loader must scatter-gather every
                // group's store and observe every group's applies. Otherwise a role/binding/DENY on a
                // non-primary shard is silently absent from the policy snapshot (an under-deny bypass) and
                // its apply never advances configPolicyVersion() (so bounded watch revocation misses it).
                List<VersionedConfigStore> perShardStores = new ArrayList<>(runtimes.size());
                for (RaftGroupRuntime rt : runtimes) {
                    perShardStores.add(rt.configStore());
                }
                aclPolicyLoader = new AclConfigPolicyLoader(
                        aclService, perShardStores,
                        AclConfigPolicyLoader.RESERVED_ROLES, AclConfigPolicyLoader.RESERVED_PRINCIPALS,
                        metricsRegistry);
                for (RaftGroupRuntime rt : runtimes) {
                    rt.stateMachine().addListener(aclPolicyLoader::onConfigChange);
                    rt.stateMachine().addSnapshotListener(aclPolicyLoader::onSnapshotInstalled);
                }
            }
            aclPolicyLoader.bootSeed(); // boot seed (N=1 inline; N>1 serialized through the worker, awaited)
        }

        HealthService healthService = new HealthService();
        // Drain flag: shutdown() flips this to true BEFORE closing anything, so the readiness check
        // reports 503 and an LB/orchestrator stops routing while in-flight work drains. Created here and
        // handed to the ConfigdServer instance below, so the readiness lambda and shutdown() share the
        // one AtomicBoolean. /health/live is unaffected (liveness is not readiness).
        java.util.concurrent.atomic.AtomicBoolean draining =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        // Shard-aware readiness: this node hosts EVERY group in `runtimes` (static-N - each node runs
        // all N shards). It is ready only when it is not draining AND every hosted group has a known leader
        // (quorum exists). A group-0-blind check LIES at N>1: a node that lost quorum on shards 1..N-1 would
        // still report READY. The per-group leader is read off `monitorView().leaderId()` - the same
        // never-torn, <= one-tick-stale off-owner snapshot registerPerShardMetrics reads - so the health
        // thread never touches RaftNode internals. At N=1 `runtimes` holds only group 0, so this is
        // semantically the same single-group "leader elected?" check under the same "raft-leader" name.
        int[] hostedGroups = new int[runtimes.size()];
        for (int i = 0; i < runtimes.size(); i++) {
            hostedGroups[i] = runtimes.get(i).groupId();
        }
        healthService.registerReadinessCheck(() -> evaluateReadiness(
                draining.get(), hostedGroups,
                gid -> {
                    RaftNode node = driver.getGroup(gid);
                    return node != null ? node.monitorView().leaderId() : null;
                }));

        // Marshal proposals onto the GROUP'S OWNER (driver.ownerExecutor(gid)) so node.propose()
        // (log/term/commitIndex mutation + applyCommitted -> stateMachine.apply) never races the
        // per-owner tick or the inbound handler. The SAME marshalled task also registers the
        // commit-outcome callback, capturing (index,term) INSIDE the task (the synchronous result never
        // crosses the marshalling boundary); the HTTP write thread blocks on one end-to-end
        // WRITE_COMMIT_TIMEOUT_MS deadline and gets a commit-confirmed answer
        // (Committed/Lost/NotLeader/Indeterminate/Overloaded).
        // The production shard-routing proposer routes each write to shardFor(scope,key)'s group (and the
        // cross-shard guard rejects a multi-key write spanning shards). At N=1 every key resolves to
        // group 0.
        ConfigWriteService.RaftProposer proposer =
                raftProposer(driver, shardMap, WRITE_COMMIT_TIMEOUT_MS, configdMetrics);
        // Default write rate limit = 10_000/s globally. A startup line prints the effective rate
        // so operators can audit at boot.
        final int writeRatePerSec = 10_000;
        final int writeBurst = 10_000;
        RateLimiter rateLimiter = new RateLimiter(clock, writeRatePerSec, writeBurst);
        System.out.println("  Write rate   : " + writeRatePerSec + "/s (burst " + writeBurst + ")");
        // Echo the effective write-admission cap at boot so operators can audit it. Read from the same
        // source buildProposer uses (system properties), so the printed value matches the enforced one.
        int admissionCap = ConfigSource.system()
                .getInt("configd.write.maxInflightProposals", DEFAULT_MAX_INFLIGHT_PROPOSALS);
        System.out.println("  Write admit  : " + (admissionCap > 0
                ? "max " + admissionCap + " proposals in-flight (excess shed as 429 Overloaded)"
                : "DISABLED (configd.write.maxInflightProposals=0)"));
        // Per-principal rate limiting: each authenticated principal gets its OWN token bucket (same
        // params as the global), so one noisy/hostile tenant cannot consume the whole write budget and
        // starve others. The global rateLimiter remains the fallback for unauthenticated / overflow
        // requests. Gate stays before the Raft proposal.
        // The leader hint is SHARD-AWARE - a NotLeader/Lost redirect points at the leader of the shard
        // that OWNS (scope,key), so a client retries the right shard's leader. At N=1 shardFor ->
        // group 0 -> raftNode.leaderId().
        ConfigWriteService writeService = new ConfigWriteService(proposer, null, rateLimiter,
                (scope, key) -> {
                    io.configd.raft.RaftNode owner = driver.getGroup(shardMap.shardFor(scope, key));
                    return owner != null ? owner.leaderId() : null;
                },
                () -> new RateLimiter(clock, writeRatePerSec, writeBurst));

        // (Tick / read-dispatch / TLS-reload executors are created earlier, right after the multi-raft
        // driver, so the inbound Raft handler can marshal onto the tick executor.)

        // Config read service with linearizable read support. The ReadIndex protocol requires:
        //   1. Record commit index (readIndex())
        //   2. Confirm leadership via heartbeat quorum
        //   3. Wait until lastApplied >= readIndex
        //   4. THEN serve the read
        // Skipping straight to step 4 without waiting on 2-3 would serve a stale read.
        //
        // readIndex() and isReadReady() access ReadIndexState (a non-thread-safe LinkedHashMap), so these
        // must be dispatched to the tick thread, never called directly from an HTTP handler thread.
        //
        // Reads route to the shard that OWNS (scope, key) using the per-request scope the GET handler
        // parses, so a read resolves the SAME shard the write of (scope, key) used (read-your-writes;
        // single-key linearizability preserved). getPrefix scatter-gathers across all shards (prefix keys
        // may hash to different shards). At N=1 every resolution is group 0 -> the single primary store.
        // readScope below is the GLOBAL default for the legacy key-only ConfigReader path; the
        // scope-aware reads use the caller's scope.
        final ConfigScope readScope = ConfigScope.GLOBAL;
        // Pass IMMUTABLE copies: the reader is read concurrently by HTTP threads (off the build thread),
        // so a frozen map/list makes the read-only-after-publication contract self-evident.
        ConfigReadService.ConfigReader configReader =
                shardedConfigReader(shardMap, Map.copyOf(runtimesByGid), List.copyOf(runtimes), readScope);
        // Linearizable-read leadership is confirmed on the shard that OWNS (scope, key) (the ReadIndex
        // protocol runs on that shard's node via its owner), using the GET handler's per-request scope.
        // At N=1 this is group 0.
        ConfigReadService readService = new ConfigReadService(configReader, (scope, key) -> {
            int readGid = shardMap.shardFor(scope, key);
            io.configd.raft.RaftNode readNode = driver.getGroup(readGid);
            if (readNode == null) {
                return false; // unknown shard - fail closed (treat as not-leader)
            }
            ScheduledExecutorService readOwner = driver.ownerExecutor(readGid);
            // Single-future completion-driven pattern. Allocates 1 CompletableFuture per linearizable
            // read. Dispatch goes through readDispatchExecutor which marshals the owner-thread work - the
            // HTTP thread never touches ReadIndexState directly.
            //
            // The read keeps its double-hop; the inner hop targets the GROUP'S OWNER
            // (driver.ownerExecutor(gid)). The owner calls whenReadReady(readId, cb), which fires the
            // callback as soon as the read transitions to ready or the node steps down. On timeout, we
            // clean up via the owner.
            java.util.concurrent.CompletableFuture<Boolean> resultFuture =
                    new java.util.concurrent.CompletableFuture<>();
            // Shared slot so the timeout path can tell the owner which readId to clean up. Written
            // only from the owner thread; read only after the timeout expires (guarded by a volatile
            // via the AtomicLong memory-model semantics).
            java.util.concurrent.atomic.AtomicLong readIdRef =
                    new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
            readDispatchExecutor.execute(() -> readOwner.execute(() -> {
                try {
                    long readId = readNode.readIndex();
                    if (readId < 0) {
                        resultFuture.complete(false); // Not leader
                        return;
                    }
                    readIdRef.set(readId);
                    // whenReadReady fires synchronously if already ready,
                    // otherwise registers a one-shot callback fired from
                    // the owner thread after confirmPendingReads / apply.
                    readNode.whenReadReady(readId, () -> {
                        boolean ready = readNode.isReadReady(readId);
                        readNode.completeRead(readId);
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
                // Abandon the read; dispatch cleanup to the group's owner so ReadIndexState mutation
                // stays single-owner-threaded.
                long readId = readIdRef.get();
                if (readId != Long.MIN_VALUE) {
                    final long finalReadId = readId;
                    readOwner.execute(() -> readNode.completeRead(finalReadId));
                }
                return false;
            }
        });

        // Pass ConfigdMetrics.histogramSchedules() so the SLO histograms render `_bucket{le=...}` lines
        // (write_commit/apply/propagation) - the exact series the burn-rate alerts query. Without the
        // schedules the exporter emits quantile lines instead and the alert bucket series are empty.
        io.configd.observability.PrometheusExporter prometheusExporter =
                new io.configd.observability.PrometheusExporter(
                        metricsRegistry, ConfigdMetrics.histogramSchedules());
        // Strong-read key class is config-driven via --strong-read-prefixes (default "secure/"); those
        // keys are served fail-closed linearizable, with raftNode.leaderId() as the X-Leader-Hint source
        // for retries.
        StrongReadPolicy strongReadPolicy = new StrongReadPolicy(config.strongReadPrefixes());
        System.out.println("  Strong reads : " + strongReadPolicy.prefixes()
                + " (fail-closed linearizable, ADR-0030 INV-1)");

        // Tamper-evident security audit log. Enabled whenever auth is on (the audit trail only has
        // subjects to record once there are principals). KEYED HMAC-SHA256 chain under K_audit (derived
        // above), so a file-rewriting attacker cannot forge a consistent chain. Backed by the durable,
        // append+CRC Storage; bounded to AuditLog.DEFAULT_MAX_RECORDS.
        AuditLog auditLog = (authInterceptor != null || authChain != null)
                ? new AuditLog(storage, clock, auditLogKey) : null;
        if (auditLog != null) {
            System.out.println("  Audit log    : security-audit (KEYED HMAC-SHA256 chain, append-only, cap "
                    + AuditLog.DEFAULT_MAX_RECORDS + ")");
        }

        // Realize the AnchorWitness SPI over the per-group RaftNodes (peer-quorum provider). Held for the
        // SPI seam and a possible future external-store composition; the per-node tick/vote machinery
        // drives the actual rollback-detection logic. Node scope has no vote (freshness-only).
        AnchorWitness anchorWitness = new PeerQuorumAnchorWitness(driver::getGroup);
        if (tcpTransport != null) {
            System.out.println("  Anchor witness: peer-quorum armed (strict-boot + "
                    + (witnessStrictEnabled() ? "strict-vote [-Dconfigd.raft.witnessStrict=true]" : "fast-vote [default]")
                    + ") — Gate 3c R-a' closer");
        }
        // Upgrade each armed node's rollback handler to ALSO write an audit record before halting (the
        // in-buildRaftGroup handler only logs + halts, since the audit log did not exist yet). Runs before
        // the tick loop starts, so no gate can fire on the old handler. Only armed (peer-mode) nodes have a
        // gate that can fire; when auth is off there is no audit log, so the log+halt handler stays.
        if (tcpTransport != null && auditLog != null) {
            AuditLog auditLogRef = auditLog;
            for (RaftGroupRuntime rt : runtimes) {
                rt.raftNode().setAnchorRollbackHandler((g, bootSeq, witnessedSeq, reportingPeer) -> {
                    try {
                        auditLogRef.record("-", "anchor.rollback.detected", "raft-group-" + g,
                                "bootAnchorSeq=" + bootSeq + " witnessedSeq=" + witnessedSeq
                                        + " reportingPeer=" + reportingPeer);
                    } catch (Throwable auditFailed) {
                        LOG.log(Level.SEVERE, "failed to write anchor.rollback.detected audit record for"
                                + " raft group " + g + " (halting regardless)", auditFailed);
                    }
                    LOG.log(Level.SEVERE, "anchor rollback detected for raft group " + g
                            + ": booted from anchorSeq=" + bootSeq + " but peer " + reportingPeer
                            + " witnessed anchorSeq=" + witnessedSeq + " (> booted) - refusing to start this"
                            + " shard (R-a' fail-closed: a within-term vote rollback could double-vote and"
                            + " split-brain)");
                    Runtime.getRuntime().halt(71);
                });
            }
        }

        // Node anchor: open (first boot mints, later boots cross-check) the node-level node-anchor that
        // binds the topology (epoch/N), the security-audit chain head, and the per-shard shard-liveness
        // digest. A topology rollback, an audit chain truncated below the anchored head, or a wiped shard
        // reset to index 0 each REFUSES to start, fail-closed. Off the ack path; the cross-check runs
        // before serving traffic (below). All per-shard durable heads and FRESH signals were captured
        // pre-bind in the bring-up loop.
        NodeAnchorFile nodeAnchor = NodeAnchorService.enforceNodeAnchor(
                dataDir, raftIntegrity, topologyEpoch, shardCount, bootDurableIndex, freshShards, auditLog);
        // Replay protection. OPT-IN (default OFF for back-compat); enabled via
        // -Dconfigd.replay.enabled=true so no new CLI/ServerConfig surface is added. Defends only
        // against PASSIVE capture-and-replay; a token holder can still mint fresh requests.
        ReplayGuard replayGuard = null;
        if (cfg.getBoolean("configd.replay.enabled", false)) {
            replayGuard = new ReplayGuard(clock);
            System.out.println("  Replay guard : ON (window " + ReplayGuard.DEFAULT_WINDOW_MS
                    + "ms, nonce cap " + ReplayGuard.DEFAULT_MAX_NONCES + ")");
        }

        // The admin / control-plane HTTP API runs on Netty (NettyHttpApiServer), delegating to the same
        // AdminApiHandler the JDK HttpApiServer used. Documented fast-revert: change
        // `new NettyHttpApiServer(` back to `new HttpApiServer(` (identical arg list) - the JDK adapter
        // is retained and CI-green as the revert target.
        // The ADMIN-gated leadership-transfer control endpoint. Backed by the driver: it posts the
        // owner-thread-confined RaftNode.transferLeadership onto the group's owner executor and drives it
        // through the built AdminService guard. Gives operators a remedy for post-failover leadership drift
        // (otherwise the sharded aggregate collapses toward the single-group plateau with no lever).
        DriverLeadershipAdmin leadershipAdmin = new DriverLeadershipAdmin(driver);

        // Decentralized leadership auto-balance loop (one per node). It sheds - never pulls - at most one
        // led group per cadence to an under-loaded peer, so post-failover leader drift no longer collapses
        // the sharded aggregate toward the single-group plateau. It is created ONLY in the horizontal-scale
        // regime it targets - more than one shard AND at least one peer - and stays off when the operator
        // flips the kill switch. At N=1 or single-node the distribution is trivially flat (spread 0), so
        // building the loop would add a daemon thread that could never act; not building it keeps those
        // deployments byte-identical. Transfers go through the same owner-thread-confined
        // DriverLeadershipAdmin path the admin endpoint uses.
        LeaderBalanceConfig balanceConfig = LeaderBalanceConfig.fromConfig(cfg);
        LeaderBalanceLoop leaderBalanceLoop = null;
        if (balanceConfig.enabled() && shardCount > 1 && !config.peers().isEmpty()) {
            LeaderView balanceView = LeaderView.overDriver(driver, config.peers());
            LeaderBalanceLoop.LeadershipTransfer transferSeam = (gid, target) -> {
                try {
                    return leadershipAdmin.transferLeadership(gid, target)
                            instanceof AdminService.AdminResult.Success;
                } catch (RuntimeException wedgedOrTimedOut) {
                    // A wedged/overloaded owner surfaces as the admin path's bounded timeout (its 503
                    // contract). Treat it as a declined attempt so it folds into the loop's refused +
                    // cooldown path, never a retry storm.
                    return false;
                }
            };
            leaderBalanceLoop = new LeaderBalanceLoop(
                    balanceView, transferSeam, balanceConfig, clock,
                    new java.util.Random(), LeaderBalanceMetrics.forRegistry(metricsRegistry));
            System.out.println("  Leader balance: ON (interval " + balanceConfig.intervalMs() + "ms, threshold "
                    + balanceConfig.imbalanceThreshold() + ", cooldown " + balanceConfig.cooldownMs() + "ms"
                    + (balanceConfig.dryRun() ? ", DRY-RUN" : "") + ") [auto-balance leadership across boxes]");
        } else {
            System.out.println("  Leader balance: OFF ("
                    + (!balanceConfig.enabled() ? "kill switch"
                            : shardCount <= 1 ? "single shard" : "single node") + ")");
        }

        NettyHttpApiServer httpApiServer;
        try {
            // The read 503 X-Leader-Hint is SHARD- AND SCOPE-AWARE - resolved for the shard that owns
            // (scope, key) using the read's per-request scope, so a client retries the right shard's
            // leader (a scopeless hint would loop at N>1). At N=1 every (scope, key) resolves to
            // group 0 -> raftNode.leaderId().
            httpApiServer = new NettyHttpApiServer(
                    config.bindAddress(), config.apiPort(), sslContext, healthService, prometheusExporter,
                    configStore, writeService, readService, authInterceptor, aclService,
                    strongReadPolicy,
                    (scope, key) -> {
                        io.configd.raft.RaftNode owner = driver.getGroup(shardMap.shardFor(scope, key));
                        return owner != null ? owner.leaderId() : null;
                    },
                    auditLog, replayGuard, leadershipAdmin, authChain);
            httpApiServer.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start HTTP API server on port " + config.apiPort(), e);
        }
        bootTeardown.push(httpApiServer::stop);

        // Fan-out edge endpoint, optional (--edge-port). Drives the SAME FanOutSessionCore the
        // simulator drives, pulling via the readSince/ReplaySource seams ONLY - no work on the apply
        // path. Reuses the Raft TlsManager (REQUIRED mTLS when TLS is on; plaintext for
        // single-node/test, matching the Raft transport policy).
        io.configd.server.fanout.FanOutEndpoint fanOutServer = null;
        if (config.edgeEnabled()) {
            // The coordinator serves multi-shard WATCH across all N shards. A legacy whole-store SUBSCRIBE
            // is served from the primary shard only, so the driver refuses it per connection at N>1 unless
            // allowPartialShardView (wired into fanOutConfig below); a WATCH is never refused.
            io.configd.server.fanout.RegistryFanOutSessionMetrics fanOutMetrics =
                    new io.configd.server.fanout.RegistryFanOutSessionMetrics(metricsRegistry);
            // The per-shard sources + replay sources + shard set + resolver the multi-shard
            // fan-out/fan-in coordinator fans a watch across: one FanOutBuffer and one snapshot replay
            // per group (the same per-gid runtimes registerShardedFanOut / the ACL loader read), the
            // shard set, and a ShardMap-backed resolver (KEY -> shardFor; PREFIX/FULL -> shardIds()).
            // At N=1 these are single-entry maps and the single-shard resolver, so one core is the
            // single-shard drain (byte-identical); at N>1 the coordinator fans a WATCH across all N.
            Map<Integer, io.configd.distribution.CommitNotificationSource> edgeShardSources =
                    new java.util.LinkedHashMap<>(shardFanOutBuffers);
            Map<Integer, io.configd.distribution.ReplaySource> edgeShardReplaySources =
                    new java.util.LinkedHashMap<>();
            for (RaftGroupRuntime rt : runtimes) {
                edgeShardReplaySources.put(rt.groupId(),
                        new io.configd.distribution.SnapshotReplaySource(rt.configStore()::snapshot));
            }
            int[] edgeAllGids = shardMap.shardIds().toArray();
            io.configd.distribution.fanout.ShardResolver edgeShardResolver =
                    new io.configd.server.fanout.ShardMapResolver(shardMap);
            // The slow-consumer governor (per-cert-identity quarantine / unhealthy policy) - consulted
            // by the FanOutServer at SUBSCRIBE and fed by the per-session demotion/ack/queue signals.
            io.configd.distribution.fanout.SlowConsumerGovernor slowConsumerGovernor =
                    new io.configd.distribution.fanout.SlowConsumerGovernor(
                            io.configd.distribution.fanout.SlowConsumerPolicyConfig.defaults(),
                            fanOutMetrics);
            // The authorization gate. The adapter bridges the fan-out plane's WatchAuthorizer SPI to the
            // SAME in-core AclService the HTTP admin path uses (so the edge gate and the admin gate decide
            // identically). When auth is ON (aclService present) it gates BOTH edge surfaces: WATCH_CREATE
            // over its whole target and the legacy full-store SUBSCRIBE over whole-store READ - so the edge
            // hydration identity (the edge node's cert-DN) MUST hold READ over the root prefix or SUBSCRIBE
            // is refused NOT_AUTHORIZED. When auth is OFF (aclService == null) the authorizer is null: the
            // driver fails CLOSED for watches (every WATCH_CREATE -> NOT_AUTHORIZED) but admits SUBSCRIBE
            // (an unauthenticated deployment has no principal model to evaluate).
            io.configd.distribution.fanout.WatchAuthorizer watchAuthorizer =
                    (aclService != null)
                            ? new io.configd.server.fanout.AclServiceWatchAuthorizer(aclService)
                            : null;
            // Production edge fan-out is the Netty transport. Reverting to `new FanOutServer(...)` (the
            // JDK transport, fully tested by the contract and a drop-in FanOutEndpoint) is a single-line
            // swap.
            // Server-side prefix filtering posture: default ON for the co-located trusted deployment, so
            // a prefix-scoped edge that opts in gets its stream filtered; set OFF (full-chain) when a
            // separate/untrusted relay tier terminates the fan-out. The strong-read prefixes are always
            // shipped regardless of the edge's prefix set. When off (or for a full-store / non-opting
            // edge) the drain is byte-identical to the legacy path.
            // allowPartialShardView gates the legacy whole-store SUBSCRIBE plane at N>1 (primary-shard-
            // only); it never affects a multi-shard WATCH and is inert at N=1.
            boolean allowPartialShardView = cfg.getBoolean("configd.edge.allowPartialShardView", false);
            io.configd.distribution.fanout.FanOutConfig fanOutConfig =
                    io.configd.distribution.fanout.FanOutConfig.defaults()
                            .withServerSidePrefixFilter(resolveEdgeFilterPosture(cfg),
                                    strongReadPolicy.prefixes())
                            .withAllowPartialShardView(allowPartialShardView);
            // Edge token authentication: when the SHARED auth chain (one chain, both planes) contains a
            // bearer or basic provider, the edge admits token/basic AUTH frames additively - mTLS clients
            // stay byte-identical (no AUTH frame, cert-auth at the handshake), a certificate-less token
            // client presents an AUTH frame. When the chain is mTLS-only or absent, edgeAuth stays null
            // and the edge is byte-identical to the pre-token endpoint.
            io.configd.server.fanout.EdgeAuthConfig edgeAuth = null;
            if (authChain != null) {
                java.util.List<String> edgeProviderTypes = authChain.providerTypes();
                if (edgeProviderTypes.contains("bearer") || edgeProviderTypes.contains("basic")) {
                    int preAuthMaxFrameBytes = cfg.getInt("configd.edge.preAuthMaxFrameBytes", 16_384);
                    int maxAuthTokenBytes = cfg.getInt("configd.edge.maxAuthTokenBytes", 8_192);
                    // The static-token session lifetime (a bearer/basic credential carries no exp today):
                    // the connection closes at now + defaultTokenTtlMs on the server clock. A future OIDC
                    // exp would close at exp + leeway instead. A REFRESH_AUTH re-arms it.
                    long defaultTokenTtlMs = cfg.getLong("configd.edge.authTtlMs", 3_600_000L);
                    edgeAuth = new io.configd.server.fanout.EdgeAuthConfig(
                            authChain, preAuthMaxFrameBytes, maxAuthTokenBytes, defaultTokenTtlMs,
                            io.configd.common.auth.CredentialExpiryPolicy.fromConfig(cfg));
                }
            }
            // The edge client-cert validity gate (online revocation + mid-connection notAfter
            // enforcement). Defaults reproduce today: revocation OFF, enforceCertNotAfter false ->
            // EdgeCertGate.OFF is byte-identical to no gate at all. This gate is wired ONLY to the edge
            // plane; the Raft interior never constructs one, so the exemptInterNode invariant holds by
            // construction.
            io.configd.server.fanout.EdgeCertGate edgeCertGate =
                    buildEdgeCertGate(cfg, fanOutMetrics);
            fanOutServer = new io.configd.server.fanout.NettyFanOutServer(
                    edgeShardSources, edgeShardReplaySources, edgeAllGids, edgeShardResolver,
                    shardMap.epoch(),
                    new InetSocketAddress(config.bindAddress(), config.edgePort()),
                    tlsManager,
                    fanOutConfig,
                    io.configd.server.fanout.FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                    io.configd.server.fanout.FanOutServer.DEFAULT_MAX_SESSIONS,
                    slowConsumerGovernor, fanOutMetrics, clock, watchAuthorizer, edgeAuth, edgeCertGate);
            // Fail-closed: if TLS is enabled on the CLI but the edge endpoint did not receive a
            // TlsManager, refuse to start (no plaintext edge traffic in a TLS deployment).
            if (config.tlsEnabled() && tlsManager == null) {
                throw new IllegalStateException(
                        "TLS is enabled but FanOutServer has no TlsManager — refusing to start "
                                + "to avoid plaintext edge traffic");
            }
            final io.configd.server.fanout.FanOutEndpoint startedFanOut = fanOutServer;
            try {
                fanOutServer.start();
                bootTeardown.push(startedFanOut::close);
                System.out.println("  Edge port    : " + fanOutServer.localPort()
                        + (tlsManager != null ? " (mTLS)" : " (PLAINTEXT)"));
                if (shardCount > 1) {
                    System.out.println("  Edge plane   : N>1 multi-shard WATCH supported; legacy whole-store"
                            + " SUBSCRIBE is primary-shard-only"
                            + (allowPartialShardView
                                    ? " (allowPartialShardView=ON - admitted, partial view)"
                                    : " -> refused unless -Dconfigd.edge.allowPartialShardView=true"));
                }
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to start FanOutServer on port " + config.edgePort(), e);
            }
        }

        // Drain quiet-period: the bounded pause shutdown() takes after flipping `draining` so an LB
        // observes the 503 before the listener closes. The DEFAULT is 2000ms at N>1 (a sharded cluster
        // fronted by an LB doing rolling restarts) and 0 at N=1 (a single node with no LB in front has
        // nothing to pause for). An EXPLICIT configd.shutdown.drainQuietMs is honoured at ANY N, so a
        // single-node deployment that IS behind an LB - or the drain-flip test - can request a window.
        // Bounded either way (interruptible sleep) - shutdown never blocks unboundedly. Tests set
        // configd.shutdown.drainQuietMs=0 module-wide (parent pom) so the suite never pauses unless a test
        // explicitly opts in.
        long drainQuietMs = cfg.getLong("configd.shutdown.drainQuietMs", shardCount > 1 ? 2000L : 0L);
        ConfigdServer server = new ConfigdServer(
                config, driver, stateMachine,
                ownerPool, readDispatchExecutor, tlsReloadExecutor, nodeAnchorExecutor, nodeAnchor,
                anchorWitness,
                httpApiServer, tcpTransport, fanOutServer, aclPolicyLoader,
                watchService, fanOutBuffer, compactor, plumtreeNode, hyParViewOverlay,
                subscriptionManager, rolloutController, prometheusExporter, leaderBalanceLoop,
                draining, drainQuietMs);

        // Schedule the off-ack-path node-anchor refresh (audit head + shard-liveness digest). The write
        // decision is the K-records-or-T-ms cadence (both -D tunable); the digest is captured on those
        // writes by dispatching each shard's lastDurableIndex read onto its owner thread. Polled at a
        // sub-T period so the K bound can fire before T. A refresh failure is logged and retried - it is
        // OFF the ack path, not the fail-closed halt the per-shard anchor fsync is.
        long nodeAnchorIntervalMs = cfg.getLong("configd.nodeAnchor.intervalMs", 1000L);
        int nodeAnchorKRecords = cfg.getInt("configd.nodeAnchor.auditRecords", 64);
        long nodeAnchorPollMs = Math.max(50L, Math.min(nodeAnchorIntervalMs, 250L));
        Runnable nodeAnchorRefresh = NodeAnchorService.newRefresher(
                nodeAnchor, auditLog,
                () -> NodeAnchorService.readDurableIndexOnOwners(driver, gids),
                nodeAnchorIntervalMs, nodeAnchorKRecords);
        nodeAnchorExecutor.scheduleAtFixedRate(
                nodeAnchorRefresh, nodeAnchorPollMs, nodeAnchorPollMs, TimeUnit.MILLISECONDS);
        System.out.println("  Node anchor  : periodic refresh every " + nodeAnchorIntervalMs + "ms or "
                + nodeAnchorKRecords + " audit records (off the ack path)");

        final int[] tickCount = {0};
        // Tracks the highest term observed locally so the elections counter advances by the positive
        // delta each tick (a term bump == an election / leadership change). Read from the owner-published
        // monitor snapshot, so it is safe off the group's owner thread.
        final long[] lastSeenTerm = {0L};
        // Schedule a consensus tick on EVERY owner thread: owner[i] runs
        // driver.tickOwner(i)/maybeCompactOwner(i,...), which iterates exactly the groups bound to
        // owner[i] (ownerExecutor(gid) = pool[floorMod(gid, N)]). So each node.tick()/maybeCompact()
        // runs ON its group's owner thread - the per-group single-writer invariant holds, and the
        // assertOwnerThread() net catches any cross-group access. At N=1 the loop runs once for owner[0]
        // with the same cadence/FIFO/work as the original single-owner schedule. At N>1, owners 1..N-1
        // tick zero groups (no-op) until multi-Raft fans groups across them.
        //
        // The SINGLETON housekeeping - the monitorView scrape of DEFAULT_RAFT_GROUP + the co-tenant
        // riders (propagation/watch/plumtree/compactor) - must run EXACTLY ONCE per tick, so it rides
        // owner[0] only. The riders do NOT touch any RaftNode, so owner[0] is a safe home; the scrape
        // uses monitorView() (safe cross-owner). tickCount[0]/lastSeenTerm[0] are mutated only in this
        // owner[0]-only branch on owner[0]'s single thread - no cross-owner sharing.
        for (int ownerIdx = 0; ownerIdx < ownerPool.size(); ownerIdx++) {
            final int owner = ownerIdx;
            ownerPool.ownerByIndex(owner).scheduleAtFixedRate(() -> {
                try {
                    driver.tickOwner(owner);

                    if (owner == 0) {
                        // Publish the apply backlog (committed-not-applied) for the
                        // raft_pending_apply_entries gauge and advance the election counter, both from
                        // the owner-published monitor snapshot (monitorView() - one volatile load of an
                        // immutable, coherent, <= one-tick-stale view; safe cross-owner). DEFAULT_RAFT_-
                        // GROUP is owned by owner[0] (0 % N == 0) so this read is on-owner here. Kept
                        // immediately after tickOwner(0) - which republished the view it reads - to be
                        // order-exact to the prior single-owner schedule.
                        io.configd.raft.RaftNode tickNode = driver.getGroup(DEFAULT_RAFT_GROUP);
                        if (tickNode != null) {
                            io.configd.raft.RaftMetrics view = tickNode.monitorView();
                            pendingApplyEntries.set(Math.max(0L, view.commitIndex() - view.lastApplied()));
                            lastAppliedIndex.set(view.lastApplied());
                            long term = view.currentTerm();
                            if (term > lastSeenTerm[0]) {
                                configdMetrics.raftElections().increment(term - lastSeenTerm[0]);
                                lastSeenTerm[0] = term;
                            }
                        }
                    }

                    // Threshold-gated Raft-log compaction for THIS owner's groups so the WAL stays
                    // bounded. Cheap O(groups-on-owner) check each tick; a group only snapshots when
                    // over the threshold, via the persist-before-truncate path
                    // (durable_prefix_no_gap preserved).
                    driver.maybeCompactOwner(owner, RAFT_LOG_COMPACTION_THRESHOLD);

                    if (owner == 0) {
                        // Co-tenant riders (singleton; do NOT touch RaftNode). Ride owner[0] after
                        // maybeCompact.
                        propagationMonitor.checkAll();
                        watchService.tick();
                        plumtreeNode.tick();

                        // Compact snapshot history every ~10 seconds. Compact EVERY shard's compactor
                        // (Compactor.compact() is thread-safe, so the owner[0] singleton rider may compact
                        // sibling shards' history off their owner threads). At N=1 this is the single
                        // primary compactor.
                        tickCount[0]++;
                        if (tickCount[0] % COMPACTION_INTERVAL_TICKS == 0) {
                            for (Compactor shardCompactor : shardCompactors.values()) {
                                shardCompactor.compact();
                            }
                        }
                    }
                } catch (Throwable t) {
                    // ScheduledExecutorService silently cancels future executions of THIS owner's tick
                    // on an uncaught throwable. The tick loop drives consensus (elections, heartbeats,
                    // replication) - if an owner's tick dies, the groups it owns become zombies. Emit a
                    // structured SEVERE record AND a Prometheus counter increment so SREs can alert on
                    // per-owner tick-loop instability rather than discover it post-mortem.
                    handleTickLoopThrowable(t, configdMetrics);
                }
            }, TICK_PERIOD_MS, TICK_PERIOD_MS, TimeUnit.MILLISECONDS);
        }

        // Schedule TLS certificate hot reload when TLS is enabled. TLS reload (potentially slow cert /
        // keystore I/O) runs on its OWN executor so it cannot delay the 10ms tick loop or the
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

        // Start the leadership auto-balance loop (only present in the N>1 / multi-node regime; see the
        // wiring gate above). Its own dedicated executor means its jittered cadence never touches the
        // consensus tick. Started after the server is fully wired so the first cadence observes a live
        // driver.
        if (server.leaderBalanceLoop != null) {
            server.leaderBalanceLoop.start();
            bootTeardown.push(server.leaderBalanceLoop::close);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Configd shutting down...");
            server.shutdown();
        }, "configd-shutdown"));

        return server;
    }

    /**
     * Shuts down the server, stopping the HTTP API, owner pool, and releasing resources.
     * <p>
     * The readiness drain flag is flipped FIRST (before any close), then a bounded quiet-period lets
     * an LB observe the 503 and stop routing, so in-flight work drains instead of being dropped on restart.
     * <p>
     * Shutdown order matters. We must drain {@code readDispatchExecutor} FIRST so no new
     * read tasks are marshalled onto an owner thread. Then we shut the owner pool (each owner
     * also owns its groups' ReadIndexState + per-owner tick) so any in-flight reads complete.
     * Finally the {@code tlsReloadExecutor} is the slowest to drain and is stopped last.
     */
    public void shutdown() {
        // Drain-flip: report NOT-ready BEFORE closing anything so an LB/orchestrator sees 503 and stops
        // routing while in-flight work drains (no in-flight drop on restart). This MUST precede every close
        // below. /health/live is untouched - a draining node is still alive, just not accepting new work.
        draining.set(true);
        // Bounded quiet-period: give the LB one readiness-probe interval to observe the 503 before the
        // listener closes. 0 at N=1 / in tests (nothing to pause for). Interruptible and always bounded, so
        // a SIGTERM hook thread cannot hang here.
        if (drainQuietMs > 0) {
            try {
                Thread.sleep(drainQuietMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // preserve the interrupt; proceed with teardown
            }
        }
        // Stop the leadership auto-balance loop FIRST so it initiates no new transfers against a driver /
        // owner pool that is about to be torn down. Its own executor drains independently; a null loop
        // (N=1 / single-node / kill switch off) is a no-op.
        if (leaderBalanceLoop != null) {
            leaderBalanceLoop.close();
        }
        // Edge endpoint FIRST: it is a pure consumer of the readSince/replay seams, so closing it
        // before the HTTP API / owner pool / Raft teardown lets edge subscribers receive a clean
        // SERVER_SHUTDOWN and stops any new readSince/replay pulls against a store/consensus engine
        // that is about to be torn down. (Order is safe either way - the fan-out never touches the
        // apply path - but closing it first gives the cleanest edge-visible teardown.)
        if (fanOutServer != null) {
            fanOutServer.close();
        }
        if (httpApiServer != null) {
            httpApiServer.stop();
        }
        // Stop accepting new read marshals first (so nothing new is enqueued onto an owner).
        shutdownExecutor(readDispatchExecutor, "read-dispatch", 2);
        // Then stop the owner pool (each owner drains its per-owner tick + pending read-index /
        // commit-outcome callbacks). Per-owner shutdown reuses the same shutdownNow()-on-timeout
        // fallback as the other executors, preserving the prior single-`tickExecutor` semantics.
        for (int i = 0; i < ownerPool.size(); i++) {
            shutdownExecutor(ownerPool.ownerByIndex(i), "raft-owner-" + i, 5);
        }
        // Drain the config-policy loader worker AFTER the owner pool is stopped: no apply-thread
        // onConfigChange can then enqueue onto a shut worker (which would throw RejectedExecutionException).
        // No-op at N=1 (no worker) and when auth is disabled (null loader).
        if (aclPolicyLoader != null) {
            aclPolicyLoader.close();
        }
        // Slow I/O executor can be shut down last - it is independent.
        shutdownExecutor(tlsReloadExecutor, "tls-reload", 2);
        // Node-anchor refresh: stop the periodic writer, then release the file handle. Skipping a final
        // refresh is safe - a graceful shutdown's un-refreshed tail is handled by the next boot's
        // accept-forward re-anchor (a forward digest advance, never a REFUSE). Off the ack path.
        shutdownExecutor(nodeAnchorExecutor, "node-anchor", 2);
        nodeAnchor.close();
        if (tcpTransport != null) {
            try {
                tcpTransport.close();
            } catch (Exception e) {
                System.err.println("Error closing TCP transport: " + e.getMessage());
            }
        }
    }

    /**
     * Derives the keyed at-rest {@link io.configd.common.IntegrityEnvelope} for the Raft durability
     * artifacts from the cluster signing key, and enforces the signing-key co-location requirement.
     * <p>
     * {@code K_integrity = HKDF-SHA256(IKM = signing private-key encoding,
     * salt = keyId bytes, info = "configd/raft-at-rest-integrity/v2", len = 32)} -
     * derived from the EXISTING cluster-shared signing key, so no new key file and no new
     * key-distribution channel is introduced. The verify side runs the identical derivation.
     * <p>
     * <b>Fail-closed:</b> {@code K_integrity}'s secrecy depends on the signing key living OUTSIDE
     * attacker-writable snapshot/WAL/backup storage. If the resolved {@code keyFile} is co-located
     * inside {@code dataDir}, a storage-tampering attacker who can write the artifacts can also read
     * the key and recompute a valid MAC. {@link #enforceSigningKeyNotColocated} therefore REFUSES TO
     * START by default (the {@code configd.security.allowColocatedSigningKey} opt-out downgrades to a
     * loud warning for dev/test/single-node only); production mounts the key on separate storage.
     *
     * @param keyStore the loaded cluster signing key store
     * @param keyFile  the resolved signing-key file path
     * @param dataDir  the Raft data directory (where artifacts live)
     * @return a keyed, fail-closed integrity envelope
     */
    // Package-private (not private) so EncryptionAtRestWiringTest can assert the flag -> envelope
    // wiring directly, mirroring how enforceSigningKeyNotColocated is exercised by D1FailClosedTest.
    // The no-cfg overload resolves against the ambient system-property + environment source, keeping
    // this three-argument signature byte-identical for every caller that does not pass a ConfigSource.
    static io.configd.common.IntegrityEnvelope deriveRaftIntegrityEnvelope(
            SigningKeyStore keyStore, Path keyFile, Path dataDir) {
        return deriveRaftIntegrityEnvelope(keyStore, keyFile, dataDir, ConfigSource.system());
    }

    static io.configd.common.IntegrityEnvelope deriveRaftIntegrityEnvelope(
            SigningKeyStore keyStore, Path keyFile, Path dataDir, ConfigSource cfg) {
        // FAIL-CLOSED: refuse to derive the at-rest integrity key from a signing key co-located
        // inside the data dir it protects, BEFORE doing any crypto. Default = refuse to start; the
        // dev/test/single-node opt-out (system property OR env var, the latter for CI / docker-compose
        // where -D is awkward) downgrades to a loud warning. anyLayerTrue reproduces the original
        // "system-property OR env-alias" semantics exactly (EITHER being true enables the opt-out).
        boolean allowColocated = cfg.anyLayerTrue("configd.security.allowColocatedSigningKey");
        enforceSigningKeyNotColocated(keyFile, dataDir, allowColocated);
        byte[] ikm = keyStore.keyPair().getPrivate().getEncoded();
        java.util.UUID keyId = keyStore.keyId();
        byte[] salt = java.nio.ByteBuffer.allocate(16)
                .putLong(keyId.getMostSignificantBits())
                .putLong(keyId.getLeastSignificantBits())
                .array();
        try {
            // Auth-on (a signing key is always present): build the persisted keyring and a
            // term-versioned envelope over ALL retained terms. Encryption ON -> AES-256-GCM (Layer C);
            // OFF -> term-versioned HMAC (Layer B). Both derive their at-rest keys from the keyring's
            // independent per-term roots (not the signing key directly), so a term OR signing-key
            // rotation is non-destructive. Keyless is a no-signing-key posture that never reaches here.
            return buildTermVersionedEnvelope(ikm, salt, keyId, dataDir, cfg);
        } finally {
            // Zeroize the transient signing-key material now that the keyring's K_keyringMac/KEK
            // (SecretKeySpec, which clones) hold their own copies. Best-effort (JDK-8160206): the raw
            // Ed25519 private-key encoding should not linger on the heap after boot.
            java.util.Arrays.fill(ikm, (byte) 0);
        }
    }

    /** True if at-rest encryption is enabled (system property, or the CI/docker-friendly env var). */
    private static boolean encryptionAtRestEnabled(ConfigSource cfg) {
        return cfg.anyLayerTrue("configd.raft.encryption.enabled");
    }

    /** System property that sets the edge fan-out server-side prefix-filtering posture. */
    static final String EDGE_FILTER_PROP = "configd.edge.fanout.filter";

    /**
     * Resolves the {@value #EDGE_FILTER_PROP} posture: {@code on}/{@code off}, DEFAULT on for the
     * co-located trusted deployment, fail-loud on any other value (mirroring the
     * {@code NettyTransport.select} / KMS-provider posture flags - never a silent default). Set
     * {@code off} to restore the full-chain feed when a separate/untrusted relay tier terminates
     * the fan-out. This is a two-way door, not a one-way door.
     */
    static boolean resolveEdgeFilterPosture() {
        return resolveEdgeFilterPosture(ConfigSource.system());
    }

    static boolean resolveEdgeFilterPosture(ConfigSource cfg) {
        String v = cfg.getString(EDGE_FILTER_PROP).orElse("on").trim().toLowerCase();
        return switch (v) {
            case "on", "true" -> true;
            case "off", "false" -> false;
            default -> throw new IllegalArgumentException(
                    EDGE_FILTER_PROP + " must be 'on'/'off' (or 'true'/'false'), got: '" + v + "'");
        };
    }

    /**
     * Builds the AES-256-GCM at-rest encryption envelope. Unseals a per-node root key through the
     * configured {@link KmsProvider} ONCE at boot, then derives per-segment DEKs locally.
     * <p>
     * <b>Fail-closed:</b> naming a provider that is not built in is a startup error - NEVER a
     * silent downgrade to no encryption or to a different provider (a silent downgrade is how a
     * "data is encrypted at rest" claim becomes fiction). Only {@code local} (HKDF-from-signing-key)
     * is built in; a cloud provider is added as a separate module that slots into this same seam.
     *
     * <b>requireEncrypted (post-migration hardening):</b> once the pre-encryption HMAC WAL prefix has
     * been compacted away, an operator can set {@code configd.raft.encryption.requireEncrypted} so the
     * reader REFUSES any legacy {@code algId=1} HMAC record. This defends against a rollback/replay of
     * an old pre-encryption WAL segment. Default: keep reading them (the migration path).
     *
     * <b>The keyring:</b> the per-term at-rest roots are INDEPENDENT random 32-byte secrets persisted
     * (wrapped) in the dual-slot {@code raft-keyring}, NOT re-derived from the signing key. This is what
     * makes rotation non-destructive for BOTH postures: HMAC integrity keys ({@code K_integrity[term]})
     * and GCM DEKs both derive from those roots, so rotating the signing key only rewraps the keyring
     * (roots unchanged) and boot loads ALL retained terms so old-term data still verifies/decrypts.
     * First boot (or the enable-encryption migration) mints a fresh {@code root[1]}; a
     * present-but-unreadable keyring REFUSES (fail-closed). The active write term is always the
     * keyring's {@code activeTerm} - boot never hardcodes term=1.
     *
     * @param ikm     the signing private-key encoding (the local KEK/mac IKM)
     * @param salt    the signing keyId bytes (HKDF salt)
     * @param keyId   the signing keyId (the loggable KEK reference / node id)
     * @param dataDir the node data directory (where {@code raft-keyring} lives)
     * @param cfg     the resolved configuration source (encryption enable / requireEncrypted / provider)
     * @return a term-versioned {@link IntegrityEnvelope} (HMAC when encryption off, GCM when on)
     */
    private static io.configd.common.IntegrityEnvelope buildTermVersionedEnvelope(
            byte[] ikm, byte[] salt, java.util.UUID keyId, Path dataDir, ConfigSource cfg) {
        boolean encrypt = encryptionAtRestEnabled(cfg);
        // anyLayerTrue reproduces the original "system-property OR env-alias" semantics for the flag.
        boolean requireEncrypted = cfg.anyLayerTrue("configd.raft.encryption.requireEncrypted");
        // Resolve the KEYRING-CUSTODY SECRET: the IKM the two keyring-wrapping keys (K_keyringMac and
        // KEK_wrap) are HKDF-derived from. For 'local' - and for the encryption-OFF term-versioned HMAC
        // posture - it IS the signing-key IKM, byte-identical to every prior boot: the raw signing key
        // never crosses the KMS SPI boundary (secret minimisation), and existing encrypted data still
        // decrypts because the derivation is unchanged. For an EXTERNAL custodian (vault-transit, a cloud
        // CMK, ...) it is a per-node secret UNSEALED ONCE through the KmsProvider at boot; an
        // unreachable custodian FAILS CLOSED - never a silent downgrade to no encryption or a
        // different provider. Only encryption-ON with a non-'local' provider takes the SPI branch.
        String providerName = encrypt
                ? cfg.getString("configd.raft.encryption.kms.provider").orElse("local").trim()
                : "local";
        boolean externalCustody = encrypt && !"local".equals(providerName);
        byte[] custodySecret = externalCustody
                ? unsealKeyringCustodySecret(providerName, dataDir, keyId, cfg)
                : ikm; // ALIAS of the caller's signing-key IKM (zeroed by deriveRaftIntegrityEnvelope)

        // Two custody-secret-derived, domain-separated keys authenticate and wrap the keyring:
        //   K_keyringMac authenticates the whole keyring file; KEK_wrap AES-GCM-wraps each root.
        // Neither derives the roots (those are independent random material in the keyring) - the whole
        // point of the decoupling that makes both term and signing-key rotation non-destructive.
        javax.crypto.SecretKey keyringMac = deriveKeyringKey(custodySecret, salt, KEYRING_MAC_INFO, "HmacSHA256");
        javax.crypto.SecretKey kek = deriveKeyringKey(custodySecret, salt, KEYRING_WRAP_INFO, "AES");
        if (externalCustody) {
            // The two keyring keys have taken their own copies (SecretKeySpec clones); wipe the external
            // custody secret, a distinct array this method owns. The 'local'/OFF path aliases the caller's
            // ikm (zeroed by deriveRaftIntegrityEnvelope), so it is deliberately NOT touched here.
            java.util.Arrays.fill(custodySecret, (byte) 0);
        }
        byte[] nodeKeyId = keyId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // The keyring lives in dataDir (production start() already created it; ensure it exists here
        // too so a direct-call boot path can mint the keyring). Idempotent.
        try {
            Files.createDirectories(dataDir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot create data directory for the keyring: " + dataDir, e);
        }

        // Load (or, on first boot / enable-encryption migration, mint) the persisted keyring, then build
        // ONE SegmentKeyManager over ALL retained terms with the keyring's activeTerm current. It serves
        // BOTH the HMAC integrity key (K_integrity[term]) and the GCM DEK. A present-but-unreadable
        // keyring throws IntegrityException here (fail-closed startup).
        try (NodeKeyring keyring = NodeKeyring.loadOrCreate(dataDir, keyringMac, kek, nodeKeyId)) {
            java.util.List<RootKey> roots = keyring.unsealRootKeys(keyId.toString());
            // Invariant: this is ONE SegmentKeyManager, and the SAME instance is shared
            // across ALL N Raft groups (it rides inside the single raftIntegrity envelope passed to every
            // buildRaftGroup). Global no-(key,nonce)-reuse holds because that one manager issues every
            // nonce from a single per-magic atomic counter. If a future refactor gives each group its OWN
            // manager, EACH manager MUST draw its OWN fresh random segmentId (hence its own DEK): sharing
            // a DEK across managers while splitting the nonce counter per group WOULD reuse (key,nonce)
            // and break GCM. Do not split the counter without splitting the segmentId/DEK.
            SegmentKeyManager keyManager =
                    SegmentKeyManager.overTerms(roots, keyring.activeTerm(), nodeKeyId);
            if (encrypt) {
                LOG.log(Level.INFO,
                        "At-rest encryption ENABLED (AES-256-GCM, keyring terms={0}, activeTerm={1},"
                                + " requireEncrypted={2})",
                        new Object[]{roots.size(), keyring.activeTerm(), requireEncrypted});
                return io.configd.common.IntegrityEnvelope.encrypting(keyManager, requireEncrypted);
            }
            LOG.log(Level.INFO,
                    "At-rest integrity: term-versioned HMAC (keyring terms={0}, activeTerm={1})",
                    new Object[]{roots.size(), keyring.activeTerm()});
            return io.configd.common.IntegrityEnvelope.hmac(keyManager);
        }
    }

    /**
     * Unseals the per-node keyring-custody secret through an EXTERNAL {@link io.configd.common.kms.KmsProvider}
     * discovered by {@link io.configd.common.kms.KmsProviderFactory} (ServiceLoader). This is the genuine SPI
     * boot seam for every non-{@code local} custodian.
     * <p>
     * Fail-loud: a selected provider whose module is not on the classpath is a startup error - never a
     * silent downgrade. First boot / enable-encryption migration mints and seals a fresh secret and persists
     * its {@link io.configd.common.kms.WrappedKey} beside the keyring (mirroring the keyring's own first-boot
     * mint); every later boot reads that carrier and performs the ONE {@code unwrap} call. Fail-closed
     * ({@link io.configd.common.kms.KmsUnavailableException}) if the backend is unreachable at boot - the node
     * refuses to start. The provider is {@code close()}d (its token dropped) the instant the secret is
     * recovered, so no live provider handle survives onto the data path.
     *
     * @return the freshly-unsealed custody secret; the CALLER owns and zeroes it after deriving the keyring keys
     */
    private static byte[] unsealKeyringCustodySecret(
            String providerName, Path dataDir, java.util.UUID keyId, ConfigSource cfg) {
        java.util.Map<String, io.configd.common.kms.KmsProviderFactory> factories =
                io.configd.common.kms.KmsProviderFactory.discover();
        io.configd.common.kms.KmsProviderFactory factory = factories.get(providerName);
        if (factory == null) {
            throw new IllegalStateException(
                    "configd.raft.encryption.kms.provider='" + providerName + "' is not available: it is not"
                            + " the built-in 'local' provider and no configd-kms-<provider> module on the"
                            + " classpath provides it. Refusing to start rather than silently downgrade - a"
                            + " silent downgrade is how a 'data is encrypted at rest' claim becomes fiction."
                            + " Add the matching module to the runtime classpath, or unset the property."
                            + " Known external providers: " + new java.util.TreeSet<>(factories.keySet()));
        }
        try {
            Files.createDirectories(dataDir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot create data directory for the KMS sealed root: " + dataDir, e);
        }
        Path sealedRoot = dataDir.resolve(KmsSealedRootStore.FILE_NAME);
        io.configd.common.kms.KmsBootContext ctx =
                new io.configd.common.kms.KmsBootContext(keyId.toString());
        try (io.configd.common.kms.KmsProvider provider = factory.create(cfg, ctx)) {
            provider.healthCheck(); // pre-flight reachability; unreachable -> KmsUnavailableException -> fail closed
            io.configd.common.kms.RootKey root;
            if (KmsSealedRootStore.exists(sealedRoot)) {
                root = provider.unwrap(KmsSealedRootStore.read(sealedRoot)); // the ONE boot call
            } else {
                io.configd.common.kms.KmsProvider.Provisioned provisioned = provider.generateRootKey();
                KmsSealedRootStore.write(sealedRoot, provisioned.wrapped()); // persist the sealed carrier (fsync)
                root = provisioned.rootKey();
            }
            try {
                LOG.log(Level.INFO, "At-rest keyring custody unsealed via KMS provider ''{0}'' (keyId={1})",
                        new Object[]{providerName, root.keyId()});
                return root.withMaterial(byte[]::clone);
            } finally {
                root.destroy(); // the caller now owns the returned bytes; wipe our RootKey handle
            }
        } catch (io.configd.common.kms.KmsUnavailableException e) {
            throw new IllegalStateException(
                    "at-rest KMS provider '" + providerName + "' is unavailable at boot - refusing to start"
                            + " (fail closed). The node will NOT fall back to no encryption or a different"
                            + " provider. Cause: " + e.getMessage(), e);
        }
    }

    /** HKDF info string for the keyring outer-MAC key {@code K_keyringMac}. */
    private static final byte[] KEYRING_MAC_INFO =
            "configd/keyring-mac/v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    /** HKDF info string for the keyring root-wrapping KEK {@code KEK_wrap}. */
    private static final byte[] KEYRING_WRAP_INFO =
            "configd/keyring-wrap/v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /**
     * Derives a 32-byte keyring key from the signing key via the SAME HKDF construction as
     * {@link #deriveRaftIntegrityEnvelope} (IKM = signing private-key encoding, salt = keyId bytes)
     * but with a distinct {@code info} string, so {@code K_keyringMac} and {@code KEK_wrap} are
     * domain-separated from each other and from K_integrity / K_audit. The transient derived bytes
     * are zeroed after the {@link javax.crypto.spec.SecretKeySpec} takes its own copy.
     */
    private static javax.crypto.SecretKey deriveKeyringKey(byte[] ikm, byte[] salt, byte[] info,
                                                           String algorithm) {
        byte[] k = io.configd.common.Hkdf.deriveKey(ikm, salt, info, 32);
        try {
            return new javax.crypto.spec.SecretKeySpec(k, algorithm);
        } finally {
            java.util.Arrays.fill(k, (byte) 0);
        }
    }

    /**
     * Fail-closed co-location guard. The at-rest integrity key {@code K_integrity} is HKDF-derived
     * from the cluster signing key, so that signing key MUST NOT live inside the data directory holding
     * the snapshot/WAL/state it protects: a storage-tampering / full-host adversary who can write those
     * artifacts could then ALSO read the co-located key and recompute a valid MAC, making the integrity
     * layer worthless.
     * <p>
     * <b>Default behavior is to REFUSE TO START</b> ({@link SecurityException}). {@code allowColocated}
     * - wired from the system property {@code configd.security.allowColocatedSigningKey} - downgrades
     * this to a loud warning for dev/test/single-node ONLY; production must mount the signing key on
     * separate storage (KMS/HSM/mounted secret) and leave the opt-out unset.
     *
     * @param keyFile        the resolved signing-key file path
     * @param dataDir        the Raft data directory (where the protected artifacts live)
     * @param allowColocated dev/test opt-out; when false (production default) co-location throws
     * @throws SecurityException if the key is co-located inside the data dir and the opt-out is unset
     */
    static void enforceSigningKeyNotColocated(Path keyFile, Path dataDir, boolean allowColocated) {
        if (!isInsideDataDir(keyFile, dataDir)) {
            return; // key is on separate storage - the correct production layout
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
     * Fail-closed footgun guard against SILENTLY exposing an unauthenticated store on a public
     * network interface - the Redis/etcd "default-open" compromise class. This is deliberately NOT
     * "auth required by default": a no-auth deployment stays a legitimate workload choice. The one thing
     * refused is doing it by ACCIDENT - binding a non-loopback interface with authentication off and no
     * explicit acknowledgement.
     * <p>
     * Refuses only when ALL of: (1) authentication is off on the client-facing plane, AND (2) the bind is
     * a non-loopback interface (0.0.0.0 / :: bind ALL interfaces incl. public, so they count as
     * non-loopback), AND (3) the {@code configd.security.allowInsecurePublicBind} override is unset.
     * When the override IS set the bind proceeds but a loud WARN is logged. An authenticated store, a
     * loopback-only bind, or an unresolvable address that we cannot prove is loopback are handled up front.
     * <p>
     * Mirrors the {@link #enforceSigningKeyNotColocated} co-location guard: package-private and
     * parameterized on plain values (no {@link ServerConfig}/{@link ConfigSource}) so
     * {@code InsecurePublicBindFailClosedTest} can drive it directly, and its opt-out has the same
     * "production fails closed, dev opts out" shape.
     *
     * @param bindAddress              the configured bind address ({@link ServerConfig#bindAddress()})
     * @param authEnabled              whether the client-facing plane authenticates requests (the
     *                                 {@code configd.auth.*} chain or the legacy {@code --auth-token})
     * @param allowInsecurePublicBind  operator acknowledgement; when {@code false} (the production
     *                                 default) a non-loopback bind with auth off refuses to start
     * @throws SecurityException if auth is off, the bind is non-loopback, and the override is unset
     */
    static void enforceBindNotSilentlyPublic(
            String bindAddress, boolean authEnabled, boolean allowInsecurePublicBind) {
        if (authEnabled) {
            return; // an authenticated store may bind any interface - a legitimate operator choice
        }
        if (!isNonLoopbackBind(bindAddress)) {
            return; // loopback-only bind: not reachable off-box, so auth-off is safe by construction
        }
        if (!allowInsecurePublicBind) {
            throw new SecurityException(
                    "B5 fail-closed: refusing to bind an UNAUTHENTICATED Configd store to the non-loopback"
                            + " interface '" + bindAddress + "'. A store reachable off-box with authentication"
                            + " off lets anyone on the network read and write every key (the Redis/etcd"
                            + " default-open class). Choose one: (a) enable authentication (configd.auth.mode"
                            + " or --auth-token); (b) bind loopback (--bind-address 127.0.0.1); or (c) if an"
                            + " unauthenticated public bind is genuinely intended, acknowledge it explicitly"
                            + " with -Dconfigd.security.allowInsecurePublicBind=true.");
        }
        // Override explicitly set: proceed, but warn loudly - an unauthenticated store is on a public
        // interface by deliberate operator choice (front it with a trusted network boundary).
        String banner = "************************************************************";
        System.err.println("WARNING: " + banner);
        System.err.println("WARNING: An UNAUTHENTICATED Configd store is bound to a NON-LOOPBACK interface:");
        System.err.println("WARNING:   bind address : " + bindAddress);
        System.err.println("WARNING: Anyone who can reach this interface can read and write every key.");
        System.err.println("WARNING: Permitted only because configd.security.allowInsecurePublicBind=true.");
        System.err.println("WARNING: Enable authentication or front it with a trusted network boundary.");
        System.err.println("WARNING: " + banner);
        LOG.log(Level.SEVERE,
                "B5: unauthenticated store bound to non-loopback interface {0} — permitted only by the"
                        + " explicit allowInsecurePublicBind override; enable auth or bind loopback for"
                        + " production",
                bindAddress);
    }

    /**
     * True if {@code bindAddress} names an interface reachable off-box (non-loopback). The wildcard
     * 0.0.0.0 / :: ({@link InetAddress#isAnyLocalAddress()}) binds ALL interfaces including public ones,
     * so it counts as non-loopback; a genuine loopback literal/hostname (127.0.0.0/8, ::1, localhost)
     * does not. An address that cannot be resolved is treated as non-loopback: we cannot prove it is
     * loopback, so we fail closed rather than assume it is safe.
     */
    private static boolean isNonLoopbackBind(String bindAddress) {
        try {
            // getByName resolves a literal directly and a hostname via the name service; 0.0.0.0 / ::
            // resolve to the wildcard address, whose isLoopbackAddress() is false (isAnyLocalAddress()).
            return !InetAddress.getByName(bindAddress).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return true; // unresolvable -> cannot prove loopback -> fail closed
        }
    }

    /**
     * Derives the audit-log chain MAC key {@code K_audit} from the cluster signing key using the
     * SAME HKDF construction as {@link #deriveRaftIntegrityEnvelope} but with a DISTINCT
     * {@code info} string - {@code "configd/audit-log-integrity/v1"} vs the Raft
     * {@code "configd/raft-at-rest-integrity/v2"} - so the two derived keys are domain-separated and
     * independent (compromise/analysis of one does not yield the other). Same IKM (signing private-key
     * encoding) and salt (keyId bytes). Residual: an attacker who holds the cluster signing key can
     * recompute {@code K_audit} and forge the chain (the co-location warning is emitted once by
     * {@code deriveRaftIntegrityEnvelope}).
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

    /** True if {@code keyFile} resolves to a path within {@code dataDir} (the co-location check). */
    private static boolean isInsideDataDir(Path keyFile, Path dataDir) {
        try {
            Path kf = keyFile.toAbsolutePath().normalize();
            Path dd = dataDir.toAbsolutePath().normalize();
            return kf.startsWith(dd);
        } catch (RuntimeException e) {
            // If path normalization fails for any reason, do not suppress the warning
            // silently - err toward warning (treat as co-located) is too noisy, but a
            // failure here is unexpected; log and treat as not-inside (best effort).
            LOG.log(Level.WARNING, "PA-2021: could not compare key/data paths for co-location check", e);
            return false;
        }
    }

    /**
     * Reads and range-validates the deploy-time shard count {@code N} from
     * {@code configd.raft.shardCount} (default {@code 1} - a single group, byte-identical; a system
     * property, consistent with the other {@code configd.raft.*} tunables). Validates
     * {@code 1 <= N <= }{@link #MAX_SHARD_COUNT}; a clear error otherwise. The fixed-at-deploy
     * enforcement + topology-epoch derivation is done separately by {@link #enforceTopologyDescriptor}
     * once the Raft integrity envelope exists (the descriptor is authenticated with that same key).
     *
     * <p>Package-private static so {@code ShardCountConfigTest} can drive it directly.
     *
     * @return the validated shard count {@code N}
     * @throws IllegalArgumentException if {@code N} is out of range
     */
    static int resolveShardCount() {
        return resolveShardCount(ConfigSource.system());
    }

    static int resolveShardCount(ConfigSource cfg) {
        int shardCount = cfg.getInt("configd.raft.shardCount", 1);
        if (shardCount < 1 || shardCount > MAX_SHARD_COUNT) {
            throw new IllegalArgumentException(
                    "configd.raft.shardCount must be in [1, " + MAX_SHARD_COUNT + "], got " + shardCount
                            + " — static-N multi-Raft; N is a deploy-time constant fixed for the life of"
                            + " the deployment (changing it requires a manual reshard).");
        }
        return shardCount;
    }

    /**
     * Builds the edge client-cert validity gate from config, fail-closed, emitting the loud operator
     * warnings a misconfiguration deserves. Defaults reproduce today's behavior: revocation OFF +
     * {@code enforceCertNotAfter} false yields {@link io.configd.server.fanout.EdgeCertGate#OFF}, which is
     * byte-identical (no online lookup, no active cert expiry). This gate is threaded ONLY into the edge
     * fan-out transport; the Raft interior never receives one, so the {@code exemptInterNode} invariant
     * holds by construction regardless of this method's result.
     *
     * <p>No built-in OCSP/CRL responder ships: the {@code RevocationChecker} seam is left null, so a
     * lookup returns {@code UNKNOWN} and the mode decides (lax fails open + alarms; strict fails closed).
     * A real responder plugs into that seam as a follow-on.
     */
    private static io.configd.server.fanout.EdgeCertGate buildEdgeCertGate(
            ConfigSource cfg, io.configd.server.fanout.RegistryFanOutSessionMetrics metrics) {
        io.configd.common.auth.RevocationPolicy revocationPolicy =
                io.configd.common.auth.RevocationPolicy.fromConfig(cfg);
        if (!revocationPolicy.exemptInterNode()) {
            // Setting this false re-arms the CockroachDB-style strict-lockout foot-gun. The Raft interior
            // is NEVER revocation-checked (by construction), so the flag has no effect today beyond
            // signalling intent - but a down responder must never be able to gate consensus, so warn loudly.
            System.err.println("WARNING: ************************************************************");
            System.err.println("WARNING: configd.auth.revocation.exemptInterNode=false re-arms the");
            System.err.println("WARNING: strict-lockout foot-gun. The Raft inter-node plane and the");
            System.err.println("WARNING: break-glass admin credential are validated by chain + notAfter");
            System.err.println("WARNING: ONLY and must never consult a revocation responder - a down");
            System.err.println("WARNING: responder must never be able to brick the cluster interior.");
            System.err.println("WARNING: ************************************************************");
        }
        boolean enforceCertNotAfter = cfg.getBoolean("configd.auth.expiry.enforceCertNotAfter", false);
        io.configd.common.auth.CredentialExpiryPolicy expiryPolicy =
                io.configd.common.auth.CredentialExpiryPolicy.fromConfig(cfg);
        // The functional default responder is a CRL file (configd.auth.revocation.crlFile); a live OCSP
        // responder stays a pluggable RevocationChecker the operator supplies. When a mode is enabled with
        // NO responder wired, the checker is null -> every lookup is UNKNOWN, so warn (strict would then
        // REJECT every new edge cert connection - the documented foot-gun).
        io.configd.common.auth.RevocationChecker revocationChecker = null;
        if (revocationPolicy.enabled()) {
            java.util.Optional<String> crlFile =
                    cfg.getString("configd.auth.revocation.crlFile").filter(s -> !s.isBlank());
            if (crlFile.isPresent()) {
                revocationChecker = new io.configd.common.auth.CrlFileRevocationChecker(
                        java.nio.file.Path.of(crlFile.get().trim()));
                System.out.println("  Revocation   : mode=" + revocationPolicy.mode()
                        + " CRL=" + crlFile.get().trim());
            } else {
                System.err.println("WARNING: configd.auth.revocation.mode=" + revocationPolicy.mode()
                        + " but no responder is configured (set configd.auth.revocation.crlFile, or plug an"
                        + " OCSP RevocationChecker); edge client-cert lookups return UNKNOWN"
                        + (revocationPolicy.mode() == io.configd.common.auth.RevocationMode.STRICT
                                ? " -> STRICT will REJECT every new edge cert connection until a responder is wired."
                                : " -> LAX will fail-open + raise the responder-unreachable alarm on every cert."));
            }
        }
        if (!revocationPolicy.enabled() && !enforceCertNotAfter) {
            return io.configd.server.fanout.EdgeCertGate.OFF; // byte-identical
        }
        return new io.configd.server.fanout.EdgeCertGate(
                revocationPolicy, revocationChecker, expiryPolicy, enforceCertNotAfter,
                metrics::onRevocationFailOpenAdmit);
    }

    /**
     * Whether authentication is enabled for the node-join gate's fail-closed default: the pluggable
     * {@code configd.auth.*} chain is configured (and is not the explicit auth-disabled {@code none}
     * posture), OR the legacy static {@code --auth-token} is set. Mirrors the auth-wiring predicate below
     * ({@code configuredProviders} / {@code authEnabled}) so the boot gate and the request-path auth agree
     * on what "authenticated" means.
     */
    private static boolean isAuthEnabled(ConfigSource cfg, ServerConfig config) {
        List<String> providers = AuthenticatorChain.configuredProviders(cfg);
        // configd.auth.mode=none explicitly disables auth: the noAuthMode boot branch wires NO chain,
        // interceptor, or ACL (the open gate), which supersedes the legacy static --auth-token. A leftover
        // token is therefore inert on this posture, so the store is genuinely open. Counting the token here
        // would let the enforceBindNotSilentlyPublic guard believe the store is authenticated when it is
        // fully open on every interface - the exact footgun that guard exists to catch. So `none` is
        // auth-off regardless of any --auth-token, keeping this predicate equal to "an ACL/interceptor was
        // wired".
        if (providers.equals(List.of("none"))) {
            return false;
        }
        boolean spiAuthEnabled = !providers.isEmpty();
        return spiAuthEnabled || config.authEnabled();
    }

    /**
     * Fixed-at-deploy guard + topology-epoch source. On first boot mints an authenticated,
     * versioned {@link TopologyDescriptor} at N + {@link TopologyDescriptor#INITIAL_EPOCH} and writes it
     * to {@value #TOPOLOGY_DESCRIPTOR_FILE}; on a later boot it verifies the persisted descriptor and
     * REJECTS a different configured {@code N} (changing N on an existing deployment requires a manual
     * reshard - static-N sharding does not support dynamic resharding). Wrapping the descriptor in the
     * Raft integrity envelope makes the guard TAMPER-EVIDENT under a key: a corrupt, MAC-failing,
     * rolled-version, or reserved-illegal-epoch descriptor is refused with the same fail-closed
     * refuse-to-start class as a corrupt marker file. Idempotent: a matching descriptor is a read-only
     * no-op. The first-boot write is crash-durable (temp + fsync, atomic rename, dir fsync - mirroring
     * {@code FileStorage.put}) so a crash can neither leave a torn descriptor nor lose it.
     *
     * <p>Package-private static so {@code ShardCountConfigTest} can drive it directly without standing up
     * a server.
     *
     * @param shardCount    the configured, range-validated shard count (1..{@link #MAX_SHARD_COUNT})
     * @param dataDir       the data directory (holds {@value #TOPOLOGY_DESCRIPTOR_FILE})
     * @param raftIntegrity the Raft integrity envelope (same {@code K_integrity}/posture as the WAL)
     * @return the topology epoch to bind into {@code StaticShardMap.epoch()} and every edge resume token
     * @throws IllegalStateException if the persisted descriptor records a different {@code N} (a reshard
     *                               attempt), or is corrupt / tampered / malformed (refuse to start)
     * @throws RuntimeException      if the descriptor cannot be read or written
     */
    static long enforceTopologyDescriptor(int shardCount, Path dataDir,
            io.configd.common.IntegrityEnvelope raftIntegrity) {
        Path descriptorFile = dataDir.resolve(TOPOLOGY_DESCRIPTOR_FILE);
        try {
            if (Files.exists(descriptorFile)) {
                byte[] enveloped = Files.readAllBytes(descriptorFile);
                TopologyDescriptor persisted;
                try {
                    persisted = TopologyDescriptor.fromEnvelope(raftIntegrity, enveloped);
                } catch (io.configd.common.IntegrityException | IllegalStateException bad) {
                    // Tamper / CRC-or-MAC failure / rolled version / reserved-illegal epoch: refuse to
                    // start, fail-closed - now tamper-evident (an attacker cannot edit N/epoch to bypass
                    // the reshard refusal without invalidating the envelope MAC under a key).
                    throw new IllegalStateException(
                            "corrupt or tampered topology descriptor " + descriptorFile
                                    + "; refusing to start. Restore it from a trusted replica or redeploy"
                                    + " on a clean data directory. Cause: " + bad.getMessage(), bad);
                }
                if (persisted.shardCount() != shardCount) {
                    throw new IllegalStateException(
                            "configd.raft.shardCount=" + shardCount + " but this data directory was"
                                    + " initialized with N=" + persisted.shardCount() + " (" + descriptorFile
                                    + "). The shard count is FIXED AT DEPLOY (static-N); changing it would"
                                    + " mis-route already-committed keys to the wrong group. To change N,"
                                    + " perform a manual reshard or redeploy on a fresh data directory.");
                }
                return persisted.topologyEpoch(); // matches - fixed-at-deploy honoured
            }
            // First boot for this data dir: mint the descriptor at the initial epoch and write it
            // CRASH-DURABLY - temp + fsync, atomic rename, fsync the directory - mirroring FileStorage.put
            // so a crash in the OS writeback window can neither LOSE the descriptor nor leave it torn. The
            // descriptor is the durability backbone of the fixed-at-deploy guard AND the topology epoch,
            // so it gets the same fsync discipline as Raft state.
            TopologyDescriptor descriptor =
                    new TopologyDescriptor(shardCount, TopologyDescriptor.INITIAL_EPOCH);
            byte[] bytes = descriptor.toEnvelope(raftIntegrity);
            Path tmp = dataDir.resolve(TOPOLOGY_DESCRIPTOR_FILE + ".tmp");
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(tmp,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true); // fsync data + metadata before the rename
            }
            try {
                Files.move(tmp, descriptorFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException amns) {
                Files.move(tmp, descriptorFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // fsync the directory so the rename itself is durable (mirrors FileStorage.sync()).
            try (java.nio.channels.FileChannel dir = java.nio.channels.FileChannel.open(
                    dataDir, java.nio.file.StandardOpenOption.READ)) {
                dir.force(true);
            }
            return descriptor.topologyEpoch();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read/write topology descriptor " + descriptorFile, e);
        }
    }

    /**
     * The per-group consensus runtime bundle produced by {@link #buildRaftGroup}. Holds every object
     * that is PER SHARD - its own durable log, store, state machine, node, and outbound transport.
     * The node-level singletons (AuditLog, signing key, owner pool, driver, and the fan-out/read/write/
     * HTTP wiring) are NOT here: they are shared across groups, and the not-yet-sharded ones are bound to
     * the PRIMARY group in {@code start()}.
     *
     * @param groupId             the shard / Raft group id
     * @param storage             this group's durable {@link Storage} (the node-level instance at N=1; a
     *                            per-shard {@code dataDir/shard-<gid>} directory at N&gt;1)
     * @param raftLog             this group's WAL/snapshot log over {@code storage}
     * @param configStore         this group's versioned config store
     * @param stateMachine        this group's config state machine
     * @param raftNode            this group's consensus node
     * @param adapter             this group's OUTBOUND transport adapter (stamps {@code groupId});
     *                            {@code null} in single-node/test mode (no peers)
     * @param coalescingTransport this group's heartbeat-coalescing decorator over {@code adapter};
     *                            {@code null} in single-node/test mode
     */
    record RaftGroupRuntime(
            int groupId,
            Storage storage,
            RaftLog raftLog,
            VersionedConfigStore configStore,
            ConfigStateMachine stateMachine,
            RaftNode raftNode,
            RaftTransportAdapter adapter,
            CoalescingRaftTransport coalescingTransport) {
    }

    /**
     * Builds ONE group's consensus runtime. The single bring-up path used for EVERY shard, so the
     * intricate storage/log/store/state-machine/node/transport/group-commit wiring is written once.
     * Package-private static so {@code MultiGroupBringupTest} can drive the real bring-up for N&gt;1
     * without standing up a whole server; the production loop in {@code start()} calls it once per shard.
     *
     * <p><b>N=1 byte-identity:</b> at {@code shardCount == 1} the group reuses the node-level
     * {@code nodeStorage} instance (so its WAL/snapshot bytes and on-disk paths are unchanged), its
     * outbound adapter stamps gid 0, and its group-commit dispatches on group 0. At {@code shardCount > 1}
     * each group gets its own {@code dataDir/shard-<gid>} storage. The caller registers the returned node
     * on the driver, binds its owner thread, and binds its coalescer.
     *
     * @return the fully-wired (but not-yet-registered, not-yet-owner-bound) group runtime
     */
    /**
     * The anchor-witness VOTE mode. The BOOT gate is ALWAYS strict (peer-majority) and closes the
     * grant-versus-witnessed race at N=3 out of the box, so it is not a toggle. This controls ONLY vote
     * deferral:
     * {@code -Dconfigd.raft.witnessStrict=true} opts into strict-VOTE (defer voteGranted until a
     * peer-majority acks - the N&gt;=5 absolute close of the grant→witnessed race). It is opt-in, NOT
     * the default, because deferring voteGranted breaks single-fault leader failover (operator ruling
     * after the CI smoke test caught full-strict-default deadlocking a 3-node failover). Unset / any
     * non-{@code true} value = the default fast-vote mode (voteGranted immediately after the announce;
     * failover preserved). Only an explicit {@code true} enables deferral, so a typo cannot silently
     * break failover. Package-private static so the production default is directly testable.
     */
    static boolean witnessStrictEnabled() {
        return witnessStrictEnabled(ConfigSource.system());
    }

    static boolean witnessStrictEnabled(ConfigSource cfg) {
        // Lenient "true"-only test: any other value (including empty) stays fast-vote. NOT the strict
        // cfg.getBoolean - a typo must not throw and break failover, it must fall to the safe default.
        return "true".equalsIgnoreCase(cfg.getString("configd.raft.witnessStrict").orElse("false"));
    }

    static RaftGroupRuntime buildRaftGroup(
            int groupId, int shardCount, Path dataDir, Storage nodeStorage,
            IntegrityEnvelope raftIntegrity, Clock clock, ConfigSigner configSigner,
            ConfigStateMachine.InvariantChecker smInvariantChecker,
            RaftNode.InvariantChecker raftInvariantChecker, ConfigdMetrics configdMetrics,
            RaftConfig raftConfig, NodeId nodeId, RaftTransportEndpoint tcpTransport,
            boolean groupCommitEnabled, int groupCommitMaxBatch, long groupCommitLingerMicros,
            MultiRaftDriver driver) {
        // Per-group storage: at N=1 reuse the node-level instance (byte-identical WAL/snapshot + paths);
        // at N>1 isolate each shard under dataDir/shard-<gid>. AuditLog + signing key stay node-level.
        Storage groupStorage = (shardCount == 1)
                ? nodeStorage
                : Storage.file(dataDir.resolve("shard-" + groupId));
        // The node-level keyed integrity envelope authenticates this group's WAL + snapshot + merged
        // anchor; the groupId is stamped as the envelope scopeId (cross-shard-splice defense) and
        // asserted on recovery. At N=1 groupId=0. The RaftLog builds its dual-slot raft-anchor in this
        // storage's directory (subsuming raft.persistent_state + snapshot-meta).
        RaftLog raftLog = new RaftLog(groupStorage, raftIntegrity, groupId);

        ConfigSnapshot initialSnapshot = new ConfigSnapshot(
                HamtMap.empty(), 0L, clock.currentTimeMillis());
        VersionedConfigStore configStore = new VersionedConfigStore(initialSnapshot, clock);
        ConfigStateMachine stateMachine =
                new ConfigStateMachine(configStore, clock, smInvariantChecker, configSigner,
                        new ServerStateMachineMetrics(configdMetrics));

        // Per-group RandomGenerator: distinct seed per (node, group). At gid 0 the `groupId * STRIDE`
        // term is 0, so the seed equals the single-group seed (nodeId*31 + nanoTime). No two groups
        // share an RNG instance (avoiding a cross-owner-thread data race at N>1), and election timeouts
        // STAGGER across shards (correlated-election-storm mitigation). RNG affects only election-timing
        // jitter; the WAL/wire/snapshot format is unaffected.
        RandomGenerator groupRandom = RandomGeneratorFactory.getDefault().create(
                nodeId.id() * 31L + groupId * GID_RNG_STRIDE + System.nanoTime());

        // Per-group OUTBOUND transport: each group stamps ITS gid, wrapped in a per-group
        // CoalescingRaftTransport. The node-level tcpTransport is SHARED (one bind/port); the inbound
        // demux (registered once in start()) routes each frame to its group. No peers -> a no-op
        // transport (single-node/test). At N=1 group 0 stamps gid 0 -> byte-identical frames.
        RaftTransportAdapter adapter = null;
        CoalescingRaftTransport coalescingTransport = null;
        RaftTransport transport;
        if (tcpTransport != null) {
            adapter = new RaftTransportAdapter(tcpTransport, groupId,
                    tcpTransport.peerIdentityEnforced(), new ServerRaftTransportMetrics(configdMetrics));
            coalescingTransport = new CoalescingRaftTransport(adapter, groupId);
            transport = coalescingTransport;
        } else {
            transport = (target, message) -> {
                // No-op: peer addresses not configured (single-node or test mode)
            };
        }

        RaftNode raftNode = new RaftNode(
                raftConfig, raftLog, transport, stateMachine,
                groupRandom, groupStorage, raftInvariantChecker, raftIntegrity);

        // Fail-closed durability (fsyncgate): a WAL- or anchor-fsync that throws means the durable
        // advance did not happen, so the node must not commit/ack and must stop. Halt (not exit) so no
        // shutdown hook runs another fsync that could falsely "succeed" after Linux marked the failed
        // page clean. Restart rebuilds from the durable WAL/anchor.
        raftNode.setDurabilityFailureHandler((seam, cause) -> {
            LOG.log(Level.SEVERE, "durability fsync failed at seam '" + seam + "' for raft group "
                    + groupId + " - the durable advance did not happen; halting to avoid re-acking"
                    + " lost state (fsyncgate)", cause);
            Runtime.getRuntime().halt(70);
        });

        // Peer-quorum anchor witness. Armed only in real peer mode: a configured multi-node cluster over
        // the shared TCP transport (tcpTransport != null). Single-node and sharding-on-one-node have no
        // peers, so the witness stays INERT and the vote path is byte-identical to having no witness at
        // all. The BOOT gate is ALWAYS strict (peer-majority) - it closes the boot-reply race at N=3 and
        // only costs a node rebooting into a partition, NOT a running survivor, so single-fault leader
        // failover is preserved. VOTE deferral is the opt-in (witnessStrictEnabled(),
        // -Dconfigd.raft.witnessStrict=true) - the N>=5 absolute close, kept opt-in because deferring
        // voteGranted breaks 3-node failover. The fail-closed rollback handler here logs + halts; it is
        // upgraded after the audit log is built (see the arming loop in start()) to ALSO write an
        // {action=anchor.rollback.detected} audit record before halting.
        if (tcpTransport != null) {
            raftNode.armAnchorWitness(witnessStrictEnabled(),
                    (g, bootSeq, witnessedSeq, reportingPeer) -> {
                        LOG.log(Level.SEVERE, "anchor rollback detected for raft group " + g
                                + ": booted from anchorSeq=" + bootSeq + " but peer " + reportingPeer
                                + " witnessed anchorSeq=" + witnessedSeq + " (> booted) - refusing to start"
                                + " this shard (R-a' fail-closed: a within-term vote rollback could"
                                + " double-vote and split-brain)");
                        Runtime.getRuntime().halt(71);
                    });
        }

        // Group commit (per group): dispatch the flush onto the group's CURRENT owner via the driver
        // (rehoming-aware; DORMANT in prod -> always the static floorMod owner). Identical to the prior
        // captured-executor dispatch at gid 0.
        if (groupCommitEnabled) {
            raftNode.setGroupCommit(
                    (flush, delayMicros) -> driver.dispatchFlush(groupId, flush, delayMicros),
                    groupCommitMaxBatch, groupCommitLingerMicros);
        }

        return new RaftGroupRuntime(groupId, groupStorage, raftLog, configStore, stateMachine,
                raftNode, adapter, coalescingTransport);
    }

    /**
     * The SHARD-AWARE {@link ConfigReadService.ConfigReader}. A point read resolves the shard that OWNS
     * the key ({@code shardMap.shardFor(readScope, key)}) and reads THAT shard's {@code configStore}; a
     * {@code getPrefix} SCATTER-GATHERS across all shards (a prefix's keys may hash to different shards)
     * and merges; {@code currentVersion} is the max across shards (the per-key version still comes from
     * {@code ReadResult.version()}, which is per-shard correct). At {@code N=1} every key resolves to
     * group 0 -> the single store. Package-private static so {@code ShardedRoutingTest} can drive the
     * real read routing.
     *
     * <p>The scope-aware {@code get(scope, key)} overrides route on the caller's <em>per-request</em>
     * scope (read-your-writes - a GET of {@code (scope, key)} hits the same shard the write used). The
     * legacy key-only {@code get(key)} overloads route on {@code readScope}.
     *
     * @param readScope the default scope for the legacy key-only {@code ConfigReader.get(String)}
     *                  path (production: {@code GLOBAL}); the scope-aware overloads use the caller's scope
     */
    static ConfigReadService.ConfigReader shardedConfigReader(
            StaticShardMap shardMap, Map<Integer, RaftGroupRuntime> runtimesByGid,
            List<RaftGroupRuntime> runtimes, ConfigScope readScope) {
        return new ConfigReadService.ConfigReader() {
            private VersionedConfigStore storeFor(ConfigScope scope, String key) {
                RaftGroupRuntime rt = runtimesByGid.get(shardMap.shardFor(scope, key));
                return (rt != null ? rt : runtimes.get(0)).configStore();
            }
            // Legacy key-only reads route on readScope (the GLOBAL default the server wires).
            @Override public io.configd.store.ReadResult get(String key) { return storeFor(readScope, key).get(key); }
            @Override public io.configd.store.ReadResult get(String key, long minVersion) {
                return storeFor(readScope, key).get(key, minVersion);
            }
            // Scope-aware reads route on the caller's per-request scope, so a GET of (scope, key)
            // resolves the SAME shard the write of (scope, key) used (read-your-writes).
            @Override public io.configd.store.ReadResult get(ConfigScope scope, String key) {
                return storeFor(scope, key).get(key);
            }
            @Override public io.configd.store.ReadResult get(ConfigScope scope, String key, long minVersion) {
                return storeFor(scope, key).get(key, minVersion);
            }
            @Override public Map<String, io.configd.store.ReadResult> getPrefix(String prefix) {
                if (runtimes.size() == 1) {
                    return runtimes.get(0).configStore().getPrefix(prefix); // single shard
                }
                // Scatter-gather across shards; a prefix's keys may live on different shards.
                Map<String, io.configd.store.ReadResult> merged = new java.util.LinkedHashMap<>();
                for (RaftGroupRuntime rt : runtimes) {
                    merged.putAll(rt.configStore().getPrefix(prefix));
                }
                return merged;
            }
            @Override public long currentVersion() {
                long max = 0L;
                for (RaftGroupRuntime rt : runtimes) {
                    max = Math.max(max, rt.configStore().currentVersion());
                }
                return max;
            }
        };
    }

    /**
     * The per-shard fan-out runtime - one {@link FanOutBuffer} and one {@link Compactor} per shard,
     * returned keyed by group id. The primary group's entries (gid {@link #DEFAULT_RAFT_GROUP}) are the
     * home for the not-yet-sharded edge endpoint and the server's
     * {@code fanOutBuffer()}/{@code compactor()}/{@code replaySource()} accessors.
     *
     * @param buffers    gid -&gt; that shard's {@link FanOutBuffer} (the {@link CommitNotificationSource})
     * @param compactors gid -&gt; that shard's snapshot-retention {@link Compactor}
     */
    record ShardedFanOut(Map<Integer, FanOutBuffer> buffers, Map<Integer, Compactor> compactors) {
    }

    /**
     * Builds the PER-SHARD fan-out and registers each group's commit listener. For every shard it
     * creates a {@link FanOutBuffer} (sharing the aggregate {@code fanout.buffer.dropped} counter) + a
     * {@link Compactor}, and registers a {@link ConfigStateMachine.ConfigChangeListener} on THAT group's
     * state machine that, on each mutating apply, builds the {@link ConfigDelta} (with the
     * signature/epoch/nonce when present), publishes a {@link CommitNotification} (seq = the shard's
     * applied-mutation version; commit timestamp = the leader wall clock captured on the apply thread)
     * to THAT shard's buffer, and adds the shard's snapshot to THAT shard's compactor.
     *
     * <p><b>Thread-safety:</b> a group's apply -&gt; {@code notifyListeners} runs only on that group's
     * owner thread, so each per-shard buffer/compactor has exactly ONE writer - the
     * {@link FanOutBuffer} single-writer invariant holds per shard with NO lock, even when several
     * groups share an owner thread (they write their distinct buffers serially on that one thread). The
     * shared dropped counter is {@code LongAdder}-backed (thread-safe). The listener touches only its
     * own group's state machine + store - no cross-group {@link RaftNode} access.
     *
     * <p><b>N=1 byte-identity:</b> at a single shard this builds exactly one buffer + compactor for the
     * primary group and registers exactly the prior single fan-out listener.
     *
     * <p>Per-shard sequences + cursor vector; NO fabricated cross-shard global order. Package-private
     * static so {@code ShardedFanOutTest} can drive it directly without standing up a whole server.
     *
     * @param runtimes       the per-shard runtimes (each supplies its group id, state machine, and store)
     * @param clock          the wall clock for the commit timestamp
     * @param droppedCounter the aggregate {@code fanout.buffer.dropped} counter (shared across shards)
     * @param bufferCapacity the per-shard ring capacity ({@link #FANOUT_BUFFER_CAPACITY})
     * @return the per-shard {@link ShardedFanOut} (buffers + compactors), keyed by group id
     */
    static ShardedFanOut registerShardedFanOut(
            List<RaftGroupRuntime> runtimes, Clock clock,
            MetricsRegistry.Counter droppedCounter, int bufferCapacity) {
        Map<Integer, FanOutBuffer> buffers = new java.util.LinkedHashMap<>();
        Map<Integer, Compactor> compactors = new java.util.LinkedHashMap<>();
        for (RaftGroupRuntime rt : runtimes) {
            // Defensive (mirrors the primary-selection guard): a duplicate gid would orphan a buffer
            // whose listener writes a buffer no accessor exposes. Unreachable with StaticShardMap's
            // unique [0,N), but fail loud if a future ShardMap returns a duplicate.
            if (buffers.containsKey(rt.groupId())) {
                throw new IllegalStateException(
                        "duplicate shard group id " + rt.groupId() + " in the bring-up runtimes — each"
                                + " group must have exactly one fan-out buffer");
            }
            FanOutBuffer fanOut = new FanOutBuffer(bufferCapacity, droppedCounter::increment);
            Compactor shardCompactor = new Compactor();
            buffers.put(rt.groupId(), fanOut);
            compactors.put(rt.groupId(), shardCompactor);
            // Capture THIS group's state machine + store (final per-iteration so the listener binds to its
            // own shard, never a loop-variable race).
            ConfigStateMachine sm = rt.stateMachine();
            VersionedConfigStore store = rt.configStore();
            sm.addListener((mutations, version) -> {
                long fromVersion = version - 1;
                byte[] signature = sm.lastSignature();
                // Bind the monotonic epoch + nonce into the signature so edges can reject replays.
                long epoch = sm.lastEpoch();
                byte[] nonce = sm.lastNonce();
                ConfigDelta delta;
                if (signature != null && nonce != null) {
                    delta = new ConfigDelta(fromVersion, version, mutations, signature, epoch, nonce);
                } else {
                    delta = new ConfigDelta(fromVersion, version, mutations, signature);
                }
                // `version` is this shard's applied-mutation seq S (the listener fires only on mutating
                // applies). The commit timestamp is the leader's wall clock on the apply thread, captured
                // per shard.
                long commitTimestampMillis = clock.currentTimeMillis();
                fanOut.publish(new CommitNotification(version, commitTimestampMillis, delta));
                shardCompactor.addSnapshot(store.snapshot());
            });
        }
        // Order-preserving immutable views (deterministic compact-rider / debug iteration by gid).
        return new ShardedFanOut(
                java.util.Collections.unmodifiableMap(buffers),
                java.util.Collections.unmodifiableMap(compactors));
    }

    /**
     * Registers the PER-SHARD health gauges. For every shard it publishes
     * {@code raft.shard.{commit_index,last_applied,apply_lag,current_term,leader}.<gid>} plus the
     * node-level {@code raft.node.leader_count}. Each gauge is pull-based and reads the group's
     * {@link RaftNode#monitorView()} - a safe, never-torn, &lt;= one-tick-stale snapshot the Prometheus
     * scrape thread reads off-owner. Null-safe: a removed/absent group reads {@code 0}. At {@code N=1}
     * this registers exactly the group-0 series - purely additive (the existing series are untouched).
     * Package-private static so {@code PerShardMetricsTest} can drive it directly.
     */
    static void registerPerShardMetrics(MetricsRegistry registry, MultiRaftDriver driver,
            List<RaftGroupRuntime> runtimes) {
        for (RaftGroupRuntime rt : runtimes) {
            int gid = rt.groupId();
            registry.gauge("raft.shard.commit_index." + gid, shardGauge(driver, gid, RaftMetrics::commitIndex));
            registry.gauge("raft.shard.last_applied." + gid, shardGauge(driver, gid, RaftMetrics::lastApplied));
            registry.gauge("raft.shard.apply_lag." + gid,
                    shardGauge(driver, gid, v -> Math.max(0L, v.commitIndex() - v.lastApplied())));
            registry.gauge("raft.shard.current_term." + gid, shardGauge(driver, gid, RaftMetrics::currentTerm));
            registry.gauge("raft.shard.leader." + gid,
                    shardGauge(driver, gid, v -> v.role() == RaftRole.LEADER ? 1L : 0L));
            // The wedge/saturation signals for this shard: max replication lag across peers (the
            // "follower stuck / snapshot could not install" proxy) plus the three otherwise-silent
            // codec-reject / reassembly-refuse drop tallies. Monotonic-count gauges read off the same
            // monitorView snapshot; register(increase()) over them behaves like a counter.
            registry.gauge("raft.shard.replication_lag_max." + gid,
                    shardGauge(driver, gid, RaftMetrics::replicationLagMax));
            registry.gauge("raft.shard.append_send_rejected." + gid,
                    shardGauge(driver, gid, RaftMetrics::appendSendRejected));
            registry.gauge("raft.shard.snapshot_chunk_send_rejected." + gid,
                    shardGauge(driver, gid, RaftMetrics::snapshotChunkSendRejected));
            registry.gauge("raft.shard.snapshot_reassembly_refused." + gid,
                    shardGauge(driver, gid, RaftMetrics::snapshotReassemblyRefused));
        }
        // Node-level: how many shards THIS node currently leads (the leader-count-per-node view).
        registry.gauge("raft.node.leader_count", () -> {
            long leaders = 0L;
            for (RaftGroupRuntime rt : runtimes) {
                RaftNode node = driver.getGroup(rt.groupId());
                if (node != null && node.monitorView().role() == RaftRole.LEADER) {
                    leaders++;
                }
            }
            return leaders;
        });
    }

    /**
     * Evaluates shard-aware readiness. Package-private static so {@code ReadinessDrainTest} can drive
     * the decision directly with a stub leader source and a draining flag - no server, no RaftNode.
     *
     * <p>Order matters: {@code draining} is checked FIRST, so once {@link #shutdown()} flips the flag the
     * node reports NOT-ready regardless of shard state (an LB then stops routing while in-flight work
     * drains). When not draining, the node is ready only if EVERY hosted group has a known leader; the
     * first group without one (quorum lost, or no leader yet elected) makes the whole node NOT-ready and
     * names the offending shard.
     *
     * @param draining     whether shutdown has begun draining (readiness must report NOT-ready)
     * @param hostedGroups the group ids this node hosts (all N shards under static-N)
     * @param leaderOf     resolves a group's known leader, or {@code null} if none (quorum lost/unknown)
     * @return a healthy result named {@code raft-leader} when ready; otherwise an unhealthy result naming
     *         {@code draining} or {@code raft-leader} with the reason
     */
    static HealthService.CheckResult evaluateReadiness(
            boolean draining, int[] hostedGroups, java.util.function.IntFunction<NodeId> leaderOf) {
        if (draining) {
            return HealthService.CheckResult.unhealthy("draining", "server is draining for shutdown");
        }
        for (int gid : hostedGroups) {
            if (leaderOf.apply(gid) == null) {
                return HealthService.CheckResult.unhealthy(
                        "raft-leader", "shard " + gid + " has no known leader");
            }
        }
        return HealthService.CheckResult.healthy("raft-leader");
    }

    /**
     * Registers the node-level consensus-transport saturation gauges - outbound {@code frames_dropped}
     * (bounded-queue overflow / no-connection drop) and inbound {@code connections_refused} (admission
     * cap) - as pull gauges over the endpoint's monotonic accessors. These are counted inside the
     * transport (JDK or Netty) but were never exported. Registered only when a consensus transport is
     * configured; the accessors are cheap volatile reads, safe on the scrape thread. Package-private
     * static so {@code PerShardMetricsTest} can drive it directly with a stub endpoint.
     */
    static void registerTransportSaturationGauges(MetricsRegistry registry, RaftTransportEndpoint endpoint) {
        registry.gauge(ConfigdMetrics.NAME_RAFT_TRANSPORT_FRAMES_DROPPED, endpoint::framesDropped);
        registry.gauge(ConfigdMetrics.NAME_RAFT_TRANSPORT_INBOUND_CONNECTIONS_REFUSED,
                endpoint::inboundConnectionsRefused);
    }

    /** A null-safe per-shard gauge: reads {@code fn} off the group's {@link RaftNode#monitorView()}, or
     *  {@code 0} if the group is absent. The monitorView read is the safe off-owner snapshot. */
    private static java.util.function.LongSupplier shardGauge(
            MultiRaftDriver driver, int gid, java.util.function.ToLongFunction<RaftMetrics> fn) {
        return () -> {
            RaftNode node = driver.getGroup(gid);
            return node != null ? fn.applyAsLong(node.monitorView()) : 0L;
        };
    }

    /**
     * Handles an unhandled throwable that escaped the tick loop body. Package-private static so the
     * regression test ({@code TickLoopThrowableHandlerTest}) can drive it directly without standing up
     * an entire {@link ConfigdServer} + scheduler - the catch block in {@code start()} is a one-line
     * call into this method.
     *
     * <p>Two visible side-effects (both load-bearing for SRE alerting):
     * <ol>
     *   <li>Increments {@code configd_tick_loop_throwable_total{class}} via
     *       {@link ConfigdMetrics#onTickLoopThrowable(String)}; the {@code class}
     *       label is {@link SafeLog#cardinalityGuard cardinality-bounded} so a
     *       hostile-input throwable family cannot blow up the series count.</li>
     *   <li>Emits a SEVERE log record with the throwable attached so the JUL formatter prints the
     *       stack trace, instead of a bare {@code printStackTrace(System.err)} that would be
     *       invisible to centralized log aggregation.</li>
     * </ol>
     *
     * <p>Defensive: a {@code null} throwable is treated as {@code class="unknown"} rather than NPE -
     * the handler must NOT itself become a new tick-loop killer.
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
     * Builds the inbound Raft message handler that marshals routing onto the Raft executor, so
     * {@code node.handleMessage} - and the {@code applyCommitted -> stateMachine.apply} it can trigger -
     * never runs concurrently with {@code node.tick()} on the explicitly non-synchronized
     * {@link io.configd.raft.RaftNode}.
     * <p>
     * Package-private static so the regression/stress test can exercise the real marshalling decision
     * directly without standing up a whole server. Reverting this to a direct
     * {@code driver.routeMessage(...)} call (dropping {@code raftExecutor.execute}) reintroduces the
     * race and makes that test fail.
     */
    static java.util.function.BiConsumer<NodeId, RaftMessage> raftInboundHandler(
            MultiRaftDriver driver, int groupId, java.util.concurrent.Executor raftExecutor) {
        return raftInboundHandler(driver, groupId, raftExecutor, null);
    }

    /**
     * Same marshalling as above, but the routing task is wrapped so a Throwable escaping
     * {@code driver.routeMessage} (e.g. a disk write failing during {@code applyCommitted -> apply} on a
     * follower) is SURFACED via {@link #handleInboundRoutingThrowable} - a counter + structured SEVERE
     * log - instead of being swallowed by the executor (which sends it to the worker's default uncaught
     * handler / stderr, invisible to log aggregation, with no metric, and drops the message with no ack).
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
     * The N-group inbound DEMULTIPLEXER. Routes each decoded frame to ITS group - resolving the group's
     * owner executor from {@code frame.groupId()} and delegating to the tested fixed-group marshalling
     * primitive ({@link #raftInboundHandler}) - instead of collapsing every inbound message onto a
     * single captured group. The owner executor is re-resolved PER MESSAGE
     * ({@code driver.ownerExecutor(groupId)}), so it always targets the group's CURRENT owner. A frame
     * for an unregistered group is dropped safely by {@code driver.routeMessage} (absent group -> no-op).
     *
     * <p>At {@code N=1} only group 0 is registered and every frame carries groupId 0, so this resolves to
     * {@code raftInboundHandler(driver, 0, ownerExecutor(0), metrics)} on every message. Package-private
     * static so {@code RaftInboundDemuxTest} can drive the real routing decision directly.
     */
    static RaftTransportAdapter.InboundHandler raftDemuxInboundHandler(
            MultiRaftDriver driver, ConfigdMetrics metrics) {
        return (from, groupId, message) -> {
            // DROP a frame for an UNREGISTERED group on the inbound (Netty) thread - BEFORE marshalling
            // it onto an owner executor. groupId is an attacker-influenceable field (the CRC32C is a
            // checksum, not a MAC), so an authenticated-but-hostile peer could otherwise spam bogus /
            // out-of-range gids to enqueue unbounded no-op routeMessage tasks on an owner thread.
            // getGroup is a thread-safe ConcurrentHashMap read; driver.routeMessage re-checks
            // (absent group -> drop) as the backstop. At N=1 only group 0 is registered, so every legit
            // frame (gid 0) proceeds and every other gid is dropped here.
            if (driver.getGroup(groupId) == null) {
                return;
            }
            raftInboundHandler(driver, groupId, driver.ownerExecutor(groupId), metrics)
                    .accept(from, message);
        };
    }

    /**
     * Frames one owner's per-peer heartbeat drain. Exactly one group for the peer (ALWAYS the case at
     * N=1) -> a normal {@link MessageType#APPEND_ENTRIES} frame, so the wire is byte-for-byte unchanged;
     * more than one group (only at N&gt;1) -> ONE {@link MessageType#RAFT_COALESCED_HEARTBEAT} frame the
     * receiver demuxes. Package-private static so {@code HeartbeatDrainFramingTest} can exercise both
     * branches without standing up a server.
     *
     * @param groupHeartbeats the per-group {@code groupId -> empty AppendEntriesRequest} (&gt;=1 entry)
     * @return the frame to send to the peer
     */
    static FrameCodec.Frame frameHeartbeatDrain(Map<Integer, AppendEntriesRequest> groupHeartbeats) {
        if (groupHeartbeats.size() == 1) {
            Map.Entry<Integer, AppendEntriesRequest> hb = groupHeartbeats.entrySet().iterator().next();
            return RaftMessageCodec.encode(hb.getValue(), hb.getKey());
        }
        return RaftMessageCodec.encodeCoalescedHeartbeat(groupHeartbeats);
    }

    /**
     * Handles a Throwable that escaped {@code driver.routeMessage} on the inbound-routing task.
     * Mirrors {@link #handleTickLoopThrowable}: a cardinality-bounded
     * {@code configd_inbound_routing_throwable_total{class}} counter increment plus a SEVERE log record
     * with the throwable attached. Package-private static so the regression test
     * ({@code InboundRoutingThrowableHandlerTest}) can drive it directly. Defensive against a null
     * throwable / null metrics - the handler must never itself become a new failure source.
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
     * Builds the commit-confirmed write proposer.
     * <p>
     * A SINGLE marshalled tick task performs {@code driver.propose} AND, on acceptance, registers
     * {@code whenCommitOutcome(index, term, cb)} on the owning {@link io.configd.raft.RaftNode} -
     * atomically, in the same task, capturing {@code (index, term)} <em>inside</em> the task (a slow
     * tick queue must not lose the position; the at-least-once append must not force Indeterminate when
     * the entry actually appended). All node mutation (propose, the apply it may trigger, and the
     * registration/firing) stays on the single tick thread; the tick thread never waits on the HTTP
     * future (registration is fire-and-return, like {@code whenReadReady}).
     * <p>
     * The calling (HTTP write) thread blocks on ONE end-to-end {@code writeCommitTimeoutMs} deadline
     * (real milliseconds, not a tick count). On commit it returns {@code Committed(seq)}; on definite
     * loss {@code Lost}; on pre-append rejection {@code NotLeader}/{@code Overloaded}; on deadline
     * expiry {@code Indeterminate}, after dispatching a tick-thread cleanup that cancels the
     * now-abandoned one-shot callback (no map-entry leak; no double-complete).
     * <p>
     * Package-private static so the regression test can drive the real marshalling decision; reverting
     * to a direct {@code driver.propose(...)} call reintroduces the write-vs-tick race.
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

    /**
     * Fixed-group proposer (tests / single-group wiring): every write routes to {@code groupId} on
     * {@code raftExecutor}, ignoring the keys for routing (one group = trivially single-shard, so the
     * cross-shard guard never fires). Commit-confirmed semantics + write-commit metrics as the production
     * overload. Package-private so the marshalling/commit regression tests can drive the real seam with
     * their own executor.
     */
    static ConfigWriteService.RaftProposer raftProposer(
            MultiRaftDriver driver, int groupId,
            java.util.concurrent.Executor raftExecutor, long writeCommitTimeoutMs,
            ConfigdMetrics metrics) {
        return buildProposer(driver, writeCommitTimeoutMs, metrics,
                (scope, keys) -> new Routed(groupId, raftExecutor));
    }

    /**
     * The PRODUCTION shard-routing proposer. Each write is routed to the shard that owns its key(s) via
     * {@link CrossShardWriteGuard#requireSingleShard} - ONE call that is both the router (single key ->
     * {@code shardFor(scope, key)}) AND the DISCLAIM guard (multi-key keys spanning shards ->
     * {@link ConfigWriteService.ProposeCommitResult.CrossShardRejected}, before any Raft work). The owner
     * executor is re-resolved per write from the resolved gid ({@code driver.ownerExecutor(gid)} -
     * rehoming-aware). At {@code N=1} every key resolves to group 0.
     */
    static ConfigWriteService.RaftProposer raftProposer(
            MultiRaftDriver driver, StaticShardMap shardMap, long writeCommitTimeoutMs,
            ConfigdMetrics metrics) {
        return buildProposer(driver, writeCommitTimeoutMs, metrics,
                (scope, keys) -> {
                    int gid = CrossShardWriteGuard.requireSingleShard(shardMap, scope, keys);
                    return new Routed(gid, driver.ownerExecutor(gid));
                });
    }

    /** The (gid, owner-executor) a write was routed to. */
    private record Routed(int gid, java.util.concurrent.Executor executor) {}

    /** Resolves a write's owning shard + owner executor, or throws {@code CrossShardBatchException}. */
    @FunctionalInterface
    private interface WriteRouter {
        Routed route(ConfigScope scope, List<String> keys);
    }

    /**
     * The commit-confirmed proposer core, parameterized by a {@link WriteRouter} (fixed-group for tests;
     * shard-routing for production). A SINGLE marshalled task performs {@code driver.propose(gid, ...)}
     * AND, on acceptance, registers {@code whenCommitOutcome(index, term, cb)} on the owning
     * {@link io.configd.raft.RaftNode} - capturing {@code (index, term)} INSIDE the task. All node
     * mutation stays on the group's owner thread; the HTTP write thread blocks on ONE end-to-end
     * {@code writeCommitTimeoutMs} deadline and gets a commit-confirmed answer
     * (Committed/Lost/NotLeader/Indeterminate/Overloaded/CrossShardRejected).
     */
    private static ConfigWriteService.RaftProposer buildProposer(
            MultiRaftDriver driver, long writeCommitTimeoutMs, ConfigdMetrics metrics,
            WriteRouter router) {
        // Admission control: bound the proposals concurrently in-flight so a sustained write flood cannot
        // starve the periodic heartbeat. Excess is shed as Overloaded (-> 429 + Retry-After) on the HTTP
        // thread BEFORE the proposal reaches the executor. ON by default at DEFAULT_MAX_INFLIGHT_PROPOSALS;
        // -Dconfigd.write.maxInflightProposals=N tunes it, 0 disables. The permit is held only for
        // the bounded wait.
        int maxInflightProposals = ConfigSource.system()
                .getInt("configd.write.maxInflightProposals", DEFAULT_MAX_INFLIGHT_PROPOSALS);
        java.util.concurrent.Semaphore admission =
                maxInflightProposals > 0 ? new java.util.concurrent.Semaphore(maxInflightProposals) : null;
        return (scope, keys, command) -> {
            // End-to-end write-commit latency is measured HERE (HTTP write thread, off the tick hot path)
            // from request entry to outcome - the true "write commit p99" SLO signal, NOT the apply
            // duration. Recorded via recordWriteOutcome.
            long t0 = System.nanoTime();
            // Route + cross-shard guard FIRST (fail-fast, before admission / any Raft work). A multi-key
            // write whose keys span shards is rejected here (DISCLAIM) - no permit consumed.
            final int groupId;
            final java.util.concurrent.Executor raftExecutor;
            try {
                Routed routed = router.route(scope, keys);
                groupId = routed.gid();
                raftExecutor = routed.executor();
            } catch (CrossShardBatchException | IllegalArgumentException e) {
                // CrossShardBatchException = keys span shards (DISCLAIM). IllegalArgumentException =
                // an empty key list (requireSingleShard contract). Both are pre-Raft validation failures
                // surfaced as a clean ValidationFailed (HTTP 400), never a 500. Defensive for a future
                // multi-key BATCH path - the single-key HTTP write path always passes one key.
                return new ConfigWriteService.ProposeCommitResult.CrossShardRejected(e.getMessage());
            }
            if (admission != null && !admission.tryAcquire()) {
                // In-flight bound reached -> graceful shed. This path creates NO executor task,
                // so the heartbeat is never queued behind a flood - the leader stays stable.
                ConfigWriteService.ProposeCommitResult shed =
                        new ConfigWriteService.ProposeCommitResult.Overloaded();
                recordWriteOutcome(metrics, shed, System.nanoTime() - t0);
                return shed;
            }
            java.util.concurrent.CompletableFuture<ConfigWriteService.ProposeCommitResult> f =
                    new java.util.concurrent.CompletableFuture<>();
            // Captured inside the marshalled task so the timeout path can cancel
            // the exact pending callback on the owner thread (mirrors the read
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
                            // definite, redirect-and-retry - surfaced as NotLeader.
                            default -> new ConfigWriteService.ProposeCommitResult.NotLeader();
                        });
                        return;
                    }
                    long index = outcome.index();
                    indexRef.set(index);
                    io.configd.raft.RaftNode node = driver.getGroup(groupId);
                    if (node == null) {
                        // Group vanished between propose and registration - treat as
                        // indeterminate (the append may still commit elsewhere).
                        f.complete(new ConfigWriteService.ProposeCommitResult.Indeterminate());
                        return;
                    }
                    // Register the one-shot commit-outcome callback atomically with
                    // the accepted append, on the owner thread. Fires inline if the
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
                // one-shot callback on the owner thread so its map entry cannot leak
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
                // Admission control: release the permit when the HTTP thread finishes waiting
                // (commit, loss, timeout, or error). No-op when admission control is disabled.
                if (admission != null) {
                    admission.release();
                }
            }
        };
    }

    /**
     * Records the end-to-end write outcome on the HTTP write thread. Latency is recorded ONLY for a
     * confirmed commit (a failure/redirect would skew the latency histogram); the counters partition
     * outcomes for the control-plane availability SLO and the sustained-429-rate alert.
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
     * Best-effort teardown of the long-lived resources a partial boot created, run in reverse
     * creation order (LIFO) when {@link #start(ServerConfig, ConfigSource)} fails. Each action
     * swallows its own failure so one stuck close neither masks the others nor the original boot
     * failure that triggered the teardown.
     */
    private static void closeBootResources(java.util.Deque<Runnable> bootTeardown) {
        while (!bootTeardown.isEmpty()) {
            try {
                bootTeardown.pop().run();
            } catch (Throwable t) {
                System.err.println("WARNING: boot-failure cleanup step threw (continuing): " + t);
            }
        }
    }

    public MultiRaftDriver driver() {
        return driver;
    }

    /**
     * Returns the underlying consensus transport (a {@link NettyRaftTransport}; the JDK
     * {@link TcpRaftTransport} is the fast-revert) when peer addresses were configured, or
     * {@code null} for single-node / test mode.
     * <p>
     * Exposed so integration tests can verify that the transport holds a non-null
     * {@link TlsManager} when TLS is enabled.
     */
    public RaftTransportEndpoint tcpTransport() {
        return tcpTransport;
    }

    public ConfigStateMachine stateMachine() {
        return stateMachine;
    }

    public ServerConfig config() {
        return config;
    }

    public WatchService watchService() {
        return watchService;
    }

    /**
     * Returns the fan-out buffer for delta distribution. At {@code N>1} this is the PRIMARY shard's
     * buffer only; the per-shard sources (one buffer per shard) are the cursor-vector view the
     * multi-shard-aware edge client consumes. At {@code N=1} it is the single buffer.
     */
    public FanOutBuffer fanOutBuffer() {
        return fanOutBuffer;
    }

    /**
     * The fan-out edge endpoint, or {@code null} when {@code --edge-port} is absent. Exposed for tests
     * and operational checks.
     */
    public io.configd.server.fanout.FanOutEndpoint fanOutServer() {
        return fanOutServer;
    }

    /** The actual bound HTTP API port (resolves an ephemeral {@code --api-port 0}). */
    public int apiPort() {
        return httpApiServer.port();
    }

    /**
     * Renders the current Prometheus exposition text (identical content to the live {@code /metrics}
     * endpoint, via the same exporter wired with the SLO histogram schedules). Exposed so a contract
     * test can assert the running server emits the SLO series with REAL data.
     */
    String scrapeMetrics() {
        return prometheusExporter.export();
    }

    /**
     * The commit-notification boundary the data plane consumes. Backed by {@link #fanOutBuffer()} (the
     * bounded hot-path cache); cursor-based, replayable, with the drop-oldest overflow contract. At
     * {@code N>1} this is the PRIMARY shard only (per-shard sources are the cursor-vector view the
     * multi-shard-aware client uses).
     */
    public CommitNotificationSource commitNotificationSource() {
        return fanOutBuffer;
    }

    /**
     * The authoritative recovery seam a consumer replays from on a
     * {@link CommitNotificationSource#readSince(long)} GAP. A snapshot-equivalent replay over the live
     * config store. At {@code N>1} this is the PRIMARY shard's store only; each shard's per-shard replay
     * is derived on demand from its own {@code configStore()::snapshot} (the per-shard cursor vector the
     * multi-shard-aware edge client uses).
     */
    public ReplaySource replaySource() {
        return new SnapshotReplaySource(stateMachine.store()::snapshot);
    }

    public Compactor compactor() {
        return compactor;
    }

    public PlumtreeNode plumtreeNode() {
        return plumtreeNode;
    }

    public HyParViewOverlay hyParViewOverlay() {
        return hyParViewOverlay;
    }

    public SubscriptionManager subscriptionManager() {
        return subscriptionManager;
    }

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

    /**
     * Resolves the optional YAML config file and builds the boot {@link ConfigSource}. The file is named
     * by {@code --config <path>}, else the {@code configd.config.file} system property, else the
     * {@code CONFIGD_CONFIG} environment variable. When none names a file the source is
     * {@link ConfigSource#system()} (system properties over environment, NO YAML layer) - byte-identical
     * to the behavior before configuration was unified. A named-but-unreadable / malformed file fails the
     * boot ({@link ConfigException}). The YAML layer always sits BELOW system properties and the
     * environment, so every {@code -D} and env override still wins.
     *
     * @param args the command-line arguments (scanned for {@code --config})
     * @return the boot configuration source
     */
    static ConfigSource loadBootConfig(String[] args) {
        String path = null;
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--config".equals(args[i])) {
                path = args[i + 1];
                break;
            }
        }
        if (path == null || path.isBlank()) {
            path = System.getProperty("configd.config.file");
        }
        if (path == null || path.isBlank()) {
            path = System.getenv("CONFIGD_CONFIG");
        }
        if (path == null || path.isBlank()) {
            return ConfigSource.system();
        }
        ConfigSource yaml = io.configd.server.config.YamlConfigSource.fromFile(Path.of(path));
        return LayeredConfigSource.of(new SystemPropertyConfigSource(), new EnvConfigSource(), yaml);
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: configd-server --node-id <id> --data-dir <path> --peers <id,id,...>"
                    + " [--bind-address <addr>] [--bind-port <port>] [--api-port <port>]"
                    + " [--tls-cert <path>] [--tls-key <path>] [--tls-trust-store <path>]"
                    + " [--auth-token <token>] [--config <path>]");
            System.exit(1);
        }

        ServerConfig config;
        ConfigSource cfg;
        try {
            config = ServerConfig.parse(args);
            cfg = loadBootConfig(args);
        } catch (IllegalArgumentException | ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }

        printBanner(config);

        ConfigdServer server;
        try {
            server = start(config, cfg);
        } catch (RuntimeException | Error e) {
            // A fail-closed boot (missing auth-provider module, unreachable IdP during auth-chain
            // build, port already in use, TLS-without-manager) escapes here. start() has already
            // closed whatever it created, but exit UNCONDITIONALLY with a non-zero code: without
            // this the main thread dies while non-daemon Netty event loops keep the JVM up, so the
            // process prints a stack trace and hangs, bound but serving nothing. A clean non-zero
            // exit lets an orchestrator restart or alert instead.
            System.err.println("FATAL: Configd server failed to start: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return; // unreachable - System.exit does not return; keeps `server` definitely-assigned
        }

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
