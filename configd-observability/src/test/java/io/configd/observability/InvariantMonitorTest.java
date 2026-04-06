package io.configd.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InvariantMonitor} — runtime invariant checking.
 */
class InvariantMonitorTest {

    private MetricsRegistry metrics;

    @BeforeEach
    void setUp() {
        metrics = new MetricsRegistry();
    }

    // -----------------------------------------------------------------------
    // Test mode — violations throw AssertionError
    // -----------------------------------------------------------------------

    @Nested
    class TestMode {

        private InvariantMonitor monitor;

        @BeforeEach
        void setUp() {
            monitor = new InvariantMonitor(metrics, true);
        }

        @Test
        void passingCheckDoesNotThrow() {
            assertDoesNotThrow(() ->
                    monitor.check("version.monotonic", true, "version must increase"));
        }

        @Test
        void failingCheckThrowsAssertionError() {
            AssertionError error = assertThrows(AssertionError.class, () ->
                    monitor.check("version.monotonic", false, "version went backwards"));

            assertTrue(error.getMessage().contains("version.monotonic"));
            assertTrue(error.getMessage().contains("version went backwards"));
        }

        @Test
        void failingCheckRecordsViolation() {
            try {
                monitor.check("version.monotonic", false, "fail");
            } catch (AssertionError ignored) {
                // Expected
            }

            Map<String, Long> violations = monitor.violations();
            assertEquals(1, violations.get("version.monotonic"));
        }

        @Test
        void failingCheckIncrementsMetricsCounter() {
            try {
                monitor.check("version.monotonic", false, "fail");
            } catch (AssertionError ignored) {
                // Expected
            }

            long count = metrics.counter("invariant.violation.version.monotonic").get();
            assertEquals(1, count);
        }
    }

    // -----------------------------------------------------------------------
    // Production mode — violations record silently
    // -----------------------------------------------------------------------

    @Nested
    class ProductionMode {

        private InvariantMonitor monitor;

        @BeforeEach
        void setUp() {
            monitor = new InvariantMonitor(metrics, false);
        }

        @Test
        void passingCheckDoesNotRecord() {
            monitor.check("version.monotonic", true, "all good");
            assertTrue(monitor.violations().isEmpty());
        }

        @Test
        void failingCheckDoesNotThrow() {
            assertDoesNotThrow(() ->
                    monitor.check("version.monotonic", false, "version went backwards"));
        }

        @Test
        void failingCheckRecordsViolation() {
            monitor.check("version.monotonic", false, "fail");
            assertEquals(1, monitor.violations().get("version.monotonic"));
        }

        @Test
        void multipleViolationsAccumulate() {
            monitor.check("version.monotonic", false, "fail 1");
            monitor.check("version.monotonic", false, "fail 2");
            monitor.check("version.monotonic", false, "fail 3");
            assertEquals(3, monitor.violations().get("version.monotonic"));
        }

        @Test
        void differentInvariantsTrackedSeparately() {
            monitor.check("invariant.a", false, "fail");
            monitor.check("invariant.b", false, "fail");
            monitor.check("invariant.a", false, "fail again");

            assertEquals(2, monitor.violations().get("invariant.a"));
            assertEquals(1, monitor.violations().get("invariant.b"));
        }

        @Test
        void failingCheckIncrementsMetricsCounter() {
            monitor.check("my.invariant", false, "fail");
            monitor.check("my.invariant", false, "fail");

            long count = metrics.counter("invariant.violation.my.invariant").get();
            assertEquals(2, count);
        }
    }

    // -----------------------------------------------------------------------
    // Registered invariants and checkAll
    // -----------------------------------------------------------------------

    @Nested
    class RegisteredInvariants {

        @Test
        void checkAllRunsAllRegisteredInvariants() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, false);
            monitor.register("always.true", () -> true, "this always holds");
            monitor.register("always.false", () -> false, "this never holds");

            monitor.checkAll();

