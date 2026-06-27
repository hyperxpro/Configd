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
 * S6/WS-A KEYSTONE — proves the control-plane SLO metric series are RECORDED with real data when
 * their real paths execute, closing the S1 "9 SLO metrics hardwired to zero" defect that survived
 * the F5/H-001 closure (which built the <em>registration</em> but never the <em>wire-up</em>: every
 * record handle was dead and the raft-pending gauge was literally {@code () -> 0L}).
 *
 * <p>Unlike {@code ConfigdMetricsTest} (which records samples directly onto the metric handles),
 * each test here drives the REAL production seam — the commit-confirmed {@code raftProposer} and the
 * {@code ConfigStateMachine} apply path wired through {@link ServerStateMachineMetrics} — then scrapes
 * via a production-configured {@link PrometheusExporter} (with {@code histogramSchedules()}, so the
 * {@code _bucket{le=...}} series the burn-rate alerts query actually render) and asserts the series
 * moved off zero. Several methods double as the "alert fires when its condition is injected" tests
 * required by the charter (availability → write_commit_failed; 429-rate → write_rejected_overloaded).
 */
class MetricsWiringContractTest {

    private static final int GROUP = 0;

    // ---- helpers ----------------------------------------------------------

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

    /** Minimal raft state machine for the forced-leader cases (nothing ever commits → never applies). */
    private static final class NoopSM implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    /**
     * A 3-node leader that can NEVER commit a client write (no peer acks delivered), built by
     * injecting the pre-vote + real-vote grants on the exec thread (R-01). Mirrors the established
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

    // ---- the real-path contract tests ------------------------------------

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
            // The exact bucket series the WriteCommitFastBurn alert queries MUST render (proves the
            // exporter was given histogramSchedules — the third blind-dashboard defect this closes).
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
        // 429-rate alert "fires" test (RR-110 / D-1): a bounded-queue shed must increment the
        // overload-reject counter that backs the Retry-After 429 path.
        ScheduledExecutorService exec = raftExecutor();
        try {
            MetricsRegistry registry = new MetricsRegistry();
            ConfigdMetrics metrics = new ConfigdMetrics(registry, () -> 0L);
            RaftNode leader = forcedUncommittableLeader(exec, 3); // maxPendingProposals = 3
            // Pre-fill the queue to the bound on the exec thread (R-01): no-op@1 + 2 accepted = 3.
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
    void gaugesAndElectionsCounterAreNotHardwiredToZero() {
        // Proves the raft_pending_apply_entries gauge reads its supplier (NOT the old () -> 0L), and
        // the elections counter + subscription gauge render real values — the dashboard panels 4/5/6.
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
