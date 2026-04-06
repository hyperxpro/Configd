package io.configd.edge;

import io.configd.common.Clock;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StalenessTracker} with deterministic time control.
 */
class StalenessTrackerTest {

    /**
     * Simple test clock with explicit time control.
     * Does not depend on configd-testkit.
     */
    static class TestClock implements Clock {
        long timeMs;

        TestClock(long initial) {
            this.timeMs = initial;
        }

        @Override
        public long currentTimeMillis() {
            return timeMs;
        }

        @Override
        public long nanoTime() {
            return timeMs * 1_000_000L;
        }

        void advance(long ms) {
            timeMs += ms;
        }
    }

    private TestClock clock;
    private StalenessTracker tracker;

    @BeforeEach
    void setUp() {
        clock = new TestClock(10_000);
        tracker = new StalenessTracker(clock);
    }

    // -----------------------------------------------------------------------
    // Initial state
    // -----------------------------------------------------------------------

    @Nested
    class InitialState {

        @Test
        void initialStateIsDisconnected() {
            assertEquals(StalenessTracker.State.DISCONNECTED, tracker.currentState());
        }

        @Test
        void initialLastVersionIsZero() {
            assertEquals(0, tracker.lastVersion());
        }
    }

    // -----------------------------------------------------------------------
    // State transitions after recordUpdate
    // -----------------------------------------------------------------------

    @Nested
    class AfterRecordUpdate {

        @Test
        void recordUpdateSetsStateToCurrent() {
            tracker.recordUpdate(1, 10_000);
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());
        }

