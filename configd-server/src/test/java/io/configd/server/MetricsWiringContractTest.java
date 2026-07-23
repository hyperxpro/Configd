package io.configd.server;

import io.configd.api.ConfigWriteService;
import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.observability.ConfigdMetrics;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.raft.RequestVoteResponse;
import io.configd.raft.StateMachine;
import io.configd.replication.MultiRaftDriver;
import io.configd.store.ConfigStateMachine;
import io.configd.store.VersionedConfigStore;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the control-plane SLO metric series are RECORDED with real data when their real paths
 * execute: registering a metric is not enough if the record handle is never wired to the code path
 * that should call it (e.g. a gauge supplier hardwired to {@code () -> 0L}).
 *
 * <p>Unlike {@code ConfigdMetricsTest} (which records samples directly onto the metric handles),
 * each test here drives the REAL production seam - the commit-confirmed {@code raftProposer} and the
 * {@code ConfigStateMachine} apply path wired through {@link ServerStateMachineMetrics} - then scrapes
 * via a production-configured {@link PrometheusExporter} (with {@code histogramSchedules()}, so the
 * {@code _bucket{le=...}} series the burn-rate alerts query actually render) and asserts the series
 * moved off zero. Several methods double as "alert fires when its condition is injected" tests
 * (availability -> write_commit_failed; 429-rate -> write_rejected_overloaded).
 */
class MetricsWiringContractTest {

    private static final int GROUP = 0;

