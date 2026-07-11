package io.configd.testkit;

import io.configd.common.NodeId;
import io.configd.raft.*;
import io.configd.store.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameterized seed sweep for core Raft safety invariants.
 * <p>
 * A bare pass/fail per seed would be vacuous if a seed could silently skip the actual
 * assertion (no leader elected, no commit, no failover) and still report green, measuring
 * execution count rather than property coverage. This test instead:
 * <ul>
 *   <li>checks the full cross-node safety invariant set <b>after every tick, every
 *       seed</b> via {@link SimInvariants} (a violation FAILS the seed with replay
 *       context) and wires the throwing in-node {@link RaftNode.InvariantChecker}
 *       so the 8 named in-node checks fire too;</li>
 *   <li>treats a goal not reached within budget (no leader / no commit / no
 *       failover) as a <b>recorded liveness stall</b> - counted, never silently
 *       passed, never failed;</li>
 *   <li>asserts a per-seed <b>minimum-activity predicate</b> for the
 *       happy-path seeds and, at the sweep level, that the real-assertion rate is
 *       not implausibly low (the all-stall regression guard) - see
 *       {@link #sweepActivityIsNotVacuous()}.</li>
 * </ul>
 * Default: 10,000 seeds (CI). Set {@code -Dconfigd.seedSweep.count=100000} for the
 * full battle-ready sweep, or a small value (e.g. 500) for fast local verification.
 */
class SeedSweepTest {

    private static final int NODES = 5;
    private static final int SWEEP_TICKS = 2000;

    static LongStream seeds() {
        int count = Integer.getInteger("configd.seedSweep.count", 10_000);
        return LongStream.range(0, count);
    }

    /**
     * Election Safety: at most one leader per term. Checked continuously by
     * {@link SimInvariants} (every tick, plus the in-node {@code election_safety}
     * check), so the property is exercised on EVERY tick of EVERY seed - not just
     * sampled at the end. Always reaches a real assertion (a 5-node cluster with no
     * faults always elects); a stall is recorded, not silently passed.
     */
    @ParameterizedTest
    @MethodSource("seeds")
    void electionSafety(long seed) {
        Activity activity = new Activity();
        SimInvariants[] inv = new SimInvariants[1];
        ConsistencyPropertyTests.ClusterHarness cluster = newCheckedCluster(seed, inv);

        for (int t = 0; t < SWEEP_TICKS; t++) {
            cluster.tick();
            inv[0].checkAll(); // SAFETY: every tick, every seed
            for (int i = 0; i < NODES; i++) {
                if (cluster.node(i).role() == RaftRole.LEADER) {
                    activity.recordLeaderAtTerm(cluster.node(i).currentTerm());
                }
            }
        }

        // Liveness goal: a leader must have been elected. A miss is a recorded
        // stall (NOT a silent pass) - for a no-fault 5-node cluster it should
        // never happen, so we assert it to keep the sweep honest.
        assertTrue(activity.leaderElected(),
                "LIVENESS-STALL (recorded, not a safety bug): no leader elected in "
                        + SWEEP_TICKS + " ticks (seed=" + seed + ") — a no-fault cluster"
                        + " must elect; investigate if this fires.");
    }

    /**
     * Commit durability across leader failure. Safety invariants are checked every
     * tick throughout. A seed that fails to elect, commit, or fail over records a
     * liveness stall rather than silently returning; the durability assertion is
     * reached only when the seed actually elected, committed, and failed over - and
     * that real-assertion outcome is what {@link #sweepActivityIsNotVacuous()}
     * accounts for.
     */
    @ParameterizedTest
    @MethodSource("seeds")
    void commitSurvivesLeaderFailure(long seed) {
        assertCommitSurvivesLeaderFailure(seed, new Activity());
    }

    /**
     * Core of {@link #commitSurvivesLeaderFailure} factored out so the sweep-level
     * vacuity guard can run it and inspect the {@link Activity} outcome. Returns the
     * activity so the caller can classify the seed (reached-assertion vs stalled).
     */
    static Activity assertCommitSurvivesLeaderFailure(long seed, Activity activity) {
        SimInvariants[] inv = new SimInvariants[1];
        ConsistencyPropertyTests.ClusterHarness cluster = newCheckedCluster(seed, inv);

        int leader = electWhileChecking(cluster, inv[0], activity, 1200);
        if (leader < 0) {
            // A recorded liveness stall - the safety invariants were still checked
            // on every tick above; we simply did not reach the durability assertion
            // this seed.
            return activity;
        }
        activity.recordLeaderAtTerm(cluster.node(leader).currentTerm());

        long seq = proposeAndCommitWhileChecking(cluster, inv[0], leader,
                "sweep-key", "sweep-val", 200);
        if (seq <= 0) {
            return activity; // recorded no-commit stall
        }
        activity.recordCommit();

        runTicksWhileChecking(cluster, inv[0], 200);

        cluster.sim().isolateNode(NodeId.of(leader));

        int newLeader = awaitStableLeaderWhileChecking(cluster, inv[0], activity,
                Set.of(leader), 2000);
        if (newLeader < 0) {
            return activity; // recorded no-failover stall
        }
        activity.recordFailover();

        // The committed value MUST be present on the new leader - the real
        // assertion. Reached only by seeds that did all the work above.
        ReadResult result = cluster.store(newLeader).get("sweep-key");
        assertTrue(result.found(),
                "Committed value lost after leader failure (seed=" + seed + ")");
        assertEquals("sweep-val",
                new String(result.value(), StandardCharsets.UTF_8),
                "Committed value corrupted after leader failure (seed=" + seed + ")");
        return activity;
    }

    /**
     * Sweep-level vacuity guard. Runs a fixed batch and asserts that a
     * healthy majority of seeds actually <em>reached</em> the durability assertion
     * (elected + committed + failed over). If a regression makes the cluster unable
     * to commit/fail-over, the real-assertion rate collapses and this test FAILS
     * loudly, naming the honest count, rather than passing silently on every seed.
     */
    @Test
    void sweepActivityIsNotVacuous() {
        int batch = Integer.getInteger("configd.seedSweep.vacuityBatch", 200);
        int reachedAssertion = 0;
        int leaderStalls = 0;
        int commitStalls = 0;
        int failoverStalls = 0;

        for (long seed = 0; seed < batch; seed++) {
            Activity a = assertCommitSurvivesLeaderFailure(seed, new Activity());
            if (a.failoverCompleted()) {
                reachedAssertion++;
            } else if (!a.leaderElected()) {
                leaderStalls++;
            } else if (!a.valueCommitted()) {
                commitStalls++;
            } else {
                failoverStalls++;
            }
        }

        double reachedRate = (double) reachedAssertion / batch;
        // A no-fault 5-node cluster should reach the durability assertion on the
        // overwhelming majority of seeds. 0.80 is a generous floor that still
        // catches an all-stall regression (which would drive this toward 0).
        assertTrue(reachedRate >= 0.80,
                "RR-012 vacuity guard: only " + reachedAssertion + "/" + batch
                        + " (" + String.format("%.1f%%", reachedRate * 100)
                        + ") of seeds reached the durability assertion. "
                        + "leaderStalls=" + leaderStalls + " commitStalls=" + commitStalls
                        + " failoverStalls=" + failoverStalls
                        + " — the sweep would be passing vacuously.");
    }

    // Helpers: drive the cluster while checking safety invariants each tick. These replace
    // the harness convenience methods (which do not check invariants) so that EVERY tick of
    // EVERY seed is safety-checked.

    private static ConsistencyPropertyTests.ClusterHarness newCheckedCluster(
            long seed, SimInvariants[] out) {
        // Two-phase: the throwing in-node checker needs the harness to exist to
        // know nodeCount/seed, but the harness needs the checker at construction.
        // Resolve with a forwarding checker whose target is set immediately after.
        RaftNode.InvariantChecker[] target = new RaftNode.InvariantChecker[1];
        RaftNode.InvariantChecker forwarding = (name, cond, msg) -> {
            if (target[0] != null) {
                target[0].check(name, cond, msg);
            } else if (!cond) {
                throw new SimInvariants.SafetyViolation(
                        "IN-NODE invariant '" + name + "' violated during construction"
                                + " (seed=" + seed + "): " + msg);
            }
        };
        ConsistencyPropertyTests.ClusterHarness cluster =
                new ConsistencyPropertyTests.ClusterHarness(seed, NODES, forwarding);
        SimInvariants inv = new SimInvariants(cluster, seed);
        target[0] = inv.throwingNodeChecker();
        out[0] = inv;
        return cluster;
    }

    private static int electWhileChecking(ConsistencyPropertyTests.ClusterHarness cluster,
            SimInvariants inv, Activity activity, int maxTicks) {
        int stableCount = 0;
        int candidate = -1;
        for (int t = 0; t < maxTicks; t++) {
            cluster.tick();
            inv.checkAll();
            int leader = cluster.findLeader();
            if (leader >= 0) {
                activity.recordLeaderAtTerm(cluster.node(leader).currentTerm());
                if (leader == candidate) {
                    if (++stableCount >= 120) {
                        return leader;
                    }
                } else {
                    candidate = leader;
                    stableCount = 1;
                }
            } else {
                candidate = -1;
                stableCount = 0;
            }
        }
        return -1;
    }

    private static long proposeAndCommitWhileChecking(
            ConsistencyPropertyTests.ClusterHarness cluster, SimInvariants inv,
            int leader, String key, String value, int maxTicks) {
        long prev = cluster.store(leader).currentVersion();
        if (!cluster.proposePut(leader, key, value)) {
            return -1;
        }
        for (int t = 0; t < maxTicks; t++) {
            cluster.tick();
            inv.checkAll();
            long cur = cluster.store(leader).currentVersion();
            if (cur > prev) {
                return cur;
            }
        }
        return -1;
    }

    private static void runTicksWhileChecking(ConsistencyPropertyTests.ClusterHarness cluster,
            SimInvariants inv, int ticks) {
        for (int t = 0; t < ticks; t++) {
            cluster.tick();
            inv.checkAll();
        }
    }

    private static int awaitStableLeaderWhileChecking(
            ConsistencyPropertyTests.ClusterHarness cluster, SimInvariants inv,
            Activity activity, Set<Integer> exclude, int maxTicks) {
        int stableCount = 0;
        int candidate = -1;
        for (int t = 0; t < maxTicks; t++) {
            cluster.tick();
            inv.checkAll();
            int leader = cluster.findLeader(exclude);
            if (leader >= 0) {
                activity.recordLeaderAtTerm(cluster.node(leader).currentTerm());
                if (leader == candidate) {
                    if (++stableCount >= 120) {
                        return leader;
                    }
                } else {
                    candidate = leader;
                    stableCount = 1;
                }
            } else {
                candidate = -1;
                stableCount = 0;
            }
        }
        return -1;
    }
}
