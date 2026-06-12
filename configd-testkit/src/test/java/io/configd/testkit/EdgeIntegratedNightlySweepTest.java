package io.configd.testkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The INTEGRATED 10k-seed sweep (charter §7: "a 10k-seed integrated sweep run at least
 * once, zero safety violations; liveness findings registered with seeds"). The
 * {@link AdversarialSimTest#nightlyAdversarialSweep} pattern applied to the integrated
 * configuration — the EXACT {@link EdgeAdversarialGateSeedSweepTest} topology (5 CP nodes
 * + 3 edges + the real {@link C1StreamDriver} + full CP fault schedule + edge faults +
 * per-tick {@link EdgeInvariants}) over sequential seeds instead of the committed
 * manifest.
 *
 * <p>Gated on {@code -Dconfigd.edge.nightly=true} (the established nightly-property
 * pattern); seed count override: {@code -Dconfigd.edge.sweepCount}. Run it ALONE — the
 * 2-vCPU gate box cannot overlap it with another JVM workload.
 *
 * <h2>Enforced vs recorded (the gate-sweep discipline, unchanged)</h2>
 * <ul>
 *   <li><b>SAFETY (enforced):</b> any per-tick edge safety violation throws
 *       {@link SimInvariants.SafetyViolation} naming the seed — the sweep fails.</li>
 *   <li><b>LIVENESS (recorded):</b> CP liveness stalls (no leader ever elected — the
 *       RR-095 class) and edge convergence misses are counted and the offending seeds
 *       PRINTED for the register report, never failed. The meaningful edge-liveness
 *       signal is a convergence miss <em>given a quiet window</em> (CP converged after
 *       heal + settle); those seeds are listed separately.</li>
 * </ul>
 */
class EdgeIntegratedNightlySweepTest {

    private static final int CP_NODES = 5;
    private static final int EDGES = 3;
    private static final int TICKS = 1_200;

    /** Cap on seeds listed per category (keeps the summary greppable at 10k scale). */
    private static final int MAX_LISTED_SEEDS = 100;

    @Test
    @EnabledIfSystemProperty(named = "configd.edge.nightly", matches = "true")
    void nightlyIntegratedEdgeSweep() {
        int count = Integer.getInteger("configd.edge.sweepCount", 10_000);
        long start = System.nanoTime();

        int cpLeaderElected = 0;
        List<Long> cpStallSeeds = new ArrayList<>();
        int quietWindowSeeds = 0;
        int convergedGivenQuiet = 0;
        int convergedRaw = 0;
        int seedsWithDelivery = 0;
        long totalDeliveryViolations = 0;
        List<Long> quietWindowMissSeeds = new ArrayList<>();

        for (long seed = 0; seed < count; seed++) {
            EdgeFanOutSim sim = new EdgeFanOutSim(seed, CP_NODES, EDGES, TICKS,
                    /* edgeFaults */ true, new C1StreamDriver(),
                    AdversarialSchedule.defaultIntensity(), EdgeInvariants.BOUND_MS);
            sim.run(); // throws SafetyViolation (carrying the seed) on ANY breach

            if (sim.cpSim().activity().leaderElected()) {
                cpLeaderElected++;
            } else if (cpStallSeeds.size() < MAX_LISTED_SEEDS) {
                cpStallSeeds.add(seed);
            }
            EdgeActivity activity = sim.activity();
            totalDeliveryViolations += activity.deliveryViolationCount();
            if (activity.deliveredCount() > 0) {
                seedsWithDelivery++;
            }

            sim.settleCp();
            boolean quietWindow = sim.cpFullyConverged();
            if (quietWindow) {
                quietWindowSeeds++;
            }
            boolean edgeConverged;
            try {
                sim.finalCheck();
                edgeConverged = true;
            } catch (SimInvariants.SafetyViolation divergence) {
                edgeConverged = false; // recorded liveness miss, never a build failure
            }
            if (edgeConverged) {
                convergedRaw++;
                if (quietWindow) {
                    convergedGivenQuiet++;
                }
            } else if (quietWindow && quietWindowMissSeeds.size() < MAX_LISTED_SEEDS) {
                quietWindowMissSeeds.add(seed); // the meaningful edge-liveness finding
            }
        }

        double secs = (System.nanoTime() - start) / 1e9;
        double convergenceGivenQuiet =
                quietWindowSeeds == 0 ? 0 : (double) convergedGivenQuiet / quietWindowSeeds;
        System.out.printf(
                "[nightly-edge-integrated] seeds=%d wall=%.1fs (%.2fms/seed) safetyViolations=0"
                        + " cpElected=%d cpStalls=%d quietWindowSeeds=%d"
                        + " convergedGivenQuiet=%d/%d (%.1f%%) rawConverged=%d/%d (%.1f%%)"
                        + " seedsWithDelivery=%d deliveryViolations=%d%n",
                count, secs, secs * 1000 / count, cpLeaderElected, count - cpLeaderElected,
                quietWindowSeeds, convergedGivenQuiet, quietWindowSeeds,
                convergenceGivenQuiet * 100, convergedRaw, count,
                (double) convergedRaw / count * 100, seedsWithDelivery, totalDeliveryViolations);
        System.out.println("[nightly-edge-integrated] cpStallSeeds(first "
                + MAX_LISTED_SEEDS + ")=" + cpStallSeeds);
        System.out.println("[nightly-edge-integrated] quietWindowConvergenceMissSeeds(first "
                + MAX_LISTED_SEEDS + ")=" + quietWindowMissSeeds);

        // Non-vacuity only — liveness is reported, not failed (the gate-sweep discipline).
        assertTrue(seedsWithDelivery > count / 2,
                "the integrated sweep must actually deliver on most seeds (delivered on "
                        + seedsWithDelivery + "/" + count + ")");
        assertTrue(quietWindowSeeds > 0, "the sweep must produce quiet-window seeds");
    }
}