    private static ScheduledExecutorService raftExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-contract-exec");
            t.setDaemon(true);
            return t;
        });
    }

    /** Scrapes exactly as the live server does (same exporter + SLO histogram schedules). */
    private static String scrape(MetricsRegistry registry) {
        return new PrometheusExporter(registry, ConfigdMetrics.histogramSchedules()).export();
    }

    /** Returns the value of an unlabeled series line ({@code name value}); fails if absent. */
    private static double seriesValue(String scrape, String series) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(series) + "\\s+(\\S+)\\s*$")
                .matcher(scrape);
        assertTrue(m.find(), "series '" + series + "' must be present in scrape:\n" + scrape);
        return Double.parseDouble(m.group(1));
    }

    private static byte[] put(String key, String value) {
        return io.configd.store.CommandCodec.encodePut(key, value.getBytes(StandardCharsets.UTF_8));
    }

    /** Minimal raft state machine for the forced-leader cases (nothing ever commits -> never applies). */
    private static final class NoopSM implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    /**
     * A 3-node leader that can NEVER commit a client write (no peer acks delivered), built by
     * injecting the pre-vote + real-vote grants on the exec thread. Mirrors the established
     * pattern in {@code RaftNodeTest.proposalRejectedWhenOverloaded}.
     */
    private static RaftNode forcedUncommittableLeader(ScheduledExecutorService exec, int maxPending)
            throws Exception {
        NodeId n1 = NodeId.of(1), n2 = NodeId.of(2), n3 = NodeId.of(3);
        RaftConfig config = new RaftConfig(
                n1, Set.of(n2, n3), 150, 300, 50, 64, 256 * 1024, maxPending, 10, 1);
        RaftNode node = new RaftNode(config, new RaftLog(), (t, m) -> { }, new NoopSM(),
                new java.util.Random(7));
        exec.submit(() -> {
            for (int i = 0; i < 301; i++) node.tick();
            node.handleMessage(new RequestVoteResponse(node.currentTerm(), true, n2, true));
            node.handleMessage(new RequestVoteResponse(node.currentTerm(), true, n3, true));
            node.handleMessage(new RequestVoteResponse(node.currentTerm(), true, n2, false));
            node.handleMessage(new RequestVoteResponse(node.currentTerm(), true, n3, false));
        }).get(5, TimeUnit.SECONDS);
        assertEquals(RaftRole.LEADER, node.role(), "forced leader must hold leadership");
        return node;
    }

    private static MultiRaftDriver driverFor(RaftNode node) {
        MultiRaftDriver driver = new MultiRaftDriver(NodeId.of(1), Clock.system());
        driver.addGroup(GROUP, node);
        return driver;
    }

    @Test
    void committedWriteRecordsCommitLatencyTotalAndApplyDuration() throws Exception {
        ScheduledExecutorService exec = raftExecutor();
        try {
            MetricsRegistry registry = new MetricsRegistry();
            ConfigdMetrics metrics = new ConfigdMetrics(registry, () -> 0L);
            // The real apply path: ConfigStateMachine wired to the SAME ConfigdMetrics via the
            // production adapter, so apply_seconds is recorded when the committed entry applies.
            VersionedConfigStore store = new VersionedConfigStore();
            ConfigStateMachine sm = new ConfigStateMachine(store, Clock.system(), null, null,
                    new ServerStateMachineMetrics(metrics));
            RaftNode node = new RaftNode(RaftConfig.of(NodeId.of(1), Set.of()), new RaftLog(),
                    (t, m) -> { }, sm, new java.util.Random(7));
            exec.submit(() -> { for (int i = 0; i < 400; i++) node.tick(); }).get(5, TimeUnit.SECONDS);

            ConfigWriteService.RaftProposer proposer =
                    ConfigdServer.raftProposer(driverFor(node), GROUP, exec, 5000, metrics);
            var result = proposer.propose(null, java.util.List.of("k"), put("k", "v"));
            assertInstanceOf(ConfigWriteService.ProposeCommitResult.Committed.class, result,
                    "single-node leader must commit-confirm the write");

            String scrape = scrape(registry);
            assertTrue(seriesValue(scrape, "configd_write_commit_total") >= 1.0,
                    "a confirmed commit must increment write_commit_total");
            assertTrue(seriesValue(scrape, "configd_write_commit_seconds_count") >= 1.0,
                    "end-to-end commit latency must be recorded (write_commit_seconds)");
            // The exact bucket series the WriteCommitFastBurn alert queries must render (proves the
            // exporter was given histogramSchedules).
            assertTrue(scrape.contains("configd_write_commit_seconds_bucket{le=\"0.150\"}"),
                    "the le=0.150 bucket the burn-rate alert queries must be emitted:\n" + scrape);
            assertTrue(seriesValue(scrape, "configd_apply_seconds_count") >= 1.0,
                    "apply duration must be recorded via ServerStateMachineMetrics (apply_seconds)");
            // Stays-quiet: a clean commit must NOT trip the failure/overload counters (no
            // false-positive feed into the availability or 429-rate alerts).
            assertEquals(0.0, seriesValue(scrape, "configd_write_commit_failed_total"),
                    "a successful commit must not increment write_commit_failed_total");
            assertEquals(0.0, seriesValue(scrape, "configd_write_rejected_overloaded_total"),
                    "a successful commit must not increment write_rejected_overloaded_total");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void uncommittedWriteRecordsFailureCounter() throws Exception {
        // Availability-SLO alert "fires" test: an unconfirmed write must increment the failed
        // counter the alert's failed/(failed+total) denominator depends on.
        ScheduledExecutorService exec = raftExecutor();
        try {
            MetricsRegistry registry = new MetricsRegistry();
            ConfigdMetrics metrics = new ConfigdMetrics(registry, () -> 0L);
            RaftNode leader = forcedUncommittableLeader(exec, 1024);
            ConfigWriteService.RaftProposer proposer =
                    ConfigdServer.raftProposer(driverFor(leader), GROUP, exec, 200 /* ms */, metrics);
            var result = proposer.propose(null, java.util.List.of("k"), put("k", "v"));
            assertInstanceOf(ConfigWriteService.ProposeCommitResult.Indeterminate.class, result,
                    "a leader that cannot reach quorum must report the write as Indeterminate");
            assertTrue(seriesValue(scrape(registry), "configd_write_commit_failed_total") >= 1.0,
                    "an unconfirmed write must increment write_commit_failed_total");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void overloadedWriteRecordsRejectCounter() throws Exception {
        // 429-rate alert "fires" test: a bounded-queue shed must increment the
        // overload-reject counter that backs the Retry-After 429 path.
        ScheduledExecutorService exec = raftExecutor();
        try {
            MetricsRegistry registry = new MetricsRegistry();
            ConfigdMetrics metrics = new ConfigdMetrics(registry, () -> 0L);
            RaftNode leader = forcedUncommittableLeader(exec, 3); // maxPendingProposals = 3
            // Pre-fill the queue to the bound on the exec thread: no-op@1 + 2 accepted = 3.
            exec.submit(() -> {
                leader.propose(new byte[]{1});
                leader.propose(new byte[]{2});
            }).get(5, TimeUnit.SECONDS);

            ConfigWriteService.RaftProposer proposer =
                    ConfigdServer.raftProposer(driverFor(leader), GROUP, exec, 5000, metrics);
            var result = proposer.propose(null, java.util.List.of("k"), put("k", "v"));
            assertInstanceOf(ConfigWriteService.ProposeCommitResult.Overloaded.class, result,
                    "a write past the bounded proposal queue must be shed as Overloaded");
            assertTrue(seriesValue(scrape(registry), "configd_write_rejected_overloaded_total") >= 1.0,
                    "an overload shed must increment write_rejected_overloaded_total");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void laggingLeaderMovesReplicationLagMaxShardGauge() throws Exception {
        // The per-shard replication-lag gauge (the follower-stuck / snapshot-wedge proxy) must render
        // AND move off zero when a leader outruns its followers. Drives the REAL registerPerShardMetrics
        // path over a leader whose peers never ack.
        ScheduledExecutorService exec = raftExecutor();
        try {
            RaftNode leader = forcedUncommittableLeader(exec, 1024);
            // Grow the log past the followers' matchIndex (which stays 0), then publish a monitorView that
            // reflects LEADER role + the lag (the gauge reads the group's monitorView on scrape).
            exec.submit(() -> {
                leader.propose(new byte[]{9});
                leader.tick();
            }).get(5, TimeUnit.SECONDS);

            MetricsRegistry registry = new MetricsRegistry();
            ConfigdServer.RaftGroupRuntime rt =
                    new ConfigdServer.RaftGroupRuntime(GROUP, null, null, null, null, leader, null, null);
            ConfigdServer.registerPerShardMetrics(registry, driverFor(leader), java.util.List.of(rt));

            String scrape = scrape(registry);
            assertTrue(seriesValue(scrape, "raft_shard_replication_lag_max_0") > 0.0,
                    "a leader ahead of its un-acked followers must show a positive replication_lag_max:\n" + scrape);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void snapshotProducesSnapshotBytesGauge() {
        // The last-snapshot-size gauge must render at 0 on the first scrape and equal the byte length
        // of the snapshot the state machine produces (driven through the ServerStateMachineMetrics bridge).
        MetricsRegistry registry = new MetricsRegistry();
        ConfigdMetrics metrics = new ConfigdMetrics(registry, () -> 0L);
        assertEquals(0.0, seriesValue(scrape(registry), "configd_snapshot_bytes"),
                "the snapshot-bytes gauge must render at 0 before any snapshot is taken");

        VersionedConfigStore store = new VersionedConfigStore();
        ConfigStateMachine sm = new ConfigStateMachine(store, Clock.system(), null, null,
                new ServerStateMachineMetrics(metrics));
        byte[] snapshot = sm.snapshot();

        assertTrue(snapshot.length > 0, "even an empty store serializes a non-empty snapshot header");
        assertEquals((double) snapshot.length, seriesValue(scrape(registry), "configd_snapshot_bytes"),
                "the gauge must equal the last snapshot's byte length");
    }

    @Test
    void connectionDecodeDropBridgeIncrementsCounter() {
        // The transport's decode-desync sink must surface as the control-plane counter. The
        // transport-side call is proven on the real wire in the transport modules' decode-drop tests.
        MetricsRegistry registry = new MetricsRegistry();
        ConfigdMetrics metrics = new ConfigdMetrics(registry, () -> 0L);
        assertEquals(0.0, seriesValue(scrape(registry), "configd_raft_transport_connection_decode_dropped_total"),
                "the connection-decode-drop counter must render _total 0 on the first scrape");

        new ServerRaftTransportMetrics(metrics).onInboundConnectionDropped();

        assertEquals(1.0, seriesValue(scrape(registry), "configd_raft_transport_connection_decode_dropped_total"),
                "a decode-desync connection drop must increment the counter");
    }

    @Test
    void restoreConformanceMetricsRenderAppliedIndexAndStateHash() {
        // The restore-conformance check reads two series off /metrics: the applied-index gauge
        // (published from the tick thread) and the state-machine-hash info gauge (over the primary
        // group's snapshot payload). Prove both are wired the way ConfigdServer wires them and that the
        // hash line equals the digest the check compares against the snapshot file.
        MetricsRegistry registry = new MetricsRegistry();
        ConfigdMetrics metrics = new ConfigdMetrics(registry, () -> 0L);
        AtomicLong lastApplied = new AtomicLong(0);
        metrics.bindRaftLastAppliedGauge(lastApplied::get);

        VersionedConfigStore store = new VersionedConfigStore();
        ConfigStateMachine sm = new ConfigStateMachine(store, Clock.system(), null, null,
                new ServerStateMachineMetrics(metrics));
        metrics.bindStateMachineHashGauge(sm::stateMachineHashHex);

        // First scrape: applied index at its published 0, hash present over the empty store.
        assertEquals(0.0, seriesValue(scrape(registry), "configd_raft_last_applied_index"));

        sm.apply(1, 1, io.configd.store.CommandCodec.encodePut("k", "v".getBytes(StandardCharsets.UTF_8)));
        lastApplied.set(7); // the tick thread publishes the raft log index

        String scrape = scrape(registry);
        assertEquals(7.0, seriesValue(scrape, "configd_raft_last_applied_index"),
                "the applied-index gauge must reflect the tick-published value");

        Matcher hashLine = Pattern.compile("(?m)^configd_state_machine_hash\\{hash=\"([0-9a-f]{64})\"\\} 1$")
                .matcher(scrape);
        assertTrue(hashLine.find(), "the state-machine-hash info gauge must render as a 64-hex label:\n" + scrape);
        // The rendered digest must be the same value the shell check computes over snapshot()[12:].
        byte[] payload = java.util.Arrays.copyOfRange(sm.snapshot(), 12, sm.snapshot().length);
        String expected;
        try {
            expected = java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertEquals(expected, hashLine.group(1),
                "the exposed hash must equal sha256(snapshot()[12:])");
    }

    @Test
    void gaugesAndElectionsCounterAreNotHardwiredToZero() {
        // Proves the raft_pending_apply_entries gauge reads its supplier (NOT the old () -> 0L), and
        // the elections counter + subscription gauge render real values.
        MetricsRegistry registry = new MetricsRegistry();
        AtomicLong pendingApply = new AtomicLong(42);
        ConfigdMetrics metrics = new ConfigdMetrics(registry, pendingApply::get);
        metrics.raftElections().increment(3);
        metrics.bindSubscriptionPrefixGauge(() -> 7L);

        String scrape = scrape(registry);
        assertEquals(42.0, seriesValue(scrape, "configd_raft_pending_apply_entries"),
                "the gauge must reflect its supplier, not the hardwired zero");
        assertEquals(3.0, seriesValue(scrape, "configd_raft_elections_total"));
        assertEquals(7.0, seriesValue(scrape, "configd_subscription_prefix_count"));
    }
}
