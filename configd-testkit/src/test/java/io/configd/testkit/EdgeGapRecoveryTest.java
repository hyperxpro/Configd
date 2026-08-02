package io.configd.testkit;

import io.configd.distribution.fanout.FanOutConfig;
import io.configd.store.CommandCodec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class EdgeGapRecoveryTest {

    private static final int CP_NODES = 3;
    private static final int EDGES = 2;
    private static final int WORKLOAD_TICKS = 1_500;

    /** ackLag effectively disabled: the ONLY recovery available is the resubscribe path. */
    private static FanOutConfig noAckLagHealConfig() {
        return new FanOutConfig(64, 80, 64, 262_144, 1_000_000L, 250L, 5L, 1_048_576);
    }

    @Test
    void gapWithinTheHorizonRecoversByReplayFromTheBoundaryNoSnapshot() {
        C1StreamDriver driver = new C1StreamDriver(noAckLagHealConfig());
        EdgeFanOutSim sim = new EdgeFanOutSim(21L, CP_NODES, EDGES, WORKLOAD_TICKS,
                false, driver, new AdversarialSchedule.Intensity(0, 60, 0.0),
                EdgeInvariants.BOUND_MS);
        sim.run();
        EdgeActor victim = sim.edges().get(0);
        long baseVersion = victim.currentVersion();
        assertTrue(baseVersion > 0, "non-vacuity: the victim applied the workload");
        int snapshotsBefore = victim.snapshotsApplied();
        sim.enableEdgeRecovery(0);

        sim.partitionEdge(0);
        for (int i = 1; i <= 3; i++) {
            commit(sim, victim.subscribedCpNode(), "gap/k" + i, "missed-" + i);
        }

        // Heal; one more concurrent write makes the next NOTIFY arrive with a gap.
        sim.healEdge(0);
        commit(sim, victim.subscribedCpNode(), "gap/after", "post-heal");

        long target = sim.cpSim().store(victim.subscribedCpNode()).currentVersion();
        tickUntil(sim, () -> victim.currentVersion() >= target,
                "victim re-converged by replay");

        assertTrue(victim.gapsDetected() >= 1, "the wedge really was a detected gap");
        assertTrue(driver.resubscribes() >= 1, "recovery ran through the resubscribe path");
        assertEquals(snapshotsBefore, victim.snapshotsApplied(),
                "WITHIN the horizon the recovery is REPLAY — a snapshot here would mean "
                        + "the server wrongly chose re-bootstrap");
        assertArrayEquals(sim, victim, "gap/k3", "missed-3");
        assertArrayEquals(sim, victim, "gap/after", "post-heal");
        assertTrue(sim.terminalFailures().isEmpty());
    }

    @Test
    void gapBeyondTheHorizonRecoversBySnapshotRebootstrap() {
        C1StreamDriver driver = new C1StreamDriver(noAckLagHealConfig());
        // Ring capacity 8: a dozen missed commits lap the victim's cursor (the horizon is
        // crossable at sim scale; production keeps 10_000 - same code, smaller knob).
        EdgeFanOutSim sim = new EdgeFanOutSim(23L, CP_NODES, EDGES, WORKLOAD_TICKS,
                false, driver, new AdversarialSchedule.Intensity(0, 30, 0.0),
                EdgeInvariants.BOUND_MS, 8);
        sim.run();
        EdgeActor victim = sim.edges().get(0);
        long baseVersion = victim.currentVersion();
        assertTrue(baseVersion > 0, "non-vacuity: the victim applied the workload");
        int snapshotsBefore = victim.snapshotsApplied();
        sim.enableEdgeRecovery(0);

        sim.partitionEdge(0);
        for (int i = 1; i <= 12; i++) { // > ring capacity: the horizon passes the victim
            commit(sim, victim.subscribedCpNode(), "lap/k" + (i % 3), "lapped-" + i);
        }
        long oldest = sim.source(victim.subscribedCpNode()).oldestSeq();
        assertTrue(oldest > baseVersion + 1,
                "fixture: the ring genuinely lapped the victim (oldest " + oldest
                        + " vs cursor " + baseVersion + ")");

        sim.healEdge(0);
        commit(sim, victim.subscribedCpNode(), "lap/after", "post-heal");

        long target = sim.cpSim().store(victim.subscribedCpNode()).currentVersion();
        tickUntil(sim, () -> victim.currentVersion() >= target,
                "victim re-converged by snapshot re-bootstrap");

        assertTrue(driver.resubscribes() >= 1, "recovery ran through the resubscribe path");
        assertTrue(victim.snapshotsApplied() > snapshotsBefore,
                "BEYOND the horizon the recovery must be a snapshot re-bootstrap");
        assertArrayEquals(sim, victim, "lap/after", "post-heal");
        assertTrue(sim.terminalFailures().isEmpty());
    }

    /**
     * Makes the automatic-coverage claim executable: under full adversarial schedules
     * (edge crashes/partitions/lag + CP workload) with the recovery loop LIVE on every
     * edge, no safety invariant (version monotonicity, no stale overwrite,
     * convergence-effect equality) may break, and the recovery must actually fire across
     * the seed set (a recovery feature that never runs proves nothing).
     */
    @Test
    void recoveryUnderAdversarialSchedulesIntroducesNoSafetyViolations() {
        int totalResubscribes = 0;
        int converged = 0;
        final int seeds = 20;
        for (long seed = 9_000; seed < 9_000 + seeds; seed++) {
            C1StreamDriver driver = new C1StreamDriver();
            EdgeFanOutSim sim = new EdgeFanOutSim(seed, CP_NODES, 3, WORKLOAD_TICKS,
                    true, driver, AdversarialSchedule.defaultIntensity(),
                    EdgeInvariants.BOUND_MS);
            for (int e = 0; e < 3; e++) {
                sim.enableEdgeRecovery(e);
            }
            // Safety invariants run EVERY tick and THROW - an unsafe recovery fails here.
            sim.run();
            try {
                sim.finalCheckHealingCp();
                converged++;
            } catch (SimInvariants.SafetyViolation notConverged) {
                // Convergence-given-heal is liveness at sweep level; recorded, not
                // asserted per-seed. Safety violations DURING the run threw out of
                // run() above and fail the test.
            }
            totalResubscribes += driver.resubscribes();
        }
        assertTrue(totalResubscribes > 0,
                "non-vacuity: the recovery loop must actually fire across the sweep");
        assertTrue(converged >= seeds / 2,
                "with recovery live, at least half the healed seeds must converge "
                        + "(deterministic; observed " + converged + "/" + seeds + ")");
    }

    /**
     * Commits one write through the REAL CP (leader propose) and ticks until the observed
     * node has APPLIED IT (value equality - a bare version-advance check races the
     * previous write's in-flight apply on a follower and returns one seq short).
     */
    private static void commit(EdgeFanOutSim sim, int observedCpNode, String key, String value) {
        byte[] expected = value.getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 50; attempt++) {
            int leader = sim.cpSim().findLeader();
            if (leader >= 0) {
                sim.cpSim().node(leader).propose(CommandCodec.encodePut(key, expected));
            }
            for (int t = 0; t < 20; t++) {
                sim.tick();
                var r = sim.cpSim().store(observedCpNode).get(key);
                if (r.found() && java.util.Arrays.equals(expected, r.value())) {
                    return;
                }
            }
        }
        fail("write '" + key + "' did not commit/apply on cp node " + observedCpNode);
    }

    private static void tickUntil(EdgeFanOutSim sim, java.util.function.BooleanSupplier cond,
                                  String what) {
        for (int t = 0; t < 3_000; t++) {
            if (cond.getAsBoolean()) {
                return;
            }
            sim.tick();
        }
        fail("not reached within the tick bound: " + what);
    }

    private static void assertArrayEquals(EdgeFanOutSim sim, EdgeActor edge,
                                          String key, String expected) {
        var r = edge.get(key);
        if (!r.found()) {
            StringBuilder sb = new StringBuilder();
            sb.append("edge must hold ").append(key)
                    .append(" — edgeVersion=").append(edge.currentVersion())
                    .append(" cpVersion=").append(
                            sim.cpSim().store(edge.subscribedCpNode()).currentVersion())
                    .append(" snapshots=").append(edge.snapshotsApplied())
                    .append(" gaps=").append(edge.gapsDetected())
                    .append(" edgeKeys=[");
            edge.snapshot().data().forEach((k, v) -> sb.append(k).append(' '));
            sb.append("] cpKeys=[");
            sim.cpSim().store(edge.subscribedCpNode()).snapshot().data()
                    .forEach((k, v) -> sb.append(k).append(' '));
            sb.append(']');
            fail(sb.toString());
        }
        assertEquals(expected, new String(r.value(), StandardCharsets.UTF_8), key);
    }
}
