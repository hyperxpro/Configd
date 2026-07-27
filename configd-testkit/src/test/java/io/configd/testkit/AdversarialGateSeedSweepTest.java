package io.configd.testkit;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdversarialGateSeedSweepTest {

    private static final String MANIFEST = "/gate/adversarial-gate-seeds.txt";
    private static final int NODES = 5;
    private static final int TICKS = 1_200;

    @Test
    void allGateSeedsHoldEverySafetyInvariant() {
        List<Long> seeds = loadSeeds();
        assertTrue(seeds.size() >= 500,
                "gate seed set must have >=500 seeds, has " + seeds.size());

        int leaderElected = 0;
        int faultsExercised = 0;
        for (long seed : seeds) {
            AdversarialSim sim = new AdversarialSim(seed, NODES, TICKS);
            // Throws SimInvariants.SafetyViolation (carrying the seed) on any breach.
            sim.run();
            if (sim.activity().leaderElected()) {
                leaderElected++;
            }
            if (sim.activity().faultsFired() > 0) {
                faultsExercised++;
            }
        }

        // Vacuity guard: the sweep must do
        // real work. Faults fire on essentially every seed; most must still elect.
        assertEquals(seeds.size(), faultsExercised,
                "every gate seed must exercise faults (else the gate is vacuous)");
        double electRate = (double) leaderElected / seeds.size();
        assertTrue(electRate >= 0.5,
                "Most gate seeds must still elect under faults (liveness sanity); got "
                        + leaderElected + "/" + seeds.size());
    }

    private static List<Long> loadSeeds() {
        List<Long> seeds = new ArrayList<>();
        try (InputStream in = AdversarialGateSeedSweepTest.class.getResourceAsStream(MANIFEST)) {
            assertNotNull(in, "gate seed manifest not found on classpath: " + MANIFEST);
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
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
