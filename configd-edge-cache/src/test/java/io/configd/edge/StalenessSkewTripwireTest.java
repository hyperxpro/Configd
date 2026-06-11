package io.configd.edge;

import io.configd.common.Clock;
import io.configd.observability.MetricsRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CT-08 — the ADR-0039 §5 / ADR-0035 handoff-item-4 implausibility tripwire.
 *
 * <p>A frontier in the future beyond the documented ≤50ms NTP-skew allowance, or a frontier
 * that would jump backwards, is flagged on a dedicated counter
 * ({@code edge_staleness_implausible_total}) and the offending sample is clamped — never
 * silently trusted. A skewed or lying clock must be visible.
 *
 * <p>Verifies: within-skew future frontier clamps to 0 WITHOUT counting; beyond-skew future
 * frontier counts + clamps; a backwards frontier (regression) counts + holds; the counter is
 * the wired {@link MetricsRegistry.Counter} (so Session 6 can alert on it).
 */
class StalenessSkewTripwireTest {

    static final class TestClock implements Clock {
        long timeMs;
        TestClock(long initial) { this.timeMs = initial; }
        @Override public long currentTimeMillis() { return timeMs; }
        @Override public long nanoTime() { return timeMs * 1_000_000L; }
    }

    private TestClock clock;
    private MetricsRegistry metrics;
    private MetricsRegistry.Counter implausible;
    private StalenessTracker tracker;

    @BeforeEach
    void setUp() {
        clock = new TestClock(1_000_000);
        metrics = new MetricsRegistry();
        implausible = metrics.counter(StalenessTracker.IMPLAUSIBLE_METRIC);
        tracker = new StalenessTracker(clock, null, implausible);
    }

    // -----------------------------------------------------------------------
    // Future-frontier (negative staleness)
    // -----------------------------------------------------------------------

    @Test
    void withinSkewAllowanceFutureFrontierClampsWithoutCounting() {
        // 50ms ahead: exactly at the allowance — tolerated as clock skew, clamped, NOT counted.
        tracker.recordUpdate(1, clock.timeMs + 50);
        assertEquals(0, tracker.stalenessMs(), "within-skew future frontier clamps to 0");
        assertEquals(0L, implausible.get(), "within-skew skew must NOT count as implausible");
    }

    @Test
    void beyondSkewFutureFrontierCountsAndClamps() {
        // 51ms ahead: beyond the allowance — a leader/relay clock ahead of ours. Counted +
        // clamped (staleness 0), never reported as a negative staleness.
        tracker.recordUpdate(1, clock.timeMs + 51);
        assertEquals(0, tracker.stalenessMs(), "beyond-skew future frontier clamps to 0");
        assertEquals(1L, implausible.get(), "beyond-skew future frontier must count once");
        assertEquals(1L, metrics.counter(StalenessTracker.IMPLAUSIBLE_METRIC).get(),
                "the implausible counter must be the wired registry counter");
    }

    @Test
    void grosslyFutureFrontierViaHeartbeatCountsAndClamps() {
        long cursor = 5;
        tracker.recordUpdate(cursor, clock.timeMs); // frontier = now
        // A lying/skewed relay heartbeat asserting a serverNow 10s in our future.
        tracker.recordFrontier(cursor, cursor, clock.timeMs + 10_000);
        assertEquals(0, tracker.stalenessMs());
        assertEquals(1L, implausible.get());
    }

    // -----------------------------------------------------------------------
    // Frontier regression (backwards jump)
    // -----------------------------------------------------------------------

    @Test
    void backwardsFrontierViaUpdateCountsAndHolds() {
        tracker.recordUpdate(2, clock.timeMs);       // frontier = now (staleness 0)
        clock.timeMs += 1_000;                        // wall advances 1s → staleness 1000
        // A re-ordered/older commit ts that would move the frontier back — refused.
        tracker.recordUpdate(3, clock.timeMs - 5_000);
        assertEquals(1_000, tracker.stalenessMs(),
                "frontier must hold at the earlier-but-higher value (regression refused)");
        assertEquals(1L, implausible.get());
    }

    @Test
    void backwardsFrontierViaHeartbeatCountsAndHolds() {
        long cursor = 7;
        tracker.recordUpdate(cursor, clock.timeMs);  // frontier = now
        clock.timeMs += 500;
        // A cursor-matched heartbeat carrying a serverNow BEHIND our current frontier.
        tracker.recordFrontier(cursor, cursor, clock.timeMs - 800);
        assertEquals(500, tracker.stalenessMs(), "frontier holds; regression refused");
        assertEquals(1L, implausible.get());
    }

    // -----------------------------------------------------------------------
    // No counter wired: still clamps, just does not count
    // -----------------------------------------------------------------------

    @Test
    void noCounterStillClampsImplausibleSamples() {
        StalenessTracker noCounter = new StalenessTracker(clock);
        noCounter.recordUpdate(1, clock.timeMs + 5_000); // grossly future
        assertEquals(0, noCounter.stalenessMs(), "still clamped even without a counter");
        assertEquals(0L, noCounter.implausibleCount());
    }

    // -----------------------------------------------------------------------
    // A plausible (non-skewed) frontier never trips the tripwire
    // -----------------------------------------------------------------------

    @Test
    void plausibleLaggingFrontierNeverCounts() {
        // The normal case: commit ts a little behind wall-now (propagation latency).
        tracker.recordUpdate(1, clock.timeMs - 300);
        assertEquals(300, tracker.stalenessMs());
        assertEquals(0L, implausible.get(),
                "a normal lagging frontier is not implausible");
    }
}
