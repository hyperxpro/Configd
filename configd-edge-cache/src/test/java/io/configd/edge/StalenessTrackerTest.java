package io.configd.edge;

import io.configd.common.Clock;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StalenessTracker} and its <b>covered-frontier</b> staleness model:
 * {@code staleness = wall_now - frontier}, where
 * {@code frontier = max(commit_ts(last applied), server_now(last cursor-matched HEARTBEAT))}.
 *
 * <p>Key properties pinned here:
 * <ul>
 *   <li>threshold transitions are driven by withholding BOTH deltas and heartbeats (a true
 *       stall - the frontier freezes and wall-now marches past it);</li>
 *   <li>an idle-but-heartbeating edge ({@code latestSeq == cursor}) stays CURRENT
 *       indefinitely (the pre-covered-frontier idle-time proxy would have walked it to
 *       DISCONNECTED and triggered spurious re-bootstraps);</li>
 *   <li>a heartbeat with {@code latestSeq > cursor} does NOT advance the frontier (the edge
 *       is genuinely behind - data age is real lag);</li>
 *   <li>the implausibility tripwire (future-frontier / regression) counts + clamps
 *       (see {@link StalenessSkewTripwireTest}).</li>
 * </ul>
 * Threshold table (500ms / 5s / 30s) drives the state transitions exercised below.
 */
class StalenessTrackerTest {

    /** Simple test clock with explicit millis control (no configd-testkit dependency). */
    static class TestClock implements Clock {
        long timeMs;
        TestClock(long initial) { this.timeMs = initial; }
        @Override public long currentTimeMillis() { return timeMs; }
        @Override public long nanoTime() { return timeMs * 1_000_000L; }
        void advance(long ms) { timeMs += ms; }
    }

    private TestClock clock;
    private StalenessTracker tracker;

    @BeforeEach
    void setUp() {
        clock = new TestClock(10_000);
        tracker = new StalenessTracker(clock);
    }

    @Nested
    class InitialState {

        @Test
        void initialStateIsDisconnected() {
            // No frontier known yet - the edge has covered nothing.
            assertEquals(StalenessTracker.State.DISCONNECTED, tracker.currentState());
        }

        @Test
        void initialLastVersionIsZero() {
            assertEquals(0, tracker.lastVersion());
        }
    }

    @Nested
    class AfterRecordUpdate {

        @Test
        void recordUpdateSetsStateToCurrent() {
            tracker.recordUpdate(1, 10_000);
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());
        }

