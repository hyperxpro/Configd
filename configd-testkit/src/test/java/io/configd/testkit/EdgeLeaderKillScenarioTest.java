package io.configd.testkit;

import io.configd.common.NodeId;
import io.configd.raft.RaftRole;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted deterministic scenario (design section 7 sim plan): the CP leader is killed
 * <b>mid-stream</b> while edges tail with the real {@link C1StreamDriver}, and we assert
 * (1) no edge version ever decreases across the leadership change, and (2) the edges
 * eventually converge to the (new leader's) authoritative store after re-election +
 * heal+drain. No sleeps - the whole scenario is tick-driven and seed-deterministic.
 *
 * <p>"Kill the leader" is modelled by fully isolating the current leader CP node from every
 * peer on the CP {@link AdversarialNetwork} (a clean network partition the surviving
 * majority re-elects around). The per-edge version-monotonicity invariant (which throws on
 * any decrease) runs every tick inside {@link EdgeFanOutSim#tick()}, so "no edge version
 * decrease" is enforced continuously; this test additionally records edge versions to make
 * the no-decrease claim explicit and asserts post-heal convergence.
 */
class EdgeLeaderKillScenarioTest {

    private static final int CP_NODES = 5;
    private static final int EDGES = 3;
    private static final int TICKS = 1_500;
    /** A seed that elects reliably and delivers under the no-edge-fault schedule. */
    private static final long SEED = 4242L;

    @Test
    void leaderKilledMidStreamNoVersionDecreaseAndEventuallyConverges() {
        // No edge faults so the only disruption is the deliberate leader kill - keeps the
        // scenario crisp (the 507-seed sweep covers the fault-interaction space).
        EdgeFanOutSim sim = new EdgeFanOutSim(SEED, CP_NODES, EDGES, TICKS,
                /* edgeFaults */ false, new C1StreamDriver(),
                AdversarialSchedule.defaultIntensity(), EdgeInvariants.BOUND_MS);

        // Per-edge highest-seen version, to make the no-decrease claim explicit (the
        // invariant also enforces it by throwing).
        Map<Integer, Long> maxVersion = new HashMap<>();

        // Phase 1: run until a leader is elected and edges have applied some versions.
        int t = 0;
        int establishedTick = -1;
        for (; t < TICKS; t++) {
            sim.tick();
            recordNoDecrease(sim, maxVersion);
            if (establishedTick < 0
                    && sim.cpSim().findLeader() >= 0
                    && anyEdgeHasVersion(sim)) {
                // Give a little more streaming so the kill is genuinely "mid-stream".
                establishedTick = t;
            }
            if (establishedTick >= 0 && t >= establishedTick + 80) {
                break;
            }
        }
        assertTrue(establishedTick >= 0, "a leader must elect and edges must apply before the kill");
        int oldLeader = sim.cpSim().findLeader();
        assertTrue(oldLeader >= 0, "leader present at kill time");

        // Phase 2: KILL the leader - isolate it from every peer on the CP network.
        killLeader(sim, oldLeader);

        // Phase 3: keep ticking through re-election; versions must never decrease.
        int newLeader = -1;
        for (; t < TICKS; t++) {
            sim.tick();
            recordNoDecrease(sim, maxVersion);
            int leaderNow = sim.cpSim().findLeader();
            if (leaderNow >= 0 && leaderNow != oldLeader) {
                newLeader = leaderNow;
            }
            if (newLeader >= 0 && t > establishedTick + 400) {
                break;
            }
        }
        assertTrue(newLeader >= 0 && newLeader != oldLeader,
                "a new leader (distinct from the killed one) must be elected; got " + newLeader);
        assertTrue(sim.cpSim().node(oldLeader).role() != RaftRole.LEADER
                        || sim.cpSim().findLeader() != oldLeader,
                "the killed leader must no longer be the cluster's serving leader");

        // Phase 4: heal everything and drain - the edges must converge to the authoritative
        // store. finalCheck throws on divergence (here it must NOT throw).
        sim.finalCheck();

        // The no-decrease invariant held continuously (else tick() would have thrown); the
        // recorded map is the explicit witness that versions only advanced.
        assertTrue(maxVersion.values().stream().anyMatch(v -> v > 0),
                "edges must have applied real versions across the leadership change");
    }

    private static void killLeader(EdgeFanOutSim sim, int leader) {
        NodeId leaderId = NodeId.of(leader);
        for (int j = 0; j < CP_NODES; j++) {
            if (j != leader) {
                sim.cpSim().network().isolate(leaderId, NodeId.of(j));
            }
        }
    }

    private static void recordNoDecrease(EdgeFanOutSim sim, Map<Integer, Long> maxVersion) {
        for (EdgeActor edge : sim.edges()) {
            if (!edge.alive()) {
                continue;
            }
            long v = edge.currentVersion();
            long prev = maxVersion.getOrDefault(edge.edgeId(), -1L);
            // The throwing invariant already guarantees no decrease within an incarnation;
            // this assertion is a redundant explicit witness (edges never crash here).
            assertTrue(v >= prev || v == 0,
                    "edge " + edge.edgeId() + " version decreased " + prev + " -> " + v);
            if (v > prev) {
                maxVersion.put(edge.edgeId(), v);
            }
        }
    }

    private static boolean anyEdgeHasVersion(EdgeFanOutSim sim) {
        for (EdgeActor edge : sim.edges()) {
            if (edge.alive() && edge.currentVersion() > 0) {
                return true;
            }
        }
        return false;
    }
}
