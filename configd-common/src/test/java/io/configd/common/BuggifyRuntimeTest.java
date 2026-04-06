package io.configd.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BuggifyRuntimeTest {

    @AfterEach
    void resetToProductionMode() {
        BuggifyRuntime.disableSimulationMode();
    }

    // ── Production mode (simulation not enabled) ────────────────────────

    @Nested
    class ProductionMode {

        @Test
        void shouldFireReturnsFalseInProductionMode() {
            assertFalse(BuggifyRuntime.shouldFire("test.point", 1.0));
        }

        @Test
        void shouldFireWithDefaultProbabilityReturnsFalseInProductionMode() {
            assertFalse(BuggifyRuntime.shouldFire("test.point"));
        }

        @Test
        void isSimulationModeReturnsFalseByDefault() {
            assertFalse(BuggifyRuntime.isSimulationMode());
        }
    }

    // ── Simulation mode lifecycle ───────────────────────────────────────

    @Nested
    class SimulationModeLifecycle {

        @Test
        void enableSimulationModeSetsFlag() {
            BuggifyRuntime.enableSimulationMode(42L);
            assertTrue(BuggifyRuntime.isSimulationMode());
        }

        @Test
        void disableSimulationModeClearsFlag() {
            BuggifyRuntime.enableSimulationMode(42L);
            BuggifyRuntime.disableSimulationMode();
            assertFalse(BuggifyRuntime.isSimulationMode());
        }

        @Test
        void shouldFireReturnsFalseAfterDisable() {
            BuggifyRuntime.enableSimulationMode(42L);
            BuggifyRuntime.disableSimulationMode();
            // Even with probability 1.0, should never fire in production mode
            assertFalse(BuggifyRuntime.shouldFire("test.point", 1.0));
        }
    }

    // ── Seed determinism (CRITICAL-3 fix) ─────────────────────────────

    @Nested
    class SeedDeterminism {

        @Test
        void sameSeedProducesSameResults() {
            // Run 1: collect results for 50 points with seed 42
            BuggifyRuntime.enableSimulationMode(42L);
            boolean[] run1 = new boolean[50];
            for (int i = 0; i < 50; i++) {
                run1[i] = BuggifyRuntime.shouldFire("seed.test." + i, 1.0);
            }

            // Run 2: re-enable with same seed, collect results again
            BuggifyRuntime.enableSimulationMode(42L);
            boolean[] run2 = new boolean[50];
            for (int i = 0; i < 50; i++) {
                run2[i] = BuggifyRuntime.shouldFire("seed.test." + i, 1.0);
            }

            assertArrayEquals(run1, run2,
                    "Same seed must produce identical activation decisions");
        }

        @Test
        void differentSeedsProduceDifferentResults() {
            // Run with seed 1
            BuggifyRuntime.enableSimulationMode(1L);
            boolean[] run1 = new boolean[100];
            for (int i = 0; i < 100; i++) {
                run1[i] = BuggifyRuntime.shouldFire("diff.seed." + i, 1.0);
            }

            // Run with seed 999
            BuggifyRuntime.enableSimulationMode(999L);
            boolean[] run2 = new boolean[100];
            for (int i = 0; i < 100; i++) {
                run2[i] = BuggifyRuntime.shouldFire("diff.seed." + i, 1.0);
            }

            // With different seeds and 100 points, results should differ
            boolean anyDifferent = false;
            for (int i = 0; i < 100; i++) {
                if (run1[i] != run2[i]) {
                    anyDifferent = true;
                    break;
                }
            }
            assertTrue(anyDifferent,
                    "Different seeds should produce different activation decisions");
        }
    }

    // ── Simulation mode behavior ────────────────────────────────────────

    @Nested
    class SimulationModeBehavior {

        @Test
        void shouldFireCanReturnTrueInSimulationMode() {
            BuggifyRuntime.enableSimulationMode(12345L);

            // With probability 1.0 and enough different points, at least one should fire.
            // By design, each point is 50% likely to be enabled, and enabled points
            // with probability 1.0 always fire.
            boolean anyFired = false;
            for (int i = 0; i < 100; i++) {
                if (BuggifyRuntime.shouldFire("point." + i, 1.0)) {
                    anyFired = true;
                    break;
                }
            }
            assertTrue(anyFired, "At least one point should fire with p=1.0 across 100 points");
        }

        @Test
        void samePointIdReturnsDeterministicEnablementWithinRun() {
            BuggifyRuntime.enableSimulationMode(99L);

            // Query the same point many times -- the enabled/disabled decision
            // should be consistent (computeIfAbsent caches the result).
            // We use p=1.0 so that if the point is enabled, it always fires,
            // and if disabled, it never fires. This tests the per-point
            // determinism, not the per-call probability.
            String pointId = "deterministic.point";
            boolean firstResult = BuggifyRuntime.shouldFire(pointId, 1.0);
            for (int i = 0; i < 50; i++) {
                boolean result = BuggifyRuntime.shouldFire(pointId, 1.0);
                // Both true (always fires) or the first was false (point disabled)
                // and subsequent may vary due to probability random -- but with p=1.0,
                // if disabled, always false; if enabled, could be true.
                // Actually: if point is disabled, shouldFire returns false immediately.
                // If point is enabled, random.nextDouble() < 1.0 is always true.
                // So with p=1.0, the result is fully determined by enablement.
                assertEquals(firstResult, result,
                        "Same pointId with p=1.0 must return same value within a run");
            }
        }

        @Test
        void differentPointIdsCanHaveDifferentActivationStates() {
            BuggifyRuntime.enableSimulationMode(42L);

            // Collect activation states for many points (p=1.0 so result == enablement)
            Set<Boolean> states = new HashSet<>();
            for (int i = 0; i < 200; i++) {
                states.add(BuggifyRuntime.shouldFire("varied.point." + i, 1.0));
            }
            // With 200 points and 50% enablement probability,
            // we expect both true and false to appear.
            assertEquals(2, states.size(),
                    "With enough points, both enabled and disabled states should appear");
        }
    }
}
