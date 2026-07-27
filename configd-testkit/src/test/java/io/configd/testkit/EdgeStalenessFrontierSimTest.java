package io.configd.testkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.configd.edge.StalenessTracker;

/**
 * Frontier staleness at SIM level, driven by the real
 * {@link C1StreamDriver} heartbeats + deltas through the production {@link io.configd.edge.EdgeClientCore}).
 *
 * <p>Two scenarios, both judged against the edges' staleness state machine:
 * <ul>
 *   <li><b>idle-but-connected</b> - after the workload quiesces, a connected edge keeps
 *       receiving HEARTBEAT frames carrying {@code latestSeq == cursor}, so its frontier
 *       advances and it stays {@link StalenessTracker.State#CURRENT} across >=35s of idle sim
 *       time: staleness is driven by the heartbeat-carried frontier, not by elapsed idle
 *       time, so an idle-but-connected edge never walks toward DISCONNECTED;</li>
 *   <li><b>partitioned</b> - an edge cut off from its stream (no deltas, no heartbeats) walks
 *       CURRENT -> STALE -> DEGRADED -> DISCONNECTED as sim time elapses past the thresholds.</li>
 * </ul>
 */
class EdgeStalenessFrontierSimTest {

    private static final int CP_NODES = 3;
    private static final int EDGES = 2;

    /**
     * Idle-but-connected: a connected edge stays CURRENT indefinitely on heartbeats alone.
     * Runs a short workload to catch the edges up, then ticks IDLE (no new ops - the workload
     * schedule is exhausted) for &gt;35s of sim time. The C1StreamDriver emits HEARTBEAT frames
     * every {@code heartbeatMs} (250ms); each carries {@code latestSeq == cursor}, advancing
     * the frontier, so the edge never leaves CURRENT.
     */
    private static final int WORKLOAD_TICKS = 1_500;

    @Test
    void idleButConnectedEdgeStaysCurrentAcrossThirtyFiveSeconds() {
        EdgeFanOutSim sim = new EdgeFanOutSim(7L, CP_NODES, EDGES, WORKLOAD_TICKS,
                /*edgeFaults*/ false, new C1StreamDriver(),
                new AdversarialSchedule.Intensity(0, 60, 0.0), EdgeInvariants.BOUND_MS);

        sim.run();
        for (EdgeActor edge : sim.edges()) {
            assertEquals(StalenessTracker.State.CURRENT, edge.staleness(),
                    "edge " + edge.edgeId() + " must be CURRENT after catch-up");
        }
        assertTrue(sim.edges().get(0).cursor() > 0, "non-vacuity: edges applied some deltas");

        long start = sim.currentTime();
        while (sim.currentTime() - start < 35_500) {
            sim.tick();
            for (EdgeActor edge : sim.edges()) {
                assertEquals(StalenessTracker.State.CURRENT, edge.staleness(),
                        "idle-but-heartbeating edge " + edge.edgeId()
                                + " must stay CURRENT (ADR-0039) at sim time " + sim.currentTime());
            }
        }
        // Sanity: >35s elapsed, past the 30s threshold that would apply if staleness were
        // driven by idle time rather than by heartbeats.
        assertTrue(sim.currentTime() - start >= 35_000);
    }

    /**
     * Partitioned: an edge cut off from BOTH deltas and heartbeats walks CURRENT -> STALE ->
     * DEGRADED -> DISCONNECTED as sim time elapses past the 500ms / 5s / 30s thresholds.
     */
    @Test
    void partitionedEdgeWalksStaleDegradedDisconnected() {
        EdgeFanOutSim sim = new EdgeFanOutSim(11L, CP_NODES, EDGES, WORKLOAD_TICKS,
                /*edgeFaults*/ false, new C1StreamDriver(),
                new AdversarialSchedule.Intensity(0, 60, 0.0), EdgeInvariants.BOUND_MS);

        sim.run();
        EdgeActor victim = sim.edges().get(0);
        assertEquals(StalenessTracker.State.CURRENT, victim.staleness(),
                "victim must be CURRENT before partition");

        sim.partitionEdge(0);

        StalenessTracker.State sawStale = walkUntil(sim, victim, StalenessTracker.State.STALE, 2_000);
        assertEquals(StalenessTracker.State.STALE, sawStale,
                "a partitioned edge must reach STALE past the 500ms threshold");

        StalenessTracker.State sawDegraded = walkUntil(sim, victim, StalenessTracker.State.DEGRADED, 8_000);
        assertEquals(StalenessTracker.State.DEGRADED, sawDegraded,
                "a partitioned edge must reach DEGRADED past the 5s threshold");

        StalenessTracker.State sawDisconnected =
                walkUntil(sim, victim, StalenessTracker.State.DISCONNECTED, 35_000);
        assertEquals(StalenessTracker.State.DISCONNECTED, sawDisconnected,
                "a partitioned edge must reach DISCONNECTED past the 30s threshold");

        // Non-vacuity: a NON-partitioned sibling stayed CURRENT throughout (heartbeats flowed).
        EdgeActor sibling = sim.edges().get(1);
        assertNotEquals(StalenessTracker.State.DISCONNECTED, sibling.staleness(),
                "the connected sibling must NOT be DISCONNECTED (heartbeats kept it fresh)");
    }

    private static StalenessTracker.State walkUntil(EdgeFanOutSim sim, EdgeActor edge,
                                                    StalenessTracker.State target, long maxAdvanceMs) {
        long start = sim.currentTime();
        while (sim.currentTime() - start < maxAdvanceMs) {
            sim.tick();
            if (edge.staleness() == target) {
                return target;
            }
        }
        return edge.staleness();
    }
}
