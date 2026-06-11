package io.configd.testkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.configd.edge.StalenessTracker;

/**
 * CT-01 / CT-07 at SIM level (ADR-0039 frontier staleness, driven by the real
 * {@link C1StreamDriver} heartbeats + deltas through the production {@link io.configd.edge.EdgeClientCore}).
 *
 * <p>Two scenarios, both judged against the edges' staleness STATE (the contract §2 state
 * machine, now ADR-0039-measured):
 * <ul>
 *   <li><b>idle-but-connected</b> — after the workload quiesces, a connected edge keeps
 *       receiving HEARTBEAT frames carrying {@code latestSeq == cursor}, so its frontier
 *       advances and it stays {@link StalenessTracker.State#CURRENT} across ≥35s of idle sim
 *       time. The pre-ADR-0039 idle-time proxy would have walked it to DISCONNECTED at 30s —
 *       this test is the executable proof of the fix at sim level;</li>
 *   <li><b>partitioned</b> — an edge cut off from its stream (no deltas, no heartbeats) walks
 *       CURRENT → STALE → DEGRADED → DISCONNECTED as sim time elapses past the thresholds.</li>
 * </ul>
 */
class EdgeStalenessFrontierSimTest {

    private static final int CP_NODES = 3;
    private static final int EDGES = 2;

    /**
     * Idle-but-connected: a connected edge stays CURRENT indefinitely on heartbeats alone.
     * Runs a short workload to catch the edges up, then ticks IDLE (no new ops — the workload
     * schedule is exhausted) for &gt;35s of sim time. The C1 driver emits HEARTBEAT frames
     * every {@code heartbeatMs} (250ms); each carries {@code latestSeq == cursor}, advancing
     * the frontier, so the edge never leaves CURRENT.
     */
    /** The workload window: ops spread across these ticks so commits actually land. */
    private static final int WORKLOAD_TICKS = 1_500;

    @Test
    void idleButConnectedEdgeStaysCurrentAcrossThirtyFiveSeconds() {
        // No edge faults, no CP faults; a workload (spread across WORKLOAD_TICKS) so a leader
        // elects, commits land, and the edges catch up — then the schedule exhausts and the
        // system goes idle with only heartbeats flowing.
        EdgeFanOutSim sim = new EdgeFanOutSim(7L, CP_NODES, EDGES, WORKLOAD_TICKS,
                /*edgeFaults*/ false, new C1StreamDriver(),
                new AdversarialSchedule.Intensity(0, 60, 0.0), EdgeInvariants.BOUND_MS);

        // Phase 1: run the full workload window so a leader elects, ~60 ops commit, and the
        // edges catch up to the leader (cursor == latestSeq) and start receiving heartbeats.
        sim.run();
        // The edges must have caught up and be CURRENT before the idle window.
        for (EdgeActor edge : sim.edges()) {
            assertEquals(StalenessTracker.State.CURRENT, edge.staleness(),
                    "edge " + edge.edgeId() + " must be CURRENT after catch-up");
        }
        assertTrue(sim.edges().get(0).cursor() > 0, "non-vacuity: edges applied some deltas");

        // Phase 2: idle for >35s of sim time (the workload schedule is exhausted, so no new
        // commits) — only heartbeats flow. The edge must stay CURRENT the WHOLE time.
        long start = sim.currentTime();
        while (sim.currentTime() - start < 35_500) {
            sim.tick();
            for (EdgeActor edge : sim.edges()) {
                assertEquals(StalenessTracker.State.CURRENT, edge.staleness(),
                        "idle-but-heartbeating edge " + edge.edgeId()
                                + " must stay CURRENT (ADR-0039) at sim time " + sim.currentTime());
            }
        }
        // Sanity: >35s elapsed — the pre-ADR-0039 idle proxy would have hit DISCONNECTED (30s).
        assertTrue(sim.currentTime() - start >= 35_000);
    }

    /**
     * Partitioned: an edge cut off from BOTH deltas and heartbeats walks CURRENT → STALE →
     * DEGRADED → DISCONNECTED as sim time elapses past the 500ms / 5s / 30s thresholds.
     */
    @Test
    void partitionedEdgeWalksStaleDegradedDisconnected() {
        EdgeFanOutSim sim = new EdgeFanOutSim(11L, CP_NODES, EDGES, WORKLOAD_TICKS,
                /*edgeFaults*/ false, new C1StreamDriver(),
                new AdversarialSchedule.Intensity(0, 60, 0.0), EdgeInvariants.BOUND_MS);

        // Run the workload window so a leader elects, commits land, and the edges catch up.
        sim.run();
        EdgeActor victim = sim.edges().get(0);
        assertEquals(StalenessTracker.State.CURRENT, victim.staleness(),
                "victim must be CURRENT before partition");

        // Partition the victim: no more deltas, no more heartbeats reach it.
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

    /** Ticks the sim up to {@code maxAdvanceMs} of sim time, returning the first state reached. */
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