        @Test
        void stillCurrentAfter499msWithoutAnyUpdateOrHeartbeat() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(499); // withhold BOTH deltas and heartbeats - a true stall
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());
        }

        @Test
        void staleAfter501msStall() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(501);
            assertEquals(StalenessTracker.State.STALE, tracker.currentState());
        }

        @Test
        void degradedAfter5001msStall() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(5001);
            assertEquals(StalenessTracker.State.DEGRADED, tracker.currentState());
        }

        @Test
        void disconnectedAfter30001msStall() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(30_001);
            assertEquals(StalenessTracker.State.DISCONNECTED, tracker.currentState());
        }

        @Test
        void stalenessUsesCommitTimestampNotRecordTime() {
            // Commit ts LAGS wall-now by 300ms at record time (network/propagation latency):
            // staleness is measured from the commit ts (data age), not the local record time.
            clock.timeMs = 10_300;
            tracker.recordUpdate(1, 10_000); // commit ts 10_000, wall-now 10_300
            assertEquals(300, tracker.stalenessMs(),
                    "staleness must be wall_now − commit_ts (ADR-0039 data-age term)");
        }
    }

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
            assertEquals(StalenessTracker.State.DISCONNECTED, tracker.currentState());
            tracker.recordUpdate(1, 10_000);
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());
        }
    }

    @Nested
    class HeartbeatFrontier {

        /**
         * Regression test: an idle-but-heartbeating edge ({@code latestSeq == cursor}) stays
         * CURRENT indefinitely. The pre-covered-frontier idle-time proxy would have walked this
         * edge CURRENT to STALE to DEGRADED to DISCONNECTED and triggered a needless
         * re-bootstrap storm.
         */
        @Test
        void idleButHeartbeatingEdgeStaysCurrentIndefinitely() {
            long cursor = 5;
            tracker.recordUpdate(cursor, 10_000); // applied up to seq 5 at commit ts 10_000

            // No new deltas for a long time, but the server keeps heartbeating "you're
            // caught up" (latestSeq == cursor) with its advancing clock.
            for (int i = 0; i < 1000; i++) {
                clock.advance(250); // a heartbeat interval
                long serverNow = clock.currentTimeMillis();
                boolean advanced = tracker.recordFrontier(cursor, cursor, serverNow);
                assertTrue(advanced, "a cursor-matched heartbeat must advance the frontier");
                assertEquals(StalenessTracker.State.CURRENT, tracker.currentState(),
                        "an idle-but-heartbeating edge must stay CURRENT (ADR-0039)");
            }
            // 1000 x 250ms = 250s of idle time, yet CURRENT throughout.
        }

        @Test
        void heartbeatWithLatestSeqGreaterThanCursorDoesNotAdvanceFrontier() {
            long cursor = 5;
            tracker.recordUpdate(cursor, 10_000);
            clock.advance(600); // would be STALE without a frontier advance

            // Server says latestSeq=7 > cursor=5 - the edge is genuinely behind (2 seqs).
            boolean advanced = tracker.recordFrontier(7, cursor, clock.currentTimeMillis());
            assertFalse(advanced, "latestSeq > cursor must NOT advance the frontier");
            assertEquals(StalenessTracker.State.STALE, tracker.currentState(),
                    "a behind edge's staleness must reflect real data-age lag, not reset");
        }

        @Test
        void heartbeatDoesNotRegressTheFrontierBelowAnAppliedDelta() {
            // Applied a delta whose commit ts is AHEAD of a later heartbeat's serverNow
            // (e.g. the heartbeat came from a node with a slightly behind clock). The
            // frontier must not regress.
            clock.timeMs = 11_000;
            tracker.recordUpdate(3, 11_000); // frontier = 11_000
            // A cursor-matched heartbeat with an EARLIER serverNow - refused as a regression.
            tracker.recordFrontier(3, 3, 10_900);
            assertEquals(0, tracker.stalenessMs(),
                    "frontier must hold at 11_000 (the heartbeat regression is refused)");
        }
    }

    @Nested
    class StalenessMeasurement {

        @Test
        void stalenessMsReflectsWallMinusFrontier() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(250);
            assertEquals(250, tracker.stalenessMs());
        }

        @Test
        void stalenessMsIsZeroImmediatelyAfterUpdate() {
            tracker.recordUpdate(1, 10_000);
            assertEquals(0, tracker.stalenessMs());
        }

        @Test
        void stalenessMsGrowsWithTimeDuringAStall() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(100);
            assertEquals(100, tracker.stalenessMs());
            clock.advance(400);
            assertEquals(500, tracker.stalenessMs());
            clock.advance(500);
            assertEquals(1000, tracker.stalenessMs());
        }

        @Test
        void stalenessNeverNegative() {
            // A frontier slightly ahead of wall-now (within skew allowance) clamps to 0.
            clock.timeMs = 10_000;
            tracker.recordUpdate(1, 10_030); // 30ms ahead - within the 50ms skew allowance
            assertEquals(0, tracker.stalenessMs());
        }
    }

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

    @Nested
    class BoundaryValues {

        @Test
        void exactlyAtStaleThresholdIsCurrent() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(500); // threshold is "> 500ms" -> still CURRENT
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());
        }

        @Test
        void exactlyAtDegradedThresholdIsStale() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(5000);
            assertEquals(StalenessTracker.State.STALE, tracker.currentState());
        }

        @Test
        void exactlyAtDisconnectedThresholdIsDegraded() {
            tracker.recordUpdate(1, 10_000);
            clock.advance(30_000);
            assertEquals(StalenessTracker.State.DEGRADED, tracker.currentState());
        }
    }

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
            assertEquals(1L, monitor.violations().get(InvariantMonitor.STALENESS_BOUND),
                    "staleness_bound must fire once when staleMs > threshold");
            assertEquals(1L, metrics.counter("invariant.violation.staleness_bound").get());
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
            StalenessTracker tracker = new StalenessTracker(clock);
            tracker.recordUpdate(1, clock.currentTimeMillis());
            clock.advance(750);
            assertTrue(tracker.isStale(500));
        }
    }
}
