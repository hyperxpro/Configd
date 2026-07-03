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
import io.configd.common.IntegrityEnvelope;
import io.configd.common.NodeId;
import io.configd.common.SegmentKeyManager;
import io.configd.common.Storage;
import io.configd.common.kms.KmsProvider;
import io.configd.common.kms.KmsUnavailableException;
import io.configd.common.kms.LocalDerivedKmsProvider;
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
import io.configd.raft.AppendEntriesRequest;
import io.configd.raft.CoalescingRaftTransport;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftMetrics;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.raft.RaftTransport;
import io.configd.raft.RaftMessage;
import io.configd.raft.ProposeOutcome;
import io.configd.replication.CrossShardBatchException;
import io.configd.replication.CrossShardWriteGuard;
import io.configd.replication.MultiRaftDriver;
import io.configd.replication.OwnerExecutorPool;
import io.configd.replication.StaticShardMap;
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
import io.configd.transport.RaftTransportEndpoint;
import io.configd.transport.TcpRaftTransport;
import io.configd.transport.TlsConfig;
import io.configd.transport.TlsManager;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
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
     * the structured-log path replaces the historical
     * {@code printStackTrace(System.err)} silent-failure mode.
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
     * Static-N ceiling: the maximum number of shards a single deploy may configure via
     * {@code configd.raft.shardCount}. Around 10-11 leaders saturate a 16-vCPU node, so N<=16 across a
     * few nodes is a sane upper bound without core overcommit. N is fixed at deploy; v2 adds dynamic
     * resharding. Default is {@code 1} (single group, byte-identical to the single-shard path).
     */
    private static final int MAX_SHARD_COUNT = 16;
    /** Marker file under the data dir recording the deploy-time shard count N (fixed-at-deploy guard). */
    private static final String SHARD_COUNT_MARKER = "raft-shard-count.meta";
    /**
     * Per-group RNG seed stride (the SplitMix64 / golden-ratio increment). Each group's RaftNode RNG is
     * seeded {@code nodeId*31 + gid*GID_RNG_STRIDE + nanoTime()} so the groups' election timeouts stagger
     * (correlated-election-storm mitigation). At {@code gid == 0} the stride term is 0, so the
     * seed formula is identical to the single-group seed.
     */
    private static final long GID_RNG_STRIDE = 0x9E3779B97F4A7C15L;
    private static final int COMPACTION_INTERVAL_TICKS = 1000; // every ~10 seconds
    // Applied entries a Raft group may retain past its snapshot point before the tick
    // loop triggers Raft-log compaction (distinct from the snapshot-retention Compactor above).
    // Bounds WAL growth - without this trigger compaction was unreachable in the wired server
    // (the only triggerSnapshot caller is the circular sendInstallSnapshot) and the WAL grew for
    // the life of the process, eventually crash-looping recovery at the FileStorage 2 GiB read cap.
    private static final long RAFT_LOG_COMPACTION_THRESHOLD = 10_000;
    private static final int TLS_RELOAD_INTERVAL_MS = 60_000;  // every 60 seconds
    private static final int FANOUT_BUFFER_CAPACITY = 10_000;
    // Single end-to-end commit-confirmation deadline for a write, in REAL milliseconds on the
    // outcome future (NOT a tick count - it must not route through a tick-config path). 5 s default,
    // chosen >> worst-case re-election.
    private static final long WRITE_COMMIT_TIMEOUT_MS = 5_000;

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
    private final OwnerExecutorPool ownerPool;
    private final ScheduledExecutorService readDispatchExecutor;
    private final ScheduledExecutorService tlsReloadExecutor;
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

    private ConfigdServer(ServerConfig config, MultiRaftDriver driver,
                          ConfigStateMachine stateMachine,
                          OwnerExecutorPool ownerPool,
                          ScheduledExecutorService readDispatchExecutor,
                          ScheduledExecutorService tlsReloadExecutor,
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
                          io.configd.observability.PrometheusExporter prometheusExporter) {
        this.config = config;
        this.driver = driver;
        this.stateMachine = stateMachine;
        this.ownerPool = ownerPool;
        this.readDispatchExecutor = readDispatchExecutor;
        this.tlsReloadExecutor = tlsReloadExecutor;
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

        // ---------------------------------------------------------------
        // Deploy-time shard count N (static-N sharding). Default 1 = a single Raft group (the common
        // case). N is config-derived (system property, consistent with the other `configd.raft.*`
        // tunables), validated to [1, MAX_SHARD_COUNT], and FIXED AT DEPLOY (see resolveShardCount).
        // The StaticShardMap routes (scope,key)->shard.
        // ---------------------------------------------------------------
        // N>1 with the edge endpoint boots. The fan-out coordinator serves multi-shard WATCH across all
        // N shards (one FanOutSessionCore per shard, (gid,S)-tagged cursor vector, per-shard resume). The
        // co-resident legacy whole-store SUBSCRIBE plane serves the primary shard only, so the fan-out
        // driver refuses a legacy SUBSCRIBE per connection at N>1 (BAD_SUBSCRIBE) unless the operator sets
        // -Dconfigd.edge.allowPartialShardView; a WATCH is never refused. See the
        // fanOutConfig.withAllowPartialShardView wiring below. At N=1 (one shard is the whole keyspace)
        // the refusal never fires - byte-identical.
        int shardCount = resolveShardCount(dataDir);
        StaticShardMap shardMap = new StaticShardMap(shardCount);
        System.out.println("  Shard map    : " + shardMap + " [Multi-Raft Phase 1 C4a; N fixed at deploy,"
                + " ceiling " + MAX_SHARD_COUNT + "]");

        // Initialize storage
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
            raftIntegrity = deriveRaftIntegrityEnvelope(keyStore, keyFile, dataDir);
            auditLogKey = deriveAuditLogKey(keyStore);
        } catch (SecurityException se) {
            // Fail-closed: surface the co-location refusal with its clear, actionable
            // message - do NOT wrap it as a generic "failed to load key" error.
            throw se;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load or create Ed25519 signing key", e);
        }

        // The config store + state machine are now PER-GROUP, built inside buildRaftGroup (one per
        // shard). At N=1 the single group 0 reuses the node-level `storage` instance below, so its
        // WAL/snapshot bytes are byte-identical. The singletons (fan-out/watch/read/write/http) bind
        // to the PRIMARY group's store/SM after the bring-up loop.

        // ---------------------------------------------------------------
        // Turn the runtime invariant safety net ON. Build the metrics registry + InvariantMonitor HERE
        // (before the state machine and Raft node) so BOTH are fed a REAL checker instead of NOOP. The
        // monitor shares this registry, so violations surface at /metrics (the PrometheusExporter reads
        // the same registry). Prod is fail-open: a violation increments a named metric + SEVERE log and
        // keeps serving (never throw in a running server). The two InvariantChecker SAMs (RaftNode's and
        // ConfigStateMachine's) both bridge to this monitor.
        // ---------------------------------------------------------------
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
        ConfigdMetrics configdMetrics =
                new ConfigdMetrics(metricsRegistry, pendingApplyEntries::get);

        // The per-group ConfigStateMachine is built in buildRaftGroup, fed THIS configdMetrics
        // via ServerStateMachineMetrics and the shared smInvariantChecker.

        // Initialize Raft with durable WAL storage. Pass the real scheduler tick period (TICK_PERIOD_MS)
        // so the documented millisecond budgets (150-300ms election timeout, 50ms heartbeat) are
        // converted to the correct tick counts and realized at runtime. Raft timing is
        // operator-tunable via system properties (defaults = the documented 150/300/50 ms). The
        // as-built ceiling is leadership-churn / heartbeat starvation under load, not fsync; a longer
        // election timeout and shorter heartbeat give more headroom for tick-thread scheduling jitter.
        int electionMinMs = Integer.getInteger("configd.raft.electionTimeoutMinMs", 150);
        int electionMaxMs = Integer.getInteger("configd.raft.electionTimeoutMaxMs", 300);
        int heartbeatMs = Integer.getInteger("configd.raft.heartbeatIntervalMs", 50);
        int maxInflight = Integer.getInteger("configd.raft.maxInflightAppends", 10);
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

        // ---------------------------------------------------------------
        // Wire TLS (must happen BEFORE TcpRaftTransport so Raft traffic
        // uses mTLS when --tls-* flags are supplied).
        //
        // Previously, the Raft transport was constructed with null TlsManager even when TLS was
        // enabled on the CLI, causing plaintext Raft traffic in production. TLS wiring is now lifted
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
            // the JDK TcpRaftTransport remains the documented fast-revert (git revert of this commit).
            //
            // Peer authentication: when TLS is enabled this is mTLS with client-auth
            // (NettyRaftTransport.newServerSslHandler sets needClientAuth=true; the client handler sets
            // EndpointIdentificationAlgorithm=HTTPS). So a frame's attacker-influenceable groupId is only
            // ever demultiplexed for an AUTHENTICATED peer - an unauthenticated/untrusted-cert peer cannot
            // complete the handshake, so its frames never reach the demux (proven by negative test).
            tcpTransport = new NettyRaftTransport(
                    config.nodeId(), bindAddr, peerAddresses, tlsManager, null);
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

        // ---------------------------------------------------------------
        // Create the owner-executor pool HERE - before wiring the transport - so the inbound Raft
        // handler can marshal onto the GROUP'S OWNER (`driver.ownerExecutor(gid)`), not a global alias.
        //   - ownerPool (N owners, default N=1 via `configd.raft.ownerPoolSize`): each group binds to
        //     `ownerExecutor(gid) = pool[gid % N]`; ALL of that group's OWNER-ONLY RaftNode work -
        //     per-owner tick, inbound handleMessage(), propose(), readIndex/flush - runs on its owner
        //     thread, so the unsynchronised RaftNode is only ever touched by one thread PER GROUP.
        //     `bindOwnerThread()` (below, first task on the owner) activates the assertOwnerThread()
        //     net in production: a missed hop now trips `raft_owner_thread`.
        //   - readDispatchExecutor: HTTP read handler marshalling (double-hop onto the owner)
        //   - tlsReloadExecutor: slow cert I/O
        //
        // CRITICAL invariant: ALL RaftNode access for a group - ticks, inbound messages, proposals,
        // and ReadIndexState reads - happens ONLY on that group's owner thread. readDispatchExecutor
        // and the inbound/propose handlers never touch the node directly; they marshal via
        // `driver.ownerExecutor(gid).execute(...)`. At N=1 a single owner thread does all of it.
        // ---------------------------------------------------------------
        OwnerExecutorPool ownerPool =
                new OwnerExecutorPool(Integer.getInteger("configd.raft.ownerPoolSize", 1));
        driver.setOwnerPool(ownerPool);
        System.out.println("  Owner pool   : " + ownerPool.size()
                + " owner thread(s) [Phase 0 B Stage 1B — R-01 deleted, consensus via ownerExecutor(gid)]");

        // ---------------------------------------------------------------
        // COALESCED HEARTBEATS. Each owner's per-tick drain sends one message per peer carrying every
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
        ScheduledExecutorService tlsReloadExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "configd-tls-reload");
            t.setDaemon(true);
            return t;
        });

        // ---------------------------------------------------------------
        // Group commit (per group). Each group's coalescing durability flush dispatches onto THAT group's
        // owner executor (all of a group's RaftNode mutation stays on its one owner thread). Entries
        // proposed concurrently are appended no-sync (RaftNode.propose -> RaftLog.appendNoSync) and
        // force-synced together by one flush task - amortizing the per-op force(true) that was
        // serializing the consensus thread (heartbeat starvation -> election churn). Tunables (system
        // properties) are read once and applied to every group (the setGroupCommit call itself is in
        // buildRaftGroup):
        //   -Dconfigd.groupCommit.enabled=false -> keep synchronous per-op fsync (the baseline)
        //   -Dconfigd.groupCommit.maxBatch=N     -> cap entries per fsync (default 4096; bounds latency)
        //   -Dconfigd.groupCommit.lingerMicros=T -> linger to grow the batch (default 0 = flush ASAP)
        // ---------------------------------------------------------------
        boolean groupCommitEnabled = Boolean.parseBoolean(
                System.getProperty("configd.groupCommit.enabled", "true"));
        int groupCommitMaxBatch = Integer.getInteger("configd.groupCommit.maxBatch", 4096);
        long groupCommitLingerMicros = Long.getLong("configd.groupCommit.lingerMicros", 0L);
        if (groupCommitEnabled) {
            System.out.println("  Group commit : ENABLED (maxBatch=" + groupCommitMaxBatch
                    + ", lingerMicros=" + groupCommitLingerMicros + ")");
        } else {
            System.out.println("  Group commit : DISABLED (synchronous per-op fsync — PART 1 baseline)");
        }

        // ===============================================================
        // N-group consensus bring-up loop. Build one RaftGroupRuntime per shard via the SINGLE
        // buildRaftGroup path (no duplication of the intricate storage/log/store/SM/node/transport/
        // group-commit wiring), register it on the driver, bind its owner thread, and bind its coalescer.
        // At N=1 (the production default) this runs EXACTLY ONCE for group 0 and is byte-identical to
        // the single-group bring-up.
        // ===============================================================
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
        RaftNode raftNode = primaryGroup.raftNode();
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
            primaryGroup.adapter().registerInboundHandler(raftDemuxInboundHandler(driver, configdMetrics));
            try {
                tcpTransport.start();
            } catch (Exception e) {
                throw new RuntimeException("Failed to start TCP Raft transport on "
                        + config.bindAddress() + ":" + config.bindPort(), e);
            }
        }

        // ---------------------------------------------------------------
        // Wire distribution layer
        // ---------------------------------------------------------------
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
        // is the only group; at N>1 the per-shard sources serve the v2 sharded edge client and the edge
        // endpoint warns it serves the primary shard only.
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
        // N>1) and is byte-identical at N=1. Cross-shard watch aggregation rides the v2 sharded edge
        // client.
        stateMachine.addListener(watchService::onConfigChange);

        // ---------------------------------------------------------------
        // Wire observability
        // ---------------------------------------------------------------
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

        // ---------------------------------------------------------------
        // Wire security (TLS already initialized above, before the Raft transport).
        // ---------------------------------------------------------------
        AuthInterceptor authInterceptor = null;
        AclService aclService = null;
        AclConfigPolicyLoader aclPolicyLoader = null;
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
                    // Root asserts NO roles - its break-glass authority is purely the static principal
                    // grant below, decoupled from any config-loadable role so no `_acl/` role can carve
                    // it via assertion.
                    return new AuthInterceptor.AuthResult.Authenticated(ROOT_PRINCIPAL, Set.of());
                }
                return new AuthInterceptor.AuthResult.Denied("invalid token");
            });
            aclService = new AclService();
            // Grant root principal full access to all keys
            aclService.grant("", ROOT_PRINCIPAL, EnumSet.allOf(AclService.Permission.class));
            // Config-sourced policy under `_acl/`. ADDITIVE on top of the static grant above (no `_acl/`
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

        // ---------------------------------------------------------------
        // Wire config read service with linearizable read support.
        //
        // The ReadIndex protocol requires:
        //   1. Record commit index (readIndex())
        //   2. Confirm leadership via heartbeat quorum
        //   3. Wait until lastApplied >= readIndex
        //   4. THEN serve the read
        //
        // Previously, readIndex() was called and the result discarded - the read was served immediately
        // without waiting for steps 2-3, making it equivalent to a stale read.
        //
        // readIndex() and isReadReady() access ReadIndexState (a non-thread-safe LinkedHashMap). These
        // must be dispatched to the tick thread, not called directly from HTTP handler threads.
        // ---------------------------------------------------------------
        // Reads route to the shard that OWNS (scope, key) using the per-request scope the GET handler
        // parses, so a read resolves the SAME shard the write of (scope, key) used (read-your-writes;
        // single-key linearizability preserved). getPrefix scatter-gathers across all shards (prefix keys
        // may hash to different shards). At N=1 every resolution is group 0 -> the single primary store.
        // readScope below is the GLOBAL default for the legacy key-only ConfigReader path; the
        // scope-aware reads use the caller's scope.
        final ConfigScope readScope = ConfigScope.GLOBAL;
        // Pass IMMUTABLE copies: the reader is read concurrently by HTTP threads (off the build thread),
        // so a frozen map/list makes the read-only-after-publication contract self-evident (diff-review NIT).
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

        // ---------------------------------------------------------------
        // Start HTTP API server
        // ---------------------------------------------------------------
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
        AuditLog auditLog = (authInterceptor != null) ? new AuditLog(storage, clock, auditLogKey) : null;
        if (auditLog != null) {
            System.out.println("  Audit log    : security-audit (KEYED HMAC-SHA256 chain, append-only, cap "
                    + AuditLog.DEFAULT_MAX_RECORDS + ")");
        }
        // Replay protection. OPT-IN (default OFF for back-compat); enabled via
        // -Dconfigd.replay.enabled=true so no new CLI/ServerConfig surface is added. Defends only
        // against PASSIVE capture-and-replay; a token holder can still mint fresh requests.
        ReplayGuard replayGuard = null;
        if (Boolean.getBoolean("configd.replay.enabled")) {
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

        NettyHttpApiServer httpApiServer;
        try {
            // The read 503 X-Leader-Hint is SHARD- AND SCOPE-AWARE - resolved for the shard that owns
            // (scope, key) using the read's per-request scope, so a client retries the right shard's
            // leader (a scopeless hint would loop at N>1). At N=1 every (scope, key) resolves to
            // group 0 -> raftNode.leaderId().
            httpApiServer = new NettyHttpApiServer(
                    config.apiPort(), sslContext, healthService, prometheusExporter,
                    configStore, writeService, readService, authInterceptor, aclService,
                    strongReadPolicy,
                    (scope, key) -> {
                        io.configd.raft.RaftNode owner = driver.getGroup(shardMap.shardFor(scope, key));
                        return owner != null ? owner.leaderId() : null;
                    },
                    auditLog, replayGuard, leadershipAdmin);
            httpApiServer.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start HTTP API server on port " + config.apiPort(), e);
        }

        // ---------------------------------------------------------------
        // Fan-out edge endpoint, optional (--edge-port). Drives the SAME FanOutSessionCore the
        // simulator drives, pulling via the readSince/ReplaySource seams ONLY - no work on the apply
        // path. Reuses the Raft TlsManager (REQUIRED mTLS when TLS is on; plaintext for
        // single-node/test, matching the Raft transport policy).
        // ---------------------------------------------------------------
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
            // Production edge fan-out is the Netty transport. The fast-revert is `git revert` of this
            // commit - restoring `new FanOutServer(...)` (the JDK transport is retained, fully tested by
            // the contract, and a drop-in FanOutEndpoint).
            // Server-side prefix filtering posture (ADR-0045): default ON for the co-located
            // trusted deployment, so a prefix-scoped edge that opts in gets its stream filtered;
            // set OFF (full-chain) when a separate/untrusted relay tier terminates the fan-out. The
            // strong-read prefixes are always shipped regardless of the edge's prefix set. When off
            // (or for a full-store / non-opting edge) the drain is byte-identical to the legacy path.
            // allowPartialShardView gates the legacy whole-store SUBSCRIBE plane at N>1 (primary-shard-
            // only); it never affects a multi-shard WATCH and is inert at N=1.
            boolean allowPartialShardView = Boolean.getBoolean("configd.edge.allowPartialShardView");
            io.configd.distribution.fanout.FanOutConfig fanOutConfig =
                    io.configd.distribution.fanout.FanOutConfig.defaults()
                            .withServerSidePrefixFilter(resolveEdgeFilterPosture(),
                                    strongReadPolicy.prefixes())
                            .withAllowPartialShardView(allowPartialShardView);
            fanOutServer = new io.configd.server.fanout.NettyFanOutServer(
                    edgeShardSources, edgeShardReplaySources, edgeAllGids, edgeShardResolver,
                    new InetSocketAddress(config.bindAddress(), config.edgePort()),
                    tlsManager,
                    fanOutConfig,
                    io.configd.server.fanout.FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                    io.configd.server.fanout.FanOutServer.DEFAULT_MAX_SESSIONS,
                    slowConsumerGovernor, fanOutMetrics, clock, watchAuthorizer);
            // Fail-closed: if TLS is enabled on the CLI but the edge endpoint did not receive a
            // TlsManager, refuse to start (no plaintext edge traffic in a TLS deployment).
            if (config.tlsEnabled() && tlsManager == null) {
                throw new IllegalStateException(
                        "TLS is enabled but FanOutServer has no TlsManager — refusing to start "
                                + "to avoid plaintext edge traffic");
            }
            try {
                fanOutServer.start();
                System.out.println("  Edge port    : " + fanOutServer.localPort()
                        + (tlsManager != null ? " (mTLS)" : " (PLAINTEXT)") + " [C1 fan-out, ADR-0037]");
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

        // ---------------------------------------------------------------
        // Start the consensus tick loop on each owner thread.
        // ---------------------------------------------------------------
        ConfigdServer server = new ConfigdServer(
                config, driver, stateMachine,
                ownerPool, readDispatchExecutor, tlsReloadExecutor,
                httpApiServer, tcpTransport, fanOutServer, aclPolicyLoader,
                watchService, fanOutBuffer, compactor, plumtreeNode, hyParViewOverlay,
                subscriptionManager, rolloutController, prometheusExporter);

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
     * Shutdown order matters. We must drain {@code readDispatchExecutor} FIRST so no new
     * read tasks are marshalled onto an owner thread. Then we shut the owner pool (each owner
     * also owns its groups' ReadIndexState + per-owner tick) so any in-flight reads complete.
     * Finally the {@code tlsReloadExecutor} is the slowest to drain and is stopped last.
     */
    public void shutdown() {
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
     * artifacts from the cluster signing key, and enforces the D-1 key-location requirement.
     * <p>
     * {@code K_integrity = HKDF-SHA256(IKM = signing private-key encoding,
     * salt = keyId bytes, info = "configd/raft-at-rest-integrity/v2", len = 32)} -
     * derived from the EXISTING cluster-shared signing key, so no new key file and no new
     * key-distribution channel is introduced. The verify side runs the identical derivation.
     * <p>
     * <b>Fail-closed:</b> {@code K_integrity}'s secrecy depends on the signing key living OUTSIDE
     * attacker-writable snapshot/WAL/backup storage. If the resolved {@code keyFile} is co-located
     * inside {@code dataDir}, a T3/A2 writer who can tamper the artifacts can also read the key and
     * recompute a valid MAC. {@link #enforceSigningKeyNotColocated} therefore REFUSES TO START by
     * default (the {@code configd.security.allowColocatedSigningKey} opt-out downgrades to a loud
     * warning for dev/test/single-node only); production mounts the key on separate storage.
     *
     * @param keyStore the loaded cluster signing key store
     * @param keyFile  the resolved signing-key file path
     * @param dataDir  the Raft data directory (where artifacts live)
     * @return a keyed, fail-closed integrity envelope
     */
    // Package-private (not private) so EncryptionAtRestWiringTest can assert the flag -> envelope
    // wiring directly, mirroring how enforceSigningKeyNotColocated is exercised by D1FailClosedTest.
    static io.configd.common.IntegrityEnvelope deriveRaftIntegrityEnvelope(
            SigningKeyStore keyStore, Path keyFile, Path dataDir) {
        // FAIL-CLOSED: refuse to derive the at-rest integrity key from a signing key co-located
        // inside the data dir it protects, BEFORE doing any crypto. Default = refuse to start; the
        // dev/test/single-node opt-out (system property OR env var, the latter for CI / docker-compose
        // where -D is awkward) downgrades to a loud warning.
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
        try {
            javax.crypto.SecretKey integrityKey = new javax.crypto.spec.SecretKeySpec(k, "HmacSHA256");

            // Secure-by-config: when at-rest ENCRYPTION is enabled, wrap the same seam in an
            // AES-256-GCM envelope (Layer C). K_integrity is carried as the legacy read key so an
            // existing HMAC-only WAL still recovers during the enable-encryption upgrade. When
            // encryption is OFF (the default) this returns the byte-identical keyed HMAC envelope.
            if (encryptionAtRestEnabled()) {
                return buildEncryptingEnvelope(ikm, salt, keyId, integrityKey);
            }
            return new io.configd.common.IntegrityEnvelope(integrityKey);
        } finally {
            // Zeroize the transient secret-derived material now that the SecretKeySpec (which clones
            // its input) and the LocalDerivedKmsProvider (which clones its IKM in its ctor and derives
            // synchronously before buildEncryptingEnvelope returns) hold their own copies. Best-effort
            // (the JCA SecretKeySpec copies are un-wipeable, JDK-8160206) but consistent discipline: the
            // raw Ed25519 private-key encoding and K_integrity should not linger on the heap after boot.
            java.util.Arrays.fill(ikm, (byte) 0);
            java.util.Arrays.fill(k, (byte) 0);
        }
    }

    /** True if at-rest encryption is enabled (system property, or the CI/docker-friendly env var). */
    private static boolean encryptionAtRestEnabled() {
        return Boolean.getBoolean("configd.raft.encryption.enabled")
                || "true".equalsIgnoreCase(System.getenv("CONFIGD_ENCRYPTION_AT_REST"));
    }

    /** System property that sets the edge fan-out server-side prefix-filtering posture (ADR-0045). */
    static final String EDGE_FILTER_PROP = "configd.edge.fanout.filter";

    /**
     * Resolves the {@value #EDGE_FILTER_PROP} posture: {@code on}/{@code off}, DEFAULT on for the
     * co-located trusted deployment, fail-loud on any other value (mirroring the
     * {@code NettyTransport.select} / KMS-provider posture flags - never a silent default). Set
     * {@code off} to restore the full-chain feed when a separate/untrusted relay tier terminates
     * the fan-out. This is a two-way door, not a one-way door.
     */
    static boolean resolveEdgeFilterPosture() {
        String v = System.getProperty(EDGE_FILTER_PROP, "on").trim().toLowerCase();
        return switch (v) {
            case "on", "true" -> true;
            case "off", "false" -> false;
            default -> throw new IllegalArgumentException(
                    EDGE_FILTER_PROP + " must be 'on'/'off' (or 'true'/'false'), got: '" + v + "'");
        };
    }

    /**
     * Builds the AES-256-GCM at-rest encryption envelope. Unseals a per-node root key through the
     * configured {@link KmsProvider} ONCE at boot (R1/R2), then derives per-segment DEKs locally.
     * <p>
     * <b>Fail-closed (R3):</b> naming a provider that is not built in is a startup error - NEVER a
     * silent downgrade to no encryption or to a different provider (a silent downgrade is how a
     * "data is encrypted at rest" claim becomes fiction). Only {@code local} (HKDF-from-signing-key)
     * ships in v1; a cloud provider is added as a separate module that slots into this same seam.
     *
     * <b>requireEncrypted (post-migration hardening):</b> once the plaintext/HMAC WAL prefix has been
     * compacted away, an operator can set {@code configd.raft.encryption.requireEncrypted} so the reader
     * REFUSES any legacy {@code algId=0/1} record (we pass {@code null} as the legacy read key). This
     * defends against a rollback/replay of an old plaintext WAL segment. Default: keep reading legacy
     * records (the migration path).
     *
     * @param ikm             the signing private-key encoding (the {@code local} KEK IKM)
     * @param salt            the signing keyId bytes (HKDF salt)
     * @param keyId           the signing keyId (the loggable KEK reference)
     * @param legacyReadKey   K_integrity, used to READ any pre-encryption HMAC records (upgrade path)
     * @return an encrypting {@link IntegrityEnvelope}
     */
    private static io.configd.common.IntegrityEnvelope buildEncryptingEnvelope(
            byte[] ikm, byte[] salt, java.util.UUID keyId, javax.crypto.SecretKey legacyReadKey) {
        String providerName = System.getProperty("configd.raft.encryption.kms.provider",
                System.getenv().getOrDefault("CONFIGD_ENCRYPTION_KMS_PROVIDER", "local"));
        if (!"local".equals(providerName)) {
            throw new IllegalStateException(
                    "configd.raft.encryption.kms.provider='" + providerName + "' is not available:"
                            + " only the built-in 'local' provider (HKDF-from-signing-key) ships in v1."
                            + " Refusing to start rather than silently downgrade - a silent downgrade is"
                            + " how a 'data is encrypted at rest' claim becomes fiction. Add the matching"
                            + " configd-kms-<provider> module to the classpath, or unset the property.");
        }
        boolean requireEncrypted = Boolean.getBoolean("configd.raft.encryption.requireEncrypted")
                || "true".equalsIgnoreCase(System.getenv("CONFIGD_ENCRYPTION_REQUIRE_ENCRYPTED"));
        // requireEncrypted: drop the legacy HMAC read key so the reader refuses algId=0/1 records.
        javax.crypto.SecretKey readKey = requireEncrypted ? null : legacyReadKey;
        // The one boot-time provider call (R1). For 'local' the root is a deterministic HKDF
        // re-derivation, so it never fails closed; a cloud provider would unwrap a persisted
        // WrappedKey here and fail closed if the KMS is unreachable.
        try (KmsProvider kms = new LocalDerivedKmsProvider(ikm, salt, keyId.toString(), 1)) {
            kms.healthCheck();
            RootKey root = kms.generateRootKey().rootKey();
            // GENUINE-WHY / INVARIANT: this is ONE SegmentKeyManager, and the SAME instance is shared
            // across ALL N Raft groups (it rides inside the single raftIntegrity envelope passed to every
            // buildRaftGroup). Global no-(key,nonce)-reuse holds because that one manager issues every
            // nonce from a single per-magic atomic counter. If a future refactor gives each group its OWN
            // manager, EACH manager MUST draw its OWN fresh random segmentId (hence its own DEK): sharing
            // a DEK across managers while splitting the nonce counter per group WOULD reuse (key,nonce)
            // and break GCM. Do not split the counter without splitting the segmentId/DEK.
            SegmentKeyManager keyManager = new SegmentKeyManager(root);
            LOG.log(Level.INFO,
                    "At-rest encryption ENABLED (AES-256-GCM, provider={0}, requireEncrypted={1})",
                    new Object[]{kms.currentKeyId(), requireEncrypted});
            // The provider is dropped when this block exits (R2): the node runs on the cached root.
            return io.configd.common.IntegrityEnvelope.encrypting(keyManager, readKey);
        } catch (KmsUnavailableException e) {
            // R3: fail closed. A node that cannot unseal its own at-rest key must not pretend it can.
            throw new IllegalStateException(
                    "At-rest encryption is enabled but the '" + providerName + "' KMS provider could"
                            + " not unseal the root key - refusing to start (fail-closed).", e);
        }
    }

    /**
     * Fail-closed co-location guard. The at-rest integrity key {@code K_integrity} is HKDF-derived
     * from the cluster signing key, so that signing key MUST NOT live inside the data directory holding
     * the snapshot/WAL/state it protects: a storage-tampering / full-host adversary (threat A2/T3) who
     * can write those artifacts could then ALSO read the co-located key and recompute a valid MAC,
     * making the integrity layer worthless.
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

    /** True if {@code keyFile} resolves to a path within {@code dataDir} (D-1 co-location check). */
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
     * Resolves and validates the deploy-time shard count {@code N}.
     * <ol>
     *   <li>reads {@code configd.raft.shardCount} (default {@code 1} - a single group, byte-identical;
     *       system property, consistent with the other {@code configd.raft.*} tunables);</li>
     *   <li>validates {@code 1 <= N <= }{@link #MAX_SHARD_COUNT} (a clear error otherwise);</li>
     *   <li>enforces fixed-at-deploy via {@link #enforceFixedShardCount} (persist on first boot, reject a
     *       later changed N - a loud reshard error rather than silent mis-routing).</li>
     * </ol>
     *
     * <p>{@code N>1} boots when the N-group production wiring is fully wired and verified.
     * {@code N=1} (the default) is unchanged.
     *
     * <p>Package-private static so {@code ShardCountConfigTest} can drive it directly.
     *
     * @param dataDir the data directory (holds the fixed-at-deploy marker)
     * @return the validated shard count {@code N}
     * @throws IllegalArgumentException if {@code N} is out of range
     * @throws IllegalStateException    if a reshard is attempted (configured N differs from the persisted N)
     */
    static int resolveShardCount(Path dataDir) {
        int shardCount = Integer.getInteger("configd.raft.shardCount", 1);
        if (shardCount < 1 || shardCount > MAX_SHARD_COUNT) {
            throw new IllegalArgumentException(
                    "configd.raft.shardCount must be in [1, " + MAX_SHARD_COUNT + "], got " + shardCount
                            + " — static-N multi-Raft; N is a deploy-time constant fixed for the life of"
                            + " the deployment (changing it requires a manual reshard).");
        }
        // N>1 is fully wired and verified. Fixed-at-deploy still applies: the first boot persists N; a
        // later boot with a different N is a loud reshard rejection, never silent mis-routing of
        // already-committed keys.
        enforceFixedShardCount(shardCount, dataDir);
        return shardCount;
    }

    /**
     * Fixed-at-deploy guard. Records the deploy-time shard count {@code N} under the data dir on first
     * boot and REJECTS a later boot whose configured {@code N} differs: changing {@code N} on an
     * existing deployment requires a manual reshard (static-N; v2 adds dynamic resharding). This turns a
     * reshard attempt into a LOUD, fail-closed startup error instead of silently mis-routing
     * already-committed keys to the wrong group. Idempotent: a matching marker is a no-op. The write is
     * crash-durable (temp + fsync, atomic rename, dir fsync - mirroring {@code FileStorage.put}) so a
     * crash can neither leave a torn marker nor lose it (a silent reset of the guard).
     *
     * <p>Package-private static so {@code ShardCountConfigTest} can drive it directly without standing up
     * a server.
     *
     * @param shardCount the configured, range-validated shard count (1..{@link #MAX_SHARD_COUNT})
     * @param dataDir    the data directory (holds the {@value #SHARD_COUNT_MARKER} marker)
     * @throws IllegalStateException if a marker exists and records a different {@code N} (a reshard
     *                               attempt) or is corrupt
     * @throws RuntimeException      if the marker cannot be read or written
     */
    static void enforceFixedShardCount(int shardCount, Path dataDir) {
        Path marker = dataDir.resolve(SHARD_COUNT_MARKER);
        try {
            if (Files.exists(marker)) {
                String recorded = Files.readString(marker, java.nio.charset.StandardCharsets.UTF_8).trim();
                int persisted;
                try {
                    persisted = Integer.parseInt(recorded);
                } catch (NumberFormatException nfe) {
                    throw new IllegalStateException(
                            "corrupt shard-count marker " + marker + " (content=\"" + recorded
                                    + "\"); refusing to start. Restore the marker or redeploy on a clean"
                                    + " data directory.", nfe);
                }
                if (persisted != shardCount) {
                    throw new IllegalStateException(
                            "configd.raft.shardCount=" + shardCount + " but this data directory was"
                                    + " initialized with N=" + persisted + " (" + marker + "). The shard"
                                    + " count is FIXED AT DEPLOY (static-N); changing it would mis-route"
                                    + " already-committed keys to the wrong group. To change N, perform a"
                                    + " manual reshard or redeploy on a fresh data directory.");
                }
                return; // matches - fixed-at-deploy honoured
            }
            // First boot for this data dir: record N CRASH-DURABLY - write temp + fsync, atomic rename,
            // fsync the directory - mirroring FileStorage.put so a crash in the OS writeback window cannot
            // LOSE the marker (not just "cannot leave a torn write"). The marker is the durability backbone
            // of the fixed-at-deploy safety guard, so it gets the same fsync discipline as Raft state.
            Path tmp = dataDir.resolve(SHARD_COUNT_MARKER + ".tmp");
            byte[] bytes = Integer.toString(shardCount).getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
                Files.move(tmp, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException amns) {
                Files.move(tmp, marker, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // fsync the directory so the rename itself is durable (mirrors FileStorage.sync()).
            try (java.nio.channels.FileChannel dir = java.nio.channels.FileChannel.open(
                    dataDir, java.nio.file.StandardOpenOption.READ)) {
                dir.force(true);
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read/write shard-count marker " + marker, e);
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
        // The node-level keyed integrity envelope authenticates this group's WAL + snapshot.
        RaftLog raftLog = new RaftLog(groupStorage, raftIntegrity);

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
            adapter = new RaftTransportAdapter(tcpTransport, groupId);
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
            // Legacy key-only reads route on readScope (the A2-3 GLOBAL default the server wires).
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
     *       stack trace - replaces the historical {@code printStackTrace(System.err)} which was
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
        // thread BEFORE the proposal reaches the executor. Default 0 = OFF (opt-in via
        // -Dconfigd.write.maxInflightProposals=N); the permit is held only for the bounded wait.
        int maxInflightProposals = Integer.getInteger("configd.write.maxInflightProposals", 0);
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
     * Returns the multi-raft driver for this server.
     */
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
     * Returns the fan-out buffer for delta distribution. At {@code N>1} this is the PRIMARY shard's
     * buffer only; the per-shard sources (one buffer per shard) are the cursor-vector view the v2
     * sharded edge client consumes. At {@code N=1} it is the single buffer.
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
     * {@code N>1} this is the PRIMARY shard only (per-shard sources are the cursor-vector v2 client
     * view).
     */
    public CommitNotificationSource commitNotificationSource() {
        return fanOutBuffer;
    }

    /**
     * The authoritative recovery seam a consumer replays from on a
     * {@link CommitNotificationSource#readSince(long)} GAP. A snapshot-equivalent replay over the live
     * config store. At {@code N>1} this is the PRIMARY shard's store only; each shard's per-shard replay
     * is derived on demand from its own {@code configStore()::snapshot} (per-shard cursor vector for the
     * v2 sharded edge client).
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