        @Test
        void stillCurrentAfter499ms() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(499);
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());
        }

        @Test
        void staleAfter501ms() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(501);
            assertEquals(StalenessTracker.State.STALE, tracker.currentState());
        }

        @Test
        void degradedAfter5001ms() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(5001);
            assertEquals(StalenessTracker.State.DEGRADED, tracker.currentState());
        }

        @Test
        void disconnectedAfter30001ms() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(30_001);
            assertEquals(StalenessTracker.State.DISCONNECTED, tracker.currentState());
        }
    }

    // -----------------------------------------------------------------------
    // Reset from any state
    // -----------------------------------------------------------------------

    @Nested
    class ResetFromAnyState {

        @Test
        void recordUpdateResetsFromStale() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(600);
            assertEquals(StalenessTracker.State.STALE, tracker.currentState());

            tracker.recordUpdate(2, 10_600);
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());
        }

        @Test
        void recordUpdateResetsFromDegraded() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(6000);
            assertEquals(StalenessTracker.State.DEGRADED, tracker.currentState());

            tracker.recordUpdate(2, 16_000);
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());
        }

        @Test
        void recordUpdateResetsFromDisconnected() {
            // Tracker starts DISCONNECTED
            assertEquals(StalenessTracker.State.DISCONNECTED, tracker.currentState());

            tracker.recordUpdate(1, 10_000);
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());
        }
    }

    // -----------------------------------------------------------------------
    // Staleness measurement
    // -----------------------------------------------------------------------

    @Nested
    class StalenessMeasurement {

        @Test
        void staleness_msReturnsCorrectElapsedTime() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(250);
            assertEquals(250, tracker.stalenessMs());
        }

        @Test
        void staleness_msIsZeroImmediatelyAfterUpdate() {
            tracker.recordUpdate(1, 10_000);
            assertEquals(0, tracker.stalenessMs());
        }

        @Test
        void staleness_msGrowsWithTime() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(100);
            assertEquals(100, tracker.stalenessMs());
            clock.advance(400);
            assertEquals(500, tracker.stalenessMs());
            clock.advance(500);
            assertEquals(1000, tracker.stalenessMs());
        }
    }

    // -----------------------------------------------------------------------
    // Version tracking
    // -----------------------------------------------------------------------

    @Nested
    class VersionTracking {

        @Test
        void lastVersionTracksLastRecordedVersion() {
            tracker.recordUpdate(5, 10_000);
            assertEquals(5, tracker.lastVersion());
        }

        @Test
        void lastVersionUpdatesOnSubsequentCalls() {
            tracker.recordUpdate(1, 10_000);
            assertEquals(1, tracker.lastVersion());

            tracker.recordUpdate(3, 11_000);
            assertEquals(3, tracker.lastVersion());

            tracker.recordUpdate(10, 12_000);
            assertEquals(10, tracker.lastVersion());
        }
    }

    // -----------------------------------------------------------------------
    // Multiple updates use most recent timestamp
    // -----------------------------------------------------------------------

    @Nested
    class MultipleUpdates {

        @Test
        void multipleUpdatesUseMostRecentTimestamp() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(300);

            // At 300ms after first update, still CURRENT
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());

            // Record another update at 300ms
            tracker.recordUpdate(2, 10_300);

            // Advance another 300ms (600ms total from first, but 300ms from second)
            clock.advance(300);

            // Should still be CURRENT because only 300ms since last update
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());

            // Advance 300ms more (600ms since second update)
            clock.advance(300);
            assertEquals(StalenessTracker.State.STALE, tracker.currentState());
        }
    }

    // -----------------------------------------------------------------------
    // Boundary values
    // -----------------------------------------------------------------------

    @Nested
    class BoundaryValues {

        @Test
        void exactlyAtStaleThresholdIsCurrent() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(500);
            // Exactly 500ms -> threshold is "> 500ms", so this is CURRENT
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());
        }

        @Test
        void exactlyAtDegradedThresholdIsStale() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(5000);
            // Exactly 5000ms -> threshold is "> 5000ms", so this is STALE
            assertEquals(StalenessTracker.State.STALE, tracker.currentState());
        }

        @Test
        void exactlyAtDisconnectedThresholdIsDegraded() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(30_000);
            // Exactly 30000ms -> threshold is "> 30000ms", so this is DEGRADED
            assertEquals(StalenessTracker.State.DEGRADED, tracker.currentState());
        }
    }

    // -----------------------------------------------------------------------
    // F-0073: InvariantMonitor wiring for INV-S1 staleness_bound
    // -----------------------------------------------------------------------

    @Nested
    class InvariantMonitorWiring {

        @Test
        void isStaleDoesNotFireMonitorWhenUnderThreshold() {
            MetricsRegistry metrics = new MetricsRegistry();
            InvariantMonitor monitor = new InvariantMonitor(metrics, false);
            StalenessTracker tracker = new StalenessTracker(clock, monitor);

            tracker.recordUpdate(1, clock.currentTimeMillis());
            clock.advance(300);

            assertFalse(tracker.isStale(500));
            assertTrue(monitor.violations().isEmpty());
        }

        @Test
        void isStaleFiresMonitorWhenOverThreshold() {
            MetricsRegistry metrics = new MetricsRegistry();
            InvariantMonitor monitor = new InvariantMonitor(metrics, false);
            StalenessTracker tracker = new StalenessTracker(clock, monitor);

            tracker.recordUpdate(1, clock.currentTimeMillis());
            tracker.observeRemoteVersion(2);
            clock.advance(750);

            assertTrue(tracker.isStale(500));
            assertEquals(1L,
                    monitor.violations().get(InvariantMonitor.STALENESS_BOUND),
                    "staleness_bound must fire once when staleMs > threshold");
            assertEquals(1L,
                    metrics.counter("invariant.violation.staleness_bound").get());
        }

        @Test
        void isStaleInTestModeThrows() {
            MetricsRegistry metrics = new MetricsRegistry();
            InvariantMonitor monitor = new InvariantMonitor(metrics, true);
            StalenessTracker tracker = new StalenessTracker(clock, monitor);

            tracker.recordUpdate(1, clock.currentTimeMillis());
            clock.advance(750);

            assertThrows(AssertionError.class, () -> tracker.isStale(500));
        }

        @Test
        void noMonitorIsTolerated() {
            // Backwards compatibility: constructing without a monitor works.
            StalenessTracker tracker = new StalenessTracker(clock);
            tracker.recordUpdate(1, clock.currentTimeMillis());
            clock.advance(750);
            assertTrue(tracker.isStale(500));
        }
    }
}
