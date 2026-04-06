package io.configd.observability;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ProductionSloDefinitions} — verifies all production SLOs are registered.
 */
class ProductionSloDefinitionsTest {

    @Test
    void registerDefinesAllSevenSlos() {
        SloTracker tracker = new SloTracker();
        ProductionSloDefinitions.register(tracker);

        Map<String, SloTracker.SloStatus> snapshot = tracker.snapshot();
        assertEquals(7, snapshot.size(), "Expected exactly 7 production SLOs");

        assertTrue(snapshot.containsKey("write.commit.latency.p99"));
        assertTrue(snapshot.containsKey("edge.read.latency.p99"));
        assertTrue(snapshot.containsKey("edge.read.latency.p999"));
        assertTrue(snapshot.containsKey("propagation.delay.p99"));
        assertTrue(snapshot.containsKey("control.plane.availability"));
        assertTrue(snapshot.containsKey("edge.read.availability"));
        assertTrue(snapshot.containsKey("write.throughput.baseline"));
    }

    @Test
    void registeredSlosHaveCorrectTargets() {
        SloTracker tracker = new SloTracker();
        ProductionSloDefinitions.register(tracker);

        Map<String, SloTracker.SloStatus> snapshot = tracker.snapshot();

        assertEquals(0.99, snapshot.get("write.commit.latency.p99").target(), 1e-9);
        assertEquals(0.99, snapshot.get("edge.read.latency.p99").target(), 1e-9);
        assertEquals(0.999, snapshot.get("edge.read.latency.p999").target(), 1e-9);
        assertEquals(0.99, snapshot.get("propagation.delay.p99").target(), 1e-9);
        assertEquals(0.99999, snapshot.get("control.plane.availability").target(), 1e-9);
        assertEquals(0.999999, snapshot.get("edge.read.availability").target(), 1e-9);
        assertEquals(0.99, snapshot.get("write.throughput.baseline").target(), 1e-9);
    }

    @Test
    void allSlosStartHealthy() {
        SloTracker tracker = new SloTracker();
        ProductionSloDefinitions.register(tracker);

        Map<String, SloTracker.SloStatus> snapshot = tracker.snapshot();
        for (var entry : snapshot.entrySet()) {
            assertFalse(entry.getValue().breaching(),
                    "SLO " + entry.getKey() + " should not be breaching with no events");
        }
    }
}
