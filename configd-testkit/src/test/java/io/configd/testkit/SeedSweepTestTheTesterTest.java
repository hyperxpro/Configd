package io.configd.testkit;

import io.configd.raft.*;
import io.configd.store.CommandCodec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-the-tester: proves the de-vacuated sweep's invariant
 * checking actually catches a real safety violation, rather than passing
 * vacuously.
 * <p>
 * <b>The injected violation.</b> We re-introduce the observable symptom of removing
 * Raft's section 5.4.2 current-term commit guard
 * ({@code RaftNode.maybeAdvanceCommitIndex}: {@code if (log.termAt(n) != currentTerm)
 * continue;}). That guard, removed, lets a leader commit a prior-term entry by
 * replication count alone; the observable consequence is two committed replicas
 * disagreeing on the term at the same committed index - a <b>Log Matching</b> /
 * <b>State Machine Safety</b> violation. Because production {@code RaftNode} must not
 * be modified here, we inject the <em>identical observable corruption</em> at the
 * simulation layer instead: after a value has committed and replicated, we rewrite
 * one follower's committed log entry to a different term (exactly what the
 * guard-less leader would have produced). The continuous {@link SimInvariants}
 * checker MUST then RED on the very next {@code checkAll()}.
 * <p>
 * This is a switchable, test-only utility - gated on
 * {@code -Dconfigd.testTheTester=true} so it never runs in the normal suite. It
 * mutates only test-scope simulation state ({@code RaftLog}); it does not touch
 * production code.
 */
@EnabledIfSystemProperty(named = "configd.testTheTester", matches = "true")
class SeedSweepTestTheTesterTest {

    private static final int NODES = 5;

    @Test
    void injectedSafetyViolationIsAlwaysCaught() {
        int batch = Integer.getInteger("configd.testTheTester.batch", 50);
        int reached = 0;
        int caught = 0;
        StringBuilder log = new StringBuilder();

        for (long seed = 0; seed < batch; seed++) {
            Outcome o = runWithInjection(seed);
            if (!o.reachedInjectionPoint) {
                continue;
            }
            reached++;
            assertTrue(o.violationCaught,
                    "TEST-THE-TESTER FAILED: injected term-divergence at a committed"
                            + " index was NOT caught by SimInvariants (seed=" + seed + ")."
                            + " The checker is vacuous.");
            caught++;
            log.append("seed=").append(seed)
               .append(" CAUGHT: ").append(o.violationMessage).append('\n');
        }

        assertTrue(reached > 0, "No seed reached the injection point in batch=" + batch);
        assertEquals(reached, caught,
                "Every seed that reached the injection point must be caught");

        System.out.println("[test-the-tester] reached=" + reached + " caught=" + caught
                + " of batch=" + batch + "\n" + log);
    }

    private record Outcome(boolean reachedInjectionPoint, boolean violationCaught,
                           String violationMessage) {}

    private static Outcome runWithInjection(long seed) {
        SimInvariants[] inv = new SimInvariants[1];
        ConsistencyPropertyTests.ClusterHarness cluster = newCheckedCluster(seed, inv);

        int leader = elect(cluster, inv[0], 1200);
        if (leader < 0) {
            return new Outcome(false, false, null);
        }
        long seq = proposeAndCommit(cluster, inv[0], leader, "ttt-key", "ttt-val", 200);
        if (seq <= 0) {
            return new Outcome(false, false, null);
        }
        // Let it replicate so >=2 nodes hold the committed entry.
        for (int t = 0; t < 200; t++) {
            cluster.tick();
            inv[0].checkAll();
        }

        int follower = -1;
        long idx = -1;
        for (int i = 0; i < NODES; i++) {
            if (i == leader) {
                continue;
            }
            RaftLog log = cluster.log(i);
            if (log.commitIndex() > log.snapshotIndex()) {
                follower = i;
                idx = log.commitIndex();
                break;
            }
        }
        if (follower < 0) {
            return new Outcome(false, false, null);
        }

        // INJECT: rewrite the committed entry at `idx` on `follower` to a bogus
        // term - the exact divergence a guard-less leader (Raft section 5.4.2) would create.
        RaftLog corruptLog = cluster.log(follower);
        long origTerm = corruptLog.termAt(idx);
        byte[] cmd = encodePut("ttt-key", "ttt-val");
        corruptLog.truncateFrom(idx);
        corruptLog.append(new LogEntry(idx, origTerm + 999, cmd));
        corruptLog.setCommitIndex(idx);

        try {
            inv[0].checkAll();
            return new Outcome(true, false, null);
        } catch (SimInvariants.SafetyViolation v) {
            return new Outcome(true, true, v.getMessage());
        }
    }


    private static ConsistencyPropertyTests.ClusterHarness newCheckedCluster(
            long seed, SimInvariants[] out) {
        RaftNode.InvariantChecker[] target = new RaftNode.InvariantChecker[1];
        RaftNode.InvariantChecker forwarding = (name, cond, msg) -> {
            if (target[0] != null) {
                target[0].check(name, cond, msg);
            }
        };
        ConsistencyPropertyTests.ClusterHarness cluster =
                new ConsistencyPropertyTests.ClusterHarness(seed, NODES, forwarding);
        SimInvariants invariants = new SimInvariants(cluster, seed);
        target[0] = invariants.throwingNodeChecker();
        out[0] = invariants;
        return cluster;
    }

    private static int elect(ConsistencyPropertyTests.ClusterHarness cluster,
            SimInvariants inv, int maxTicks) {
        int stable = 0;
        int cand = -1;
        for (int t = 0; t < maxTicks; t++) {
            cluster.tick();
            inv.checkAll();
            int leader = cluster.findLeader();
            if (leader >= 0) {
                if (leader == cand) {
                    if (++stable >= 120) {
                        return leader;
                    }
                } else {
                    cand = leader;
                    stable = 1;
                }
            } else {
                cand = -1;
                stable = 0;
            }
        }
        return -1;
    }

    private static long proposeAndCommit(ConsistencyPropertyTests.ClusterHarness cluster,
            SimInvariants inv, int leader, String key, String value, int maxTicks) {
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

    private static byte[] encodePut(String key, String value) {
        return CommandCodec.encodePut(key, value.getBytes(StandardCharsets.UTF_8));
    }
}
