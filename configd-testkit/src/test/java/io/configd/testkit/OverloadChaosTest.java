package io.configd.testkit;

import io.configd.edge.StalenessTracker;
import io.configd.raft.ProposalResult;
import io.configd.raft.RaftNode;
import io.configd.store.CommandCodec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Session 4 / Workstream D — overload under chaos (charter §6/§11). Two cells:
 * <ol>
 *   <li><b>Control-plane write flood</b> — past-capacity proposals must SHED with
 *       {@code OVERLOADED} (the §11 429-equivalent) and the uncommitted queue must PLATEAU at the
 *       bound, never grow unbounded with more load; it drains and accepts writes again once
 *       delivery resumes (clean recovery).</li>
 *   <li><b>Post-partition reconnect storm</b> (the data plane's most dangerous overload, called out
 *       in charter §6) — a whole fleet of edges partitioned to DISCONNECTED then HEALED at once
 *       must all recover to CURRENT (catch-up thundering herd), none stuck stale-but-silent, no
 *       terminal failure.</li>
 * </ol>
 * The fan-out admission/queue bounds themselves are pinned by {@code FanOutServerAdmissionBoundTest},
 * {@code DemotionNoticeBackpressureTest}, {@code BootstrapSnapshotBackpressureTest}, and the A3 legs
 * (ack-lag / wedged-transport / governor-churn); this adds the two overload *scenarios*. fault-matrix §D.
 */
class OverloadChaosTest {

    // ------------------------------------------------------------------------
    // D-1: control-plane write flood — OVERLOADED shed + bounded (plateau) queue + recovery
    // ------------------------------------------------------------------------
    @Test
    void controlPlaneWriteFlood_shedsWithOverload_boundedQueue_recovers() {
        PartitionMatrixTest.Cluster c = new PartitionMatrixTest.Cluster(7L);
        int elect = c.stepUntilLeader(600, "D-1");
        assertTrue(elect > 0, "cluster must elect a leader");
        RaftNode ldr = c.nodes.get(c.findLeader());

        // Flood WITHOUT stepping: no delivery → no commit → uncommitted builds; no ticks → the
        // leader stays leader (no CheckQuorum). §11 backpressure must shed with OVERLOADED.
        final int FLOOD = 1500;
        int accepted = 0, overloaded = 0;
        for (int i = 0; i < FLOOD; i++) {
            ProposalResult r = ldr.propose(CommandCodec.encodePut("flood/a" + i, ("v" + i).getBytes())).result();
            if (r == ProposalResult.ACCEPTED) {
                accepted++;
            } else if (r == ProposalResult.OVERLOADED) {
                overloaded++;
            }
        }
        long uncommitted1 = ldr.log().lastIndex() - ldr.log().commitIndex();
        assertTrue(overloaded > 0, "D-1: the write flood must shed with OVERLOADED (the §11 429-equivalent)");
        assertTrue(accepted < FLOOD, "D-1: not every write accepted — the queue is bounded");

        // Sustained MORE load must NOT grow the queue — it plateaus at the bound (never unbounded).
        int overloaded2 = 0;
        for (int i = 0; i < FLOOD; i++) {
            ProposalResult r = ldr.propose(CommandCodec.encodePut("flood/b" + i, ("v" + i).getBytes())).result();
            if (r == ProposalResult.OVERLOADED) {
                overloaded2++;
            }
        }
        long uncommitted2 = ldr.log().lastIndex() - ldr.log().commitIndex();
        assertEquals(uncommitted1, uncommitted2,
                "D-1: the uncommitted queue must PLATEAU under sustained overload (bounded, never unbounded)");
        assertEquals(FLOOD, overloaded2, "D-1: once the bound is hit, ALL further writes are shed (no silent buffering)");

        // Recovery: resume delivery → commits drain → queue clears → writes accepted again.
        for (int t = 0; t < 3000; t++) {
            c.step();
        }
        assertTrue(ldr.log().lastIndex() - ldr.log().commitIndex() < uncommitted1,
                "D-1: the uncommitted queue drains once delivery resumes");
        int ldr2 = c.findLeader();
        assertTrue(ldr2 >= 0, "D-1: a leader exists after the flood drains");
        assertEquals(ProposalResult.ACCEPTED,
                c.nodes.get(ldr2).propose(CommandCodec.encodePut("post/recovery", "x".getBytes())).result(),
                "D-1: writes are accepted again after the overload clears (clean recovery)");
        System.out.println("OVERLOAD: scenario=cp-write-flood accepted=" + accepted + " sheddedFirstWave="
                + overloaded + " queuePlateau=" + uncommitted1 + " (bounded, recovered)");
    }

    // ------------------------------------------------------------------------
    // D-2: post-partition reconnect storm — a fleet of edges all DISCONNECTED then HEALED at once
    // ------------------------------------------------------------------------
    @Test
    void postPartitionReconnectStorm_allEdgesRecoverToCurrent() {
        // The S4 EXP-010 anchor point: 5 edges. (S5 D.3 extends this to a fleet-size
        // distribution via reconnectStormRecoveryTicks below; this @Test keeps the
        // original single-cell assertion as the regression anchor.)
        long ticks = reconnectStormRecoveryTicks(5);
        assertTrue(ticks > 0, "D-2: the 5-edge reconnect storm must recover");
    }

