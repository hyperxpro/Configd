package io.configd.testkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Re-runs the stall-class stall seeds against the integrated simulator config and
 * reports the deltas.
 *
 * <p>The seven seeds were characterized on the bare CP sim
 * ({@code new AdversarialSim(seed, 5, 1500).run()} => {@code leaderElected=false}; a
 * never-healed drop/partition schedule - expected liveness artifact, 0 safety impact).
 * This re-run uses the integrated config: the same CP topology/ticks (5 nodes, 1500
 * ticks) with the edge plane LIVE - 3 edges, edge faults, the real
 * {@link C1StreamDriver}, per-tick {@link EdgeInvariants}, and the recovery loop
 * enabled on every edge ({@link EdgeFanOutSim#enableEdgeRecovery}, the
 * {@code EdgeBootstrapUnderSustainedWritesTest} seam usage). The CP schedule is
 * byte-identical with edges attached ({@code EdgeSeedCompatTest} pins this), so the
 * EXPECTED delta shape is "still-stalls CP-side, edge plane starves SAFELY (zero
 * safety violations, no delivery)" - anything else is a new behavior to report.
 *
 * <p>Gated on {@code -Dconfigd.rr095.rerun=true}; report-only (one greppable
 * {@code RR095-RERUN:} line per seed). This test never mutates any shared
 * state; it only prints.
 */
class Rr095StallSeedsIntegratedRerunTest {

    /** Seeds from the 10k sweep that reproduced the diagnosed stall. */
    private static final long[] STALL_SEEDS = {452, 869, 4740, 5100, 5159, 5500, 8319};

    /** The characterization shape: 5 CP nodes, 1500 ticks. */
    private static final int CP_NODES = 5;
    private static final int EDGES = 3;
    private static final int TICKS = 1_500;

    @Test
    @EnabledIfSystemProperty(named = "configd.rr095.rerun", matches = "true")
    void rerunStallSeedsOnIntegratedSim() {
        for (long seed : STALL_SEEDS) {
            EdgeFanOutSim sim = new EdgeFanOutSim(seed, CP_NODES, EDGES, TICKS,
                    /* edgeFaults */ true, new C1StreamDriver(),
                    AdversarialSchedule.defaultIntensity(), EdgeInvariants.BOUND_MS);
            for (int e = 0; e < EDGES; e++) {
                sim.enableEdgeRecovery(e); // the production directive loop, live
            }
            sim.run(); // throws SafetyViolation (with the seed) on ANY breach - none expected

            boolean cpLeaderElected = sim.cpSim().activity().leaderElected();
            long cpFaults = sim.cpSim().activity().faultsFired();
            EdgeActivity activity = sim.activity();

            sim.settleCp();
            boolean cpConvergedAfterHeal = sim.cpFullyConverged();
            boolean edgeConverged;
            try {
                sim.finalCheck();
                edgeConverged = true;
            } catch (SimInvariants.SafetyViolation divergence) {
                edgeConverged = false; // liveness miss, recorded
            }

            System.out.println("RR095-RERUN: seed=" + seed
                    + " cpLeaderElected=" + cpLeaderElected
                    + " cpFaults=" + cpFaults
                    + " cpConvergedAfterHeal=" + cpConvergedAfterHeal
                    + " edgeDelivered=" + activity.deliveredCount()
                    + " edgeDeliveryViolations=" + activity.deliveryViolationCount()
                    + " excusedAtDeadline=" + activity.excusedAtDeadline()
                    + " edgeConverged=" + edgeConverged
                    + " safetyViolations=0");
        }
    }
}