            assertTrue(monitor.violations().containsKey("always.false"));
            assertFalse(monitor.violations().containsKey("always.true"));
        }

        @Test
        void checkAllInTestModeThrowsOnFirstFailure() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, true);
            monitor.register("failing", () -> false, "this will fail");

            assertThrows(AssertionError.class, monitor::checkAll);
        }

        @Test
        void registeredInvariantWithDynamicState() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, false);
            AtomicBoolean flag = new AtomicBoolean(true);
            monitor.register("dynamic", flag::get, "flag must be true");

            monitor.checkAll();
            assertTrue(monitor.violations().isEmpty());

            flag.set(false);
            monitor.checkAll();
            assertEquals(1, monitor.violations().get("dynamic"));
        }

        @Test
        void registerReplacesExisting() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, false);
            monitor.register("invariant", () -> true, "original");
            monitor.register("invariant", () -> false, "replaced");

            monitor.checkAll();
            assertEquals(1, monitor.violations().get("invariant"));
        }
    }

    // -----------------------------------------------------------------------
    // Violations map
    // -----------------------------------------------------------------------

    @Nested
    class ViolationsMap {

        @Test
        void emptyWhenNoViolations() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, false);
            assertTrue(monitor.violations().isEmpty());
        }

        @Test
        void violationsMapIsUnmodifiable() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, false);
            monitor.check("test", false, "fail");
            Map<String, Long> violations = monitor.violations();
            assertThrows(UnsupportedOperationException.class,
                    () -> violations.put("hack", 999L));
        }
    }

    // -----------------------------------------------------------------------
    // F-0073: Data-plane invariant helpers (INV-M1 monotonic_read,
    //                                       INV-S1 staleness_bound)
    // -----------------------------------------------------------------------

    @Nested
    class MonotonicRead {

        @Test
        void equalVersionDoesNotFire() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, true);
            assertDoesNotThrow(() -> monitor.assertMonotonicRead("k", 5, 5));
            assertTrue(monitor.violations().isEmpty());
        }

        @Test
        void forwardProgressDoesNotFire() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, true);
            assertDoesNotThrow(() -> monitor.assertMonotonicRead("k", 5, 7));
            assertTrue(monitor.violations().isEmpty());
        }

        @Test
        void backwardVersionFiresInTestMode() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, true);
            AssertionError err = assertThrows(AssertionError.class,
                    () -> monitor.assertMonotonicRead("k", 10, 3));
            assertTrue(err.getMessage().contains("monotonic_read"));
            assertTrue(err.getMessage().contains("seenVersion=10"));
            assertTrue(err.getMessage().contains("newVersion=3"));
        }

        @Test
        void backwardVersionRecordsInProductionMode() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, false);
            monitor.assertMonotonicRead("k", 10, 3);
            assertEquals(1L, monitor.violations().get(InvariantMonitor.MONOTONIC_READ));
            assertEquals(1L, metrics.counter("invariant.violation.monotonic_read").get());
        }
    }

    @Nested
    class StalenessBound {

        @Test
        void withinThresholdDoesNotFire() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, true);
            assertDoesNotThrow(() -> monitor.assertStalenessBound(10, 11, 200, 500));
            assertTrue(monitor.violations().isEmpty());
        }

        @Test
        void exactlyAtThresholdDoesNotFire() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, true);
            assertDoesNotThrow(() -> monitor.assertStalenessBound(10, 11, 500, 500));
            assertTrue(monitor.violations().isEmpty());
        }

        @Test
        void exceedingThresholdFiresInTestMode() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, true);
            AssertionError err = assertThrows(AssertionError.class,
                    () -> monitor.assertStalenessBound(10, 15, 750, 500));
            assertTrue(err.getMessage().contains("staleness_bound"));
            assertTrue(err.getMessage().contains("staleMs=750"));
        }

        @Test
        void exceedingThresholdRecordsInProductionMode() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, false);
            monitor.assertStalenessBound(10, 15, 750, 500);
            assertEquals(1L, monitor.violations().get(InvariantMonitor.STALENESS_BOUND));
            assertEquals(1L, metrics.counter("invariant.violation.staleness_bound").get());
        }
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    @Nested
    class Validation {

        @Test
        void nullMetricsThrows() {
            assertThrows(NullPointerException.class,
                    () -> new InvariantMonitor(null, true));
        }

        @Test
        void nullInvariantNameInCheckThrows() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, false);
            assertThrows(NullPointerException.class,
                    () -> monitor.check(null, true, "msg"));
        }

        @Test
        void nullMessageInCheckThrows() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, false);
            assertThrows(NullPointerException.class,
                    () -> monitor.check("name", true, null));
        }

        @Test
        void blankNameInRegisterThrows() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, false);
            assertThrows(IllegalArgumentException.class,
                    () -> monitor.register("  ", () -> true, "desc"));
        }

        @Test
        void nullCheckInRegisterThrows() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, false);
            assertThrows(NullPointerException.class,
                    () -> monitor.register("name", null, "desc"));
        }

        @Test
        void nullDescriptionInRegisterThrows() {
            InvariantMonitor monitor = new InvariantMonitor(metrics, false);
            assertThrows(NullPointerException.class,
                    () -> monitor.register("name", () -> true, null));
        }
    }
}