    // ------------------------------------------------------------------------
    // D-2 (S5 D.3): reconnect-storm recovery distribution at fleet sizes {5,10,25,50}.
    // Deterministic in-sim — leadership-stable (no live 2-vCPU ceiling), so it IS locally
    // measurable. 1 tick ≈ 1 ms modeled. Each cell prints the canonical OVERLOAD line.
    // ------------------------------------------------------------------------
    @ParameterizedTest(name = "reconnect-storm fleet edges={0}")
    @ValueSource(ints = {5, 10, 25, 50})
    void postPartitionReconnectStorm_fleetSizeDistribution(int edges) {
        long ticks = reconnectStormRecoveryTicks(edges);
        assertTrue(ticks > 0,
                "D.3: the " + edges + "-edge fleet must recover to CURRENT after the storm");
    }

    /**
     * Runs one post-partition reconnect-storm cell with {@code edges} edges and returns the
     * recovery tick count (heal-instant → whole-fleet-CURRENT). Asserts the same invariants as
     * the S4 D-2 cell: every edge ends CURRENT and caught up to the authoritative version, and no
     * edge is pushed TERMINAL. Prints the canonical
     * {@code OVERLOAD: scenario=reconnect-storm edges=N recoveryTicks=M} line.
     */
    private static long reconnectStormRecoveryTicks(int edges) {
        final int CP = 3, WARMUP = 1_500;
        C1StreamDriver driver = new C1StreamDriver();
        EdgeFanOutSim sim = new EdgeFanOutSim(91L, CP, edges, WARMUP, false, driver,
                new AdversarialSchedule.Intensity(0, 60, 0.0), EdgeInvariants.BOUND_MS);
        sim.run();
        for (int e = 0; e < edges; e++) {
            assertEquals(StalenessTracker.State.CURRENT, sim.edges().get(e).staleness(),
                    "edge " + e + " must be CURRENT before the storm");
            sim.enableEdgeRecovery(e);
        }

        // The storm setup: cut the WHOLE fleet off and commit writes none of them can see.
        for (int e = 0; e < edges; e++) {
            sim.partitionEdge(e);
        }
        int obs = sim.edges().get(0).subscribedCpNode();
        for (int i = 1; i <= 5; i++) {
            commit(sim, obs, "storm/k" + i, "v" + i);
        }

        // Walk the WHOLE fleet to DISCONNECTED (logical-time ladder, no wall-clock sleeps).
        long start = sim.currentTime();
        while (!allAtLeast(sim, edges, StalenessTracker.State.DISCONNECTED)) {
            if (sim.currentTime() - start > 60_000) {
                fail("not all edges reached DISCONNECTED within 60s of sim time");
            }
            sim.tick();
        }

        // THE STORM: heal the entire fleet at the SAME instant — every edge reconnects +
        // re-bootstraps at once (the catch-up thundering herd). All must recover to CURRENT.
        long target = sim.cpSim().store(obs).currentVersion();
        for (int e = 0; e < edges; e++) {
            sim.healEdge(e);
        }
        long healAt = sim.currentTime();
        long recoveredAt = -1;
        for (int t = 0; t < 20_000; t++) {
            sim.tick();
            if (allRecovered(sim, edges, target)) {
                recoveredAt = sim.currentTime();
                break;
            }
        }
        assertTrue(recoveredAt > 0,
                "D-2: the whole fleet must recover to CURRENT after the simultaneous reconnect storm "
                        + "(no edge stuck stale-but-silent)");
        for (int e = 0; e < edges; e++) {
            assertEquals(StalenessTracker.State.CURRENT, sim.edges().get(e).staleness(),
                    "edge " + e + " must be CURRENT after the storm");
            assertTrue(sim.edges().get(e).currentVersion() >= target,
                    "edge " + e + " must have caught up to the authoritative version");
        }
        assertTrue(sim.terminalFailures().isEmpty(),
                "D-2: a reconnect storm must not push any edge TERMINAL (bounded, self-healing)");
        long recoveryTicks = recoveredAt - healAt;
        System.out.println("OVERLOAD: scenario=reconnect-storm edges=" + edges
                + " recoveryTicks=" + recoveryTicks + " (all recovered, none terminal)");
        return recoveryTicks;
    }

    // --- helpers ---

    private static boolean allAtLeast(EdgeFanOutSim sim, int edges, StalenessTracker.State state) {
        for (int e = 0; e < edges; e++) {
            if (sim.edges().get(e).staleness() != state) {
                return false;
            }
        }
        return true;
    }

    private static boolean allRecovered(EdgeFanOutSim sim, int edges, long target) {
        for (int e = 0; e < edges; e++) {
            EdgeActor a = sim.edges().get(e);
            if (a.currentVersion() < target || a.staleness() != StalenessTracker.State.CURRENT) {
                return false;
            }
        }
        return true;
    }

    private static void commit(EdgeFanOutSim sim, int observedCpNode, String key, String value) {
        byte[] expected = value.getBytes();
        for (int attempt = 0; attempt < 50; attempt++) {
            int leader = sim.cpSim().findLeader();
            if (leader >= 0) {
                sim.cpSim().node(leader).propose(CommandCodec.encodePut(key, expected));
            }
            for (int t = 0; t < 20; t++) {
                sim.tick();
                var r = sim.cpSim().store(observedCpNode).get(key);
                if (r.found() && java.util.Arrays.equals(expected, r.value())) {
                    return;
                }
            }
        }
        fail("write '" + key + "' did not commit/apply on cp node " + observedCpNode);
    }
}
