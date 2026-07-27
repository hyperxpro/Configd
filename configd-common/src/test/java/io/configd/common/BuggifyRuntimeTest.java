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
            assertFalse(BuggifyRuntime.shouldFire("test.point", 1.0));
        }
    }

    @Nested
    class SeedDeterminism {

        @Test
        void sameSeedProducesSameResults() {
            BuggifyRuntime.enableSimulationMode(42L);
            boolean[] run1 = new boolean[50];
            for (int i = 0; i < 50; i++) {
                run1[i] = BuggifyRuntime.shouldFire("seed.test." + i, 1.0);
            }

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
            BuggifyRuntime.enableSimulationMode(1L);
            boolean[] run1 = new boolean[100];
            for (int i = 0; i < 100; i++) {
                run1[i] = BuggifyRuntime.shouldFire("diff.seed." + i, 1.0);
            }

            BuggifyRuntime.enableSimulationMode(999L);
            boolean[] run2 = new boolean[100];
            for (int i = 0; i < 100; i++) {
                run2[i] = BuggifyRuntime.shouldFire("diff.seed." + i, 1.0);
            }

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

    @Nested
    class SimulationModeBehavior {

        @Test
        void shouldFireCanReturnTrueInSimulationMode() {
            BuggifyRuntime.enableSimulationMode(12345L);

            // Each point is enabled with 50% probability; an enabled point with probability 1.0
            // always fires, so across 100 points at least one should fire.
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

            // Querying the same point repeatedly must be consistent, since the enabled/disabled
            // decision is cached (computeIfAbsent) on first use. Using p=1.0 makes the result fully
            // determined by that cached enablement: always false if disabled, always true if enabled.
            String pointId = "deterministic.point";
            boolean firstResult = BuggifyRuntime.shouldFire(pointId, 1.0);
            for (int i = 0; i < 50; i++) {
                boolean result = BuggifyRuntime.shouldFire(pointId, 1.0);
                assertEquals(firstResult, result,
                        "Same pointId with p=1.0 must return same value within a run");
            }
        }

        @Test
        void differentPointIdsCanHaveDifferentActivationStates() {
            BuggifyRuntime.enableSimulationMode(42L);

            Set<Boolean> states = new HashSet<>();
            for (int i = 0; i < 200; i++) {
                states.add(BuggifyRuntime.shouldFire("varied.point." + i, 1.0));
            }
            // Each point is enabled with 50% probability, so with 200 points we expect both
            // true and false to appear.
            assertEquals(2, states.size(),
                    "With enough points, both enabled and disabled states should appear");
        }
    }
}
