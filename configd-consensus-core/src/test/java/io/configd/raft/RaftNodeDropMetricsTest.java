package io.configd.raft;

import io.configd.common.NodeId;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaftNodeDropMetricsTest {

    private static final NodeId N1 = NodeId.of(1);
    private static final NodeId N2 = NodeId.of(2);
    private static final NodeId N3 = NodeId.of(3);

    /** A 3-node leader (N1) wired to {@code transport}, elected by injecting the pre-vote + real-vote
     *  grants exactly as {@code MetricsWiringContractTest.forcedUncommittableLeader} does. */
    private static RaftNode forceLeader(RaftTransport transport) {
        RaftConfig config = new RaftConfig(N1, Set.of(N2, N3), 150, 300, 50, 64, 256 * 1024, 1024, 10, 1);
        RaftNode node = new RaftNode(config, new RaftLog(), transport,
                new InstallSnapshotTest.TestStateMachine(), new java.util.Random(7));
        for (int i = 0; i < 301; i++) {
            node.tick();
        }
        node.handleMessage(new RequestVoteResponse(node.currentTerm(), true, N2, true));
        node.handleMessage(new RequestVoteResponse(node.currentTerm(), true, N3, true));
        node.handleMessage(new RequestVoteResponse(node.currentTerm(), true, N2, false));
        node.handleMessage(new RequestVoteResponse(node.currentTerm(), true, N3, false));
        assertEquals(RaftRole.LEADER, node.role(), "forced leader must hold leadership");
        return node;
    }

    @Test
    void appendCodecRejectIncrementsAppendSendRejected() {
        // The transport rejects every AppendEntries as the wire codec would for an oversized encode; vote
        // traffic (RequestVote) passes so the node can still be elected.
        RaftTransport rejectingAppends = (target, message) -> {
            if (message instanceof AppendEntriesRequest) {
                throw new IllegalArgumentException("codec rejected AppendEntries (test)");
            }
        };
        RaftNode leader = forceLeader(rejectingAppends);

        for (int i = 0; i < 60; i++) {
            leader.tick();
        }

        assertTrue(leader.appendSendRejected() >= 1,
                "an AppendEntries codec reject must increment the append-send-rejected tally");
        assertEquals(leader.appendSendRejected(), leader.metrics().appendSendRejected(),
                "the tally must surface in the RaftMetrics snapshot the per-shard gauge reads");
    }

    @Test
    void installSnapshotChunkCodecRejectIncrementsSnapshotChunkSendRejected() {
        // Reuse the proven lagging-snapshot sender driver: it elects N1, commits + compacts so N3 falls
        // below the snapshot, and keeps the leader genuinely in power (real N2 delivery during setup), so
        // heartbeats then drive a real InstallSnapshot to N3. A hand-rolled single-leader setup cannot
        // reliably reach that state - the leader loses quorum contact and steps down before the transfer.
        InstallSnapshotTest.TestCluster cluster = new InstallSnapshotTest.TestCluster(3);
        ChunkedInstallSnapshotTest.setUpLaggingSnapshot(cluster, 2);
        RaftNode leader = cluster.nodes.get(N1);
        InstallSnapshotTest.TestTransport leaderTransport = cluster.transports.get(N1);

        List<NodeId> installAttempts = new ArrayList<>();
        leaderTransport.interceptSend((target, message) -> {
            if (message instanceof InstallSnapshotRequest && target.equals(N3)) {
                installAttempts.add(target);
                throw new IllegalArgumentException("codec rejected InstallSnapshot chunk (test)");
            }
        });

        // Drive heartbeats, keeping the leader alive with an N2 ack each interval (self + N2 = quorum in a
        // 3-node cluster), until a rejected chunk send is counted. Mirrors the proven sender loop in
        // ChunkedInstallSnapshotTest (an N2 ack every round, 60 ticks > the 50-tick heartbeat).
        long term = leader.currentTerm();
        long leaderLast = leader.log().lastIndex();
        for (int round = 0; round < 8 && leader.snapshotChunkSendRejected() == 0; round++) {
            leader.handleMessage(new AppendEntriesResponse(term, true, leaderLast, N2));
            for (int t = 0; t < 60; t++) {
                leader.tick();
            }
        }

        assertEquals(RaftRole.LEADER, leader.role(), "the leader must stay in power to drive the transfer");
        assertFalse(installAttempts.isEmpty(),
                "the leader must actually attempt an InstallSnapshot to the lagging follower");
        assertTrue(leader.snapshotChunkSendRejected() >= 1,
                "an InstallSnapshot chunk codec reject must increment the snapshot-chunk-send-rejected tally");
        assertEquals(leader.snapshotChunkSendRejected(), leader.metrics().snapshotChunkSendRejected(),
                "the tally must surface in the RaftMetrics snapshot the per-shard gauge reads");
    }
}
