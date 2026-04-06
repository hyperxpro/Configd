package io.configd.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PropagationLivenessMonitor} — LIVE-1 runtime assertion.
 */
class PropagationLivenessMonitorTest {

    private MetricsRegistry metrics;
    private PropagationLivenessMonitor monitor;

    @BeforeEach
    void setUp() {
        metrics = new MetricsRegistry();
        monitor = new PropagationLivenessMonitor(100, metrics);
    }

    @Test
    void noViolationsWhenEdgesAreUpToDate() {
        monitor.updateLeaderCommit(50);
        monitor.updateEdgeApplied("edge-1", 50);
        monitor.updateEdgeApplied("edge-2", 48);

        assertEquals(0, monitor.checkAll(), "No violations when edges are within threshold");
        assertEquals(0, metrics.counter("propagation.lag.violation").get());
    }

    @Test
    void violationWhenEdgeLagExceedsThreshold() {
        monitor.updateLeaderCommit(200);
        monitor.updateEdgeApplied("edge-1", 200);   // lag = 0, OK
        monitor.updateEdgeApplied("edge-2", 50);     // lag = 150, violation

        assertEquals(1, monitor.checkAll(), "One edge should be in violation");
        assertEquals(1, metrics.counter("propagation.lag.violation").get());
    }

    @Test
    void multipleViolations() {
        monitor.updateLeaderCommit(500);
        monitor.updateEdgeApplied("edge-1", 100);   // lag = 400
        monitor.updateEdgeApplied("edge-2", 200);   // lag = 300
        monitor.updateEdgeApplied("edge-3", 490);   // lag = 10, OK

        assertEquals(2, monitor.checkAll(), "Two edges should be in violation");
        assertEquals(2, metrics.counter("propagation.lag.violation").get());
    }

    @Test
    void lagForReturnsCorrectValue() {
        monitor.updateLeaderCommit(100);
        monitor.updateEdgeApplied("edge-1", 80);

        assertEquals(20, monitor.lagFor("edge-1"));
    }

    @Test
    void lagForUnknownEdgeReturnsNegativeOne() {
        assertEquals(-1, monitor.lagFor("unknown-edge"));
    }

    @Test
    void removeEdgeStopsTracking() {
        monitor.updateLeaderCommit(500);
        monitor.updateEdgeApplied("edge-1", 100);   // lag = 400, violation

        assertEquals(1, monitor.checkAll());

        monitor.removeEdge("edge-1");

        // Reset violation counter check by calling checkAll again
        // (counter already incremented, but no new violations)
        long prevCount = metrics.counter("propagation.lag.violation").get();
        assertEquals(0, monitor.checkAll(), "Removed edge should not cause violations");
        assertEquals(prevCount, metrics.counter("propagation.lag.violation").get());
    }

    @Test
    void edgeAtExactThresholdDoesNotViolate() {
        monitor.updateLeaderCommit(200);
        monitor.updateEdgeApplied("edge-1", 100);   // lag = 100, exactly at threshold

        assertEquals(0, monitor.checkAll(), "Lag exactly at threshold should not violate");
    }

    @Test
    void constructorRejectsNonPositiveMaxLag() {
        assertThrows(IllegalArgumentException.class,
                () -> new PropagationLivenessMonitor(0, metrics));
        assertThrows(IllegalArgumentException.class,
                () -> new PropagationLivenessMonitor(-1, metrics));
    }

    @Test
    void constructorRejectsNullMetrics() {
        assertThrows(NullPointerException.class,
                () -> new PropagationLivenessMonitor(100, null));
    }
}
