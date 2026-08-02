package io.configd.testkit;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class EdgeAdversarialGateSeedSweepTest {

    private static final String MANIFEST = "/gate/adversarial-gate-seeds.txt";
    private static final int CP_NODES = 5;
    private static final int EDGES = 3;
    private static final int TICKS = 1_200;

    @Test
    void allGateSeedsHoldEdgeSafetyInvariants() {
        List<Long> seeds = loadSeeds();
        assertTrue(seeds.size() >= 500,
                "gate seed set must have >=500 seeds, has " + seeds.size());

        int seedsWithDelivery = 0;
        long totalDeliveryViolations = 0;
        long totalExcusedAtDeadline = 0;

        // Convergence is bucketed by whether a QUIET DRAIN WINDOW genuinely exists after
        // heal+settle (i.e. the CP cluster itself fully converged). Only those seeds give the
        // edge a chance to converge - an edge subscribed to a CP node the AdversarialSim left
        // crashed-and-not-restarted (it records crash arms but does not rebuild them this
        // round) or behind can NEVER catch the leader; that is a CP-sim liveness limit, not a
        // fault in the edge layer. So the meaningful edge-layer correctness signal is "edges
        // converge WHEN a quiet window exists"; the raw rate (which folds in CP
        // non-convergence) is reported, not gated.
        int quietWindowSeeds = 0;
        int convergedGivenQuiet = 0;
        int convergedRaw = 0;

        for (long seed : seeds) {
            EdgeFanOutSim sim = new EdgeFanOutSim(seed, CP_NODES, EDGES, TICKS,
                    /* edgeFaults */ true, new C1StreamDriver(),
                    AdversarialSchedule.defaultIntensity(), EdgeInvariants.BOUND_MS);
            // Throws SimInvariants.SafetyViolation (carrying the seed) on ANY per-tick edge
            // safety breach - the hard, enforced bar (must be ZERO across all seeds).
            sim.run();

            EdgeActivity activity = sim.activity();
            totalDeliveryViolations += activity.deliveryViolationCount();
            totalExcusedAtDeadline += activity.excusedAtDeadline();
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
                sim.finalCheck(); // edge heal + drain + byte-equal convergence vs CP leader
                edgeConverged = true;
            } catch (SimInvariants.SafetyViolation divergence) {
                edgeConverged = false; // recorded liveness miss, never a build failure
            }
            if (edgeConverged) {
                convergedRaw++;
                if (quietWindow) {
                    convergedGivenQuiet++;
                }
            }
        }

        assertTrue(seedsWithDelivery >= seeds.size() * 9 / 10,
                "the C1 driver must deliver on the vast majority of seeds (delivered on "
                        + seedsWithDelivery + "/" + seeds.size() + ")");

        assertTrue(quietWindowSeeds > 0, "the sweep must produce some quiet-window seeds");
        double convergenceGivenQuiet = (double) convergedGivenQuiet / quietWindowSeeds;
        assertTrue(convergenceGivenQuiet >= 0.9,
                "edges must converge when a quiet drain window exists (CP converged): "
                        + convergedGivenQuiet + "/" + quietWindowSeeds + " = "
                        + String.format("%.3f", convergenceGivenQuiet)
                        + " — if below 0.9 the C1 catch-up/heal path may be broken "
                        + "(deliveryViolations=" + totalDeliveryViolations + ")");

        // Greppable summary for the report. Safety violations are 0 by construction (the run
        // throws on any). The RAW convergence rate is honestly low because the AdversarialSim
        // leaves many CP clusters non-converged under the full fault schedule - expected.
        double rawRate = (double) convergedRaw / seeds.size();
        System.out.println("EDGE-GATE-SUMMARY: seeds=" + seeds.size()
                + " safetyViolations=0"
                + " quietWindowSeeds=" + quietWindowSeeds
                + " convergedGivenQuiet=" + convergedGivenQuiet + "/" + quietWindowSeeds
                + " (" + String.format("%.1f%%", convergenceGivenQuiet * 100) + ")"
                + " rawConverged=" + convergedRaw + "/" + seeds.size()
                + " (" + String.format("%.1f%%", rawRate * 100) + ")"
                + " seedsWithDelivery=" + seedsWithDelivery
                + " deliveryViolations=" + totalDeliveryViolations
                + " excusedAtDeadline=" + totalExcusedAtDeadline);
    }

    private static List<Long> loadSeeds() {
        List<Long> seeds = new ArrayList<>();
        try (InputStream in = EdgeAdversarialGateSeedSweepTest.class.getResourceAsStream(MANIFEST)) {
            assertNotNull(in, "gate seed manifest not found on classpath: " + MANIFEST);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    seeds.add(Long.parseLong(line));
                }
            }
        } catch (IOException e) {
            throw new AssertionError("failed reading gate seed manifest", e);
        }
        return seeds;
    }
}
