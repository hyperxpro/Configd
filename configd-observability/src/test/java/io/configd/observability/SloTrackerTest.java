package io.configd.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SloTracker} — SLO/SLI tracking with sliding windows.
 */
class SloTrackerTest {

    private SloTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new SloTracker();
    }

    // -----------------------------------------------------------------------
    // Definition
    // -----------------------------------------------------------------------

    @Nested
    class Definition {

        @Test
        void defineSloSucceeds() {
            assertDoesNotThrow(() ->
                    tracker.defineSlo("availability", 0.999, Duration.ofMinutes(30)));
        }

        @Test
        void defineSloWithZeroTargetSucceeds() {
            assertDoesNotThrow(() ->
                    tracker.defineSlo("anything", 0.0, Duration.ofMinutes(1)));
        }

        @Test
        void defineSloWithPerfectTargetSucceeds() {
            assertDoesNotThrow(() ->
                    tracker.defineSlo("perfect", 1.0, Duration.ofMinutes(1)));
        }

        @Test
        void defineSloNullNameThrows() {
            assertThrows(NullPointerException.class, () ->
                    tracker.defineSlo(null, 0.999, Duration.ofMinutes(30)));
        }

        @Test
        void defineSloBlankNameThrows() {
            assertThrows(IllegalArgumentException.class, () ->
                    tracker.defineSlo("  ", 0.999, Duration.ofMinutes(30)));
        }

        @Test
        void defineSloNegativeTargetThrows() {
            assertThrows(IllegalArgumentException.class, () ->
                    tracker.defineSlo("slo", -0.1, Duration.ofMinutes(30)));
        }

        @Test
        void defineSloTargetAboveOneThrows() {
            assertThrows(IllegalArgumentException.class, () ->
                    tracker.defineSlo("slo", 1.1, Duration.ofMinutes(30)));
        }

        @Test
        void defineSloZeroDurationThrows() {
            assertThrows(IllegalArgumentException.class, () ->
                    tracker.defineSlo("slo", 0.999, Duration.ZERO));
        }

        @Test
        void defineSloNegativeDurationThrows() {
            assertThrows(IllegalArgumentException.class, () ->
                    tracker.defineSlo("slo", 0.999, Duration.ofMillis(-100)));
        }

        @Test
        void defineSloNullDurationThrows() {
            assertThrows(NullPointerException.class, () ->
                    tracker.defineSlo("slo", 0.999, null));
        }
    }

    // -----------------------------------------------------------------------
    // Compliance — basic
    // -----------------------------------------------------------------------

    @Nested
    class ComplianceBasic {

        @Test
        void noEventsReturnsFullCompliance() {
            tracker.defineSlo("avail", 0.999, Duration.ofHours(1));
            assertEquals(1.0, tracker.compliance("avail"));
        }

        @Test
        void allSuccessReturnsFullCompliance() {
            tracker.defineSlo("avail", 0.999, Duration.ofHours(1));
            for (int i = 0; i < 100; i++) {
                tracker.recordSuccess("avail");
            }
            assertEquals(1.0, tracker.compliance("avail"));
        }

        @Test
        void allFailureReturnsZeroCompliance() {
            tracker.defineSlo("avail", 0.999, Duration.ofHours(1));
            for (int i = 0; i < 100; i++) {
                tracker.recordFailure("avail");
            }
            assertEquals(0.0, tracker.compliance("avail"));
        }

        @Test
        void mixedEventsReturnsCorrectRatio() {
            tracker.defineSlo("avail", 0.99, Duration.ofHours(1));

            // 90 successes, 10 failures = 90% compliance
            for (int i = 0; i < 90; i++) {
                tracker.recordSuccess("avail");
            }
            for (int i = 0; i < 10; i++) {
                tracker.recordFailure("avail");
            }

            assertEquals(0.9, tracker.compliance("avail"), 0.001);
        }

        @Test
        void undefinedSloThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> tracker.compliance("nonexistent"));
        }
    }

    // -----------------------------------------------------------------------
    // Breaching
    // -----------------------------------------------------------------------

    @Nested
    class Breaching {

        @Test
        void notBreachingWhenAboveTarget() {
            tracker.defineSlo("avail", 0.9, Duration.ofHours(1));
            for (int i = 0; i < 95; i++) {
                tracker.recordSuccess("avail");
            }
            for (int i = 0; i < 5; i++) {
                tracker.recordFailure("avail");
            }

            assertFalse(tracker.isBreaching("avail"));
        }

        @Test
        void breachingWhenBelowTarget() {
            tracker.defineSlo("avail", 0.99, Duration.ofHours(1));
            for (int i = 0; i < 90; i++) {
                tracker.recordSuccess("avail");
            }
            for (int i = 0; i < 10; i++) {
                tracker.recordFailure("avail");
            }

            assertTrue(tracker.isBreaching("avail"));
        }

        @Test
        void notBreachingWithNoEvents() {
            tracker.defineSlo("avail", 0.999, Duration.ofHours(1));
            assertFalse(tracker.isBreaching("avail"));
        }

        @Test
        void undefinedSloThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> tracker.isBreaching("nonexistent"));
        }
    }

    // -----------------------------------------------------------------------
    // Recording events for undefined SLO
    // -----------------------------------------------------------------------

    @Nested
    class UndefinedSloEvents {

        @Test
        void recordSuccessForUndefinedSloThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> tracker.recordSuccess("nonexistent"));
        }

        @Test
        void recordFailureForUndefinedSloThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> tracker.recordFailure("nonexistent"));
        }
    }

    // -----------------------------------------------------------------------
    // Snapshot
    // -----------------------------------------------------------------------

    @Nested
    class SnapshotTests {

        @Test
        void snapshotContainsAllDefinedSlos() {
            tracker.defineSlo("avail", 0.999, Duration.ofHours(1));
            tracker.defineSlo("latency", 0.95, Duration.ofMinutes(5));

            Map<String, SloTracker.SloStatus> snapshot = tracker.snapshot();
            assertEquals(2, snapshot.size());
            assertTrue(snapshot.containsKey("avail"));
            assertTrue(snapshot.containsKey("latency"));
        }

        @Test
        void snapshotReflectsCurrentState() {
            tracker.defineSlo("avail", 0.99, Duration.ofHours(1));
            for (int i = 0; i < 95; i++) {
                tracker.recordSuccess("avail");
            }
            for (int i = 0; i < 5; i++) {
                tracker.recordFailure("avail");
            }

            SloTracker.SloStatus status = tracker.snapshot().get("avail");
            assertNotNull(status);
            assertEquals("avail", status.name());
            assertEquals(0.99, status.target());
            assertEquals(0.95, status.current(), 0.001);
            assertEquals(100, status.totalEvents());
            assertEquals(5, status.failedEvents());
            assertTrue(status.breaching()); // 0.95 < 0.99 target
        }

        @Test
        void emptySnapshot() {
            Map<String, SloTracker.SloStatus> snapshot = tracker.snapshot();
            assertTrue(snapshot.isEmpty());
        }

        @Test
        void snapshotIsUnmodifiable() {
            tracker.defineSlo("avail", 0.999, Duration.ofHours(1));
            Map<String, SloTracker.SloStatus> snapshot = tracker.snapshot();
            assertThrows(UnsupportedOperationException.class,
                    () -> snapshot.put("hack", new SloTracker.SloStatus("hack", 0.5, 0.5, 0, 0, false)));
        }
    }

    // -----------------------------------------------------------------------
    // Redefining an SLO clears history
    // -----------------------------------------------------------------------

    @Nested
    class Redefine {

        @Test
        void redefiningSloClearsHistory() {
            tracker.defineSlo("avail", 0.99, Duration.ofHours(1));
            for (int i = 0; i < 100; i++) {
                tracker.recordFailure("avail");
            }
            assertEquals(0.0, tracker.compliance("avail"));

            // Redefine clears events
            tracker.defineSlo("avail", 0.95, Duration.ofMinutes(30));
            assertEquals(1.0, tracker.compliance("avail"));
        }
    }

    // -----------------------------------------------------------------------
    // Multiple SLOs
    // -----------------------------------------------------------------------

    @Nested
    class MultipleSlos {

        @Test
        void independentSloTracking() {
            tracker.defineSlo("avail", 0.999, Duration.ofHours(1));
            tracker.defineSlo("latency", 0.95, Duration.ofMinutes(5));

            for (int i = 0; i < 100; i++) {
                tracker.recordSuccess("avail");
            }
            for (int i = 0; i < 50; i++) {
                tracker.recordSuccess("latency");
                tracker.recordFailure("latency");
            }

            assertEquals(1.0, tracker.compliance("avail"));
            assertEquals(0.5, tracker.compliance("latency"), 0.001);
        }
    }
}
