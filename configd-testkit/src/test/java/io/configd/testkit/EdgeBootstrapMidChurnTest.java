package io.configd.testkit;

import io.configd.common.NodeId;
import io.configd.raft.RaftRole;
import io.configd.store.CommandCodec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class EdgeBootstrapMidChurnTest {

    private static final int CP_NODES = 5;
    private static final int TICKS = 1_500;
    private static final AdversarialSchedule.Intensity CLEAN_CP =
            new AdversarialSchedule.Intensity(0, 40, 0.0);
    /** The EdgeLeaderKillScenarioTest seed: elects reliably; the mid-run leader is n2,
     *  so the roster veteran (source n0) and the follower-source legs (n1) are clean. */
    private static final long SEED = 4242L;

    private int writeCounter;

    @Test
    void leaderKilledMidTransferJoinerOnHealthyFollowerConvergesToPostChurnState() {
        writeCounter = 0;
        C1StreamDriver driver = new C1StreamDriver();
        EdgeFanOutSim sim = new EdgeFanOutSim(SEED, CP_NODES, 1, TICKS,
                false, driver, CLEAN_CP, EdgeInvariants.BOUND_MS);
        sim.enableEdgeRecovery(0);

        for (int t = 0; t < TICKS / 2; t++) {
            sim.tick();
        }
        int oldLeader = sim.cpSim().findLeader();
        assertTrue(oldLeader >= 0, "fixture: a leader before the join");
        for (int t = 0; t < 40; t++) {
            pumpAt(sim, oldLeader);
        }

        // The joiner subscribes to a healthy FOLLOWER (never the about-to-die leader,
        // never the veteran's source) - the production-shaped churn case.
        int source = pickFollower(sim, oldLeader, 0);
        assertTrue(sim.cpSim().store(source).currentVersion() > 0,
                "fixture: populated source store at join");
        int joinIdx = sim.joinEdge(source);
        EdgeActor joiner = sim.edges().get(joinIdx);
        sim.enableEdgeRecovery(joinIdx);
        pumpAt(sim, oldLeader); // T0: transfer emitted, in flight (latency >= 1 tick)
        assertEquals(0, joiner.snapshotsApplied(),
                "HARD non-vacuity: the kill lands MID-TRANSFER (nothing applied yet)");

        // KILL the leader; the surviving majority re-elects; writes continue.
        isolateFromCpPeers(sim, oldLeader);
        int newLeader = awaitNewLeader(sim, oldLeader);
        for (int t = 0; t < 40; t++) {
            pumpAt(sim, newLeader);
        }
        assertTrue(sim.cpSim().store(newLeader).currentVersion()
                        > sim.cpSim().store(oldLeader).currentVersion(),
                "HARD non-vacuity: the cluster moved on through the churn");

        // Judge: the joiner (and the veteran) converge byte-equal to the post-churn
        // cluster state. Their sources are healthy followers the NEW leader repairs.
        settleAndJudge(sim, driver, List.of(0, source));
        assertTrue(joiner.snapshotsApplied() >= 1, "the joiner bootstrapped via snapshot");
    }

    @Test
    void sourceLeaderKilledMidTransferJoinerBootstrapsSafelyToItsSourcesCommittedState() {
        writeCounter = 0;
        C1StreamDriver driver = new C1StreamDriver();
        EdgeFanOutSim sim = new EdgeFanOutSim(SEED, CP_NODES, 1, TICKS,
                false, driver, CLEAN_CP, EdgeInvariants.BOUND_MS);
        sim.enableEdgeRecovery(0);

        for (int t = 0; t < TICKS / 2; t++) {
            sim.tick();
        }
        int oldLeader = sim.cpSim().findLeader();
        assertTrue(oldLeader >= 0);
        assertTrue(oldLeader != 0, "fixture: the veteran's source must survive the kill");
        for (int t = 0; t < 40; t++) {
            pumpAt(sim, oldLeader);
        }

        // The joiner's snapshot SOURCE is the leader itself - killed mid-transfer.
        int joinIdx = sim.joinEdge(oldLeader);
        EdgeActor joiner = sim.edges().get(joinIdx);
        sim.enableEdgeRecovery(joinIdx);
        pumpAt(sim, oldLeader); // T0: transfer in flight
        assertEquals(0, joiner.snapshotsApplied(),
                "HARD non-vacuity: the kill lands MID-TRANSFER");
        isolateFromCpPeers(sim, oldLeader);

        int newLeader = awaitNewLeader(sim, oldLeader);
        for (int t = 0; t < 40; t++) {
            pumpAt(sim, newLeader);
        }
        long frozenSource = sim.cpSim().store(oldLeader).currentVersion();
        assertTrue(sim.cpSim().store(newLeader).currentVersion() > frozenSource,
                "HARD non-vacuity: the cluster moved on while the source is deposed");

        // The joiner must converge byte-equal to its source's frozen COMMITTED state
        // (applied = committed - bootstrap from a deposed leader can never serve
        // uncommitted state; any version regression would have thrown per tick).
        int guard = 0;
        while (joiner.currentVersion() < frozenSource) {
            assertTrue(++guard < 3_000,
                    "the joiner must finish bootstrapping from the deposed source");
            sim.tick();
        }
        assertTrue(joiner.snapshotsApplied() >= 1, "bootstrap was a snapshot transfer");
        // Per-source byte-equality, judged by the SAME equivalence machinery.
        sim.invariants().finalCheck(List.of(joiner),
                sim.cpSim().store(oldLeader).snapshot());
        // Full-cluster convergence for the deposed source's subscriber is NOT asserted:
        // the deposed node never reconverges (the registered RaftNode inflightCount
        // leak, class javadoc) - an edge cannot outrun its source. The veteran (healthy
        // source) still converges to the post-churn cluster:
        settleAndJudgeEdges(sim, driver, List.of(0), List.of(sim.edges().get(0)));
    }

    @Test
    void leaderKilledAndTransferLostMidFlightStillBootstrapsExactlyAfterHeal() {
        writeCounter = 0;
        C1StreamDriver driver = new C1StreamDriver();
        EdgeFanOutSim sim = new EdgeFanOutSim(SEED, CP_NODES, 1, TICKS,
                false, driver, CLEAN_CP, EdgeInvariants.BOUND_MS);
        sim.enableEdgeRecovery(0);

        for (int t = 0; t < TICKS / 2; t++) {
            sim.tick();
        }
        int oldLeader = sim.cpSim().findLeader();
        assertTrue(oldLeader >= 0);
        for (int t = 0; t < 40; t++) {
            pumpAt(sim, oldLeader);
        }

        int source = pickFollower(sim, oldLeader, 0);
        int joinIdx = sim.joinEdge(source);
        EdgeActor joiner = sim.edges().get(joinIdx);
        sim.enableEdgeRecovery(joinIdx);
        pumpAt(sim, oldLeader); // T0: transfer in flight

        // The full chaos: the leader dies AND the joiner's channel dies before the
        // transfer can deliver - the in-flight bootstrap is wholly lost in the churn.
        isolateFromCpPeers(sim, oldLeader);
        sim.partitionEdge(joinIdx);
        int newLeader = awaitNewLeader(sim, oldLeader);
        for (int t = 0; t < 40; t++) {
            pumpAt(sim, newLeader);
        }
        assertEquals(0, joiner.snapshotsApplied(),
                "HARD non-vacuity: the transfer was genuinely lost in the churn");
        assertEquals(0, joiner.currentVersion(), "the joiner is still zero-state");

        // Heal the edge channel: the unacked transfer has been rebuilding ack-lag at
        // the healthy follower - the re-send loop must bootstrap the joiner to the
        // post-churn state, exactly.
        sim.healEdge(joinIdx);
        settleAndJudge(sim, driver, List.of(0, source));
        assertTrue(joiner.snapshotsApplied() >= 1,
                "the joiner was bootstrapped by the self-healing re-sent transfer");
    }

    /** A CP node that is neither the (old) leader nor the excluded node. */
    private static int pickFollower(EdgeFanOutSim sim, int leader, int exclude) {
        for (int n = 0; n < CP_NODES; n++) {
            if (n != leader && n != exclude
                    && sim.cpSim().node(n).role() != RaftRole.LEADER) {
                return n;
            }
        }
        fail("no follower available");
        return -1;
    }

    private void pumpAt(EdgeFanOutSim sim, int cpNode) {
        if (cpNode >= 0 && sim.cpSim().node(cpNode).role() == RaftRole.LEADER) {
            int i = writeCounter++;
            sim.cpSim().node(cpNode).propose(CommandCodec.encodePut(
                    "churn/k" + (i % 8),
                    ("w-" + i).getBytes(StandardCharsets.UTF_8)));
        }
        sim.tick();
    }

    private static void isolateFromCpPeers(EdgeFanOutSim sim, int node) {
        for (int j = 0; j < CP_NODES; j++) {
            if (j != node) {
                sim.cpSim().network().isolate(NodeId.of(node), NodeId.of(j));
            }
        }
    }

    /** Ticks until a leader DISTINCT from {@code oldLeader} is elected ({@code findLeader}
     *  can keep returning the isolated stale leader, which never learns it was deposed). */
    private static int awaitNewLeader(EdgeFanOutSim sim, int oldLeader) {
        for (int t = 0; t < 3_000; t++) {
            for (int n = 0; n < CP_NODES; n++) {
                if (n != oldLeader && sim.cpSim().node(n).role() == RaftRole.LEADER) {
                    return n;
                }
            }
            sim.tick();
        }
        fail("no new leader elected after the kill");
        return -1;
    }

    /** Full-roster judge: all edges must reach the leader's state. */
    private void settleAndJudge(EdgeFanOutSim sim, C1StreamDriver driver,
                                List<Integer> mustHoldNodes) {
        settleAndJudgeEdges(sim, driver, mustHoldNodes, sim.edges());
    }

    /**
     * Heal CP -> exhaust the seed schedule -> fence writes (checked on the edge-subscribed
     * source nodes - the deposed ex-leader is excluded: the registered CP inflight leak
     * keeps it behind until a term change) -> settle -> drive {@code judgedEdges} level
     * with the leader -> byte-equality judge over those edges. Fence rationale as in
     * EdgeBootstrapUnderSustainedWritesTest#settleAndJudge.
     */
    private void settleAndJudgeEdges(EdgeFanOutSim sim, C1StreamDriver driver,
                                     List<Integer> mustHoldNodes, List<EdgeActor> judgedEdges) {
        sim.cpSim().network().healAll();
        for (int t = 0; t < TICKS; t++) {
            sim.tick();
        }
        commitBlocking(sim, "churn/fence-a", "fence-a", mustHoldNodes);
        commitBlocking(sim, "churn/fence-b", "fence-b", mustHoldNodes);
        sim.settleCp();
        int leaderNode = sim.cpSim().findLeader();
        assertTrue(leaderNode >= 0, "a settled healed CP must have a leader");
        long target = sim.cpSim().store(leaderNode).currentVersion();
        int guard = 0;
        while (!edgesAt(judgedEdges, target)) {
            if (++guard >= 3_000) {
                fail("edges did not converge to " + target + " within the tick bound:"
                        + describeEdges(judgedEdges) + " resubscribes=" + driver.resubscribes()
                        + " fatal=" + driver.fatalCloses());
            }
            sim.tick();
        }
        sim.invariants().finalCheck(judgedEdges, sim.cpSim().store(leaderNode).snapshot());
    }

    private static boolean edgesAt(List<EdgeActor> edges, long target) {
        for (EdgeActor e : edges) {
            if (e.alive() && e.currentVersion() < target) {
                return false;
            }
        }
        return true;
    }

    private static String describeEdges(List<EdgeActor> edges) {
        StringBuilder sb = new StringBuilder();
        for (EdgeActor e : edges) {
            sb.append(" edge").append(e.edgeId()).append("=v").append(e.currentVersion())
                    .append("/snap").append(e.snapshotsApplied())
                    .append("/gap").append(e.gapsDetected());
        }
        return sb.toString();
    }

    private void commitBlocking(EdgeFanOutSim sim, String key, String value,
                                List<Integer> mustHoldNodes) {
        byte[] expected = value.getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 50; attempt++) {
            int leader = sim.cpSim().findLeader();
            if (leader >= 0) {
                sim.cpSim().node(leader).propose(CommandCodec.encodePut(key, expected));
            }
            for (int t = 0; t < 20; t++) {
                sim.tick();
                if (nodesHold(sim, key, expected, mustHoldNodes)) {
                    return;
                }
            }
        }
        StringBuilder sb = new StringBuilder(
                "fence write '" + key + "' did not commit/apply on the source nodes:");
        for (int n = 0; n < CP_NODES; n++) {
            sb.append(" n").append(n).append("=v")
                    .append(sim.cpSim().store(n).currentVersion())
                    .append('/').append(sim.cpSim().node(n).role());
        }
        fail(sb.toString());
    }

    private static boolean nodesHold(EdgeFanOutSim sim, String key, byte[] expected,
                                     List<Integer> nodes) {
        for (int n : nodes) {
            var r = sim.cpSim().store(n).get(key);
            if (!r.found() || !java.util.Arrays.equals(expected, r.value())) {
                return false;
            }
        }
        return true;
    }
}
