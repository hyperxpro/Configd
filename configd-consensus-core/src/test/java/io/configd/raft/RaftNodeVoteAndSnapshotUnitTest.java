package io.configd.raft;

import io.configd.common.NodeId;
import io.configd.common.Storage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RaftNodeVoteAndSnapshotUnitTest {

    private static final NodeId N1 = NodeId.of(1);
    private static final NodeId N2 = NodeId.of(2);
    private static final NodeId N3 = NodeId.of(3);

    static final class RecordingTransport implements RaftTransport {
        record Sent(NodeId target, RaftMessage message) {}
        final List<Sent> sent = new ArrayList<>();
        @Override public void send(NodeId target, RaftMessage message) { sent.add(new Sent(target, message)); }
        void clear() { sent.clear(); }
        List<RequestVoteResponse> voteResponses() {
            return sent.stream().filter(s -> s.message() instanceof RequestVoteResponse)
                    .map(s -> (RequestVoteResponse) s.message()).toList();
        }
    }

    static final class CountingStateMachine implements StateMachine {
        long seq;
        @Override public long apply(long index, long term, byte[] command) {
            return (command == null || command.length == 0) ? StateMachine.NON_MUTATING : ++seq;
        }
        @Override public byte[] snapshot() { return new byte[]{42}; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    private static RaftNode follower(RecordingTransport t) {
        RaftConfig config = RaftConfig.of(N1, Set.of(N2, N3));
        return new RaftNode(config, new RaftLog(), t, new CountingStateMachine(),
                new java.util.Random(1));
    }

    private static RequestVoteRequest vote(long term, NodeId cand, long lastIdx, long lastTerm) {
        return new RequestVoteRequest(term, cand, lastIdx, lastTerm, false);
    }


    @Nested
    class VoteDecision {

        @Test
        void grantsVoteToUpToDateCandidateInNewTerm() {
            RecordingTransport t = new RecordingTransport();
            RaftNode node = follower(t);
            node.handleMessage(vote(1, N2, 0, 0));
            List<RequestVoteResponse> resps = t.voteResponses();
            assertEquals(1, resps.size());
            assertTrue(resps.getFirst().voteGranted(), "up-to-date candidate must be granted");
            assertEquals(N2, node.votedFor(), "vote must be recorded in-memory");
        }

        @Test
        void rejectsStaleTermCandidate() {
            RecordingTransport t = new RecordingTransport();
            RaftNode node = follower(t);
            node.handleMessage(new RequestVoteResponse(5, false, N2, false));
            t.clear();
            node.handleMessage(vote(3, N2, 0, 0));
            List<RequestVoteResponse> resps = t.voteResponses();
            assertEquals(1, resps.size());
            assertFalse(resps.getFirst().voteGranted());
            assertEquals(5, resps.getFirst().term());
        }

        @Test
        void rejectsSecondCandidateAfterAlreadyVotingInTerm() {
            RecordingTransport t = new RecordingTransport();
            RaftNode node = follower(t);
            node.handleMessage(vote(1, N2, 0, 0));
            assertEquals(N2, node.votedFor());
            t.clear();
            node.handleMessage(vote(1, N3, 0, 0));
            List<RequestVoteResponse> resps = t.voteResponses();
            assertEquals(1, resps.size());
            assertFalse(resps.getFirst().voteGranted(), "must not double-vote in one term");
        }

        @Test
        void rejectsCandidateWithStaleLog() {
            RecordingTransport t = new RecordingTransport();
            RaftNode node = follower(t);
            node.handleMessage(new AppendEntriesRequest(2, N2, 0, 0,
                    List.of(new LogEntry(1, 2, new byte[]{1}), new LogEntry(2, 2, new byte[]{2})), 0));
            t.clear();
            // A candidate at a higher term but with a shorter/older log must be denied by the
            // up-to-date check (Raft section 5.4.1).
            node.handleMessage(vote(3, N3, 1, 1));
            List<RequestVoteResponse> resps = t.voteResponses();
            assertEquals(1, resps.size());
            assertFalse(resps.getFirst().voteGranted(), "stale-log candidate must be denied");
        }

        @Test
        void higherTermVoteRequestStepsDownLeader() {
            RaftConfig config = RaftConfig.of(N1, Set.of());
            RecordingTransport t = new RecordingTransport();
            RaftNode leader = new RaftNode(config, new RaftLog(), t, new CountingStateMachine(),
                    new java.util.Random(42));
            for (int i = 0; i < 301; i++) leader.tick();
            assertEquals(RaftRole.LEADER, leader.role());
            long term = leader.currentTerm();
            leader.handleMessage(vote(term + 5, N1, 99, 99));
            assertEquals(term + 5, leader.currentTerm());
            assertEquals(RaftRole.FOLLOWER, leader.role());
        }
    }


    @Nested
    class SnapshotTrigger {

        private RaftNode durableSingleNodeLeader(Storage storage) {
            RaftConfig config = RaftConfig.of(N1, Set.of());
            RaftLog log = new RaftLog(storage);
            RaftNode node = new RaftNode(config, log, new RecordingTransport(),
                    new CountingStateMachine(), new java.util.Random(42), storage);
            for (int i = 0; i < 301; i++) node.tick();
            assertEquals(RaftRole.LEADER, node.role());
            return node;
        }

        @Test
        void noSnapshotWhenNothingNewBeyondSnapshotPoint() {
            Storage storage = Storage.inMemory();
            RaftNode node = durableSingleNodeLeader(storage);
            assertTrue(node.triggerSnapshot(), "first snapshot with applied entries must succeed");
            long snapIdx = node.log().snapshotIndex();
            assertTrue(snapIdx > 0);
            assertFalse(node.triggerSnapshot(), "no new applied entries -> no snapshot");
            assertEquals(snapIdx, node.log().snapshotIndex());
        }

        @Test
        void snapshotAdvancesSnapshotIndexToLastApplied() {
            Storage storage = Storage.inMemory();
            RaftNode node = durableSingleNodeLeader(storage);
            assertEquals(ProposalResult.ACCEPTED, node.propose(new byte[]{1}).result());
            assertEquals(ProposalResult.ACCEPTED, node.propose(new byte[]{2}).result());
            long applied = node.log().lastApplied();
            assertTrue(applied >= 3, "no-op + 2 commands applied on single-node path");
            assertTrue(node.triggerSnapshot());
            assertEquals(applied, node.log().snapshotIndex());
        }
    }


    @Nested
    class JointCodecBound {

        @Test
        void rejectsAbsurdNewVoterCountInJointConfig() {
            // magic + isJoint(1) + oldCount(1) + oldId + newCount(1000 > 255) must throw:
            // an absurd newCount (> 255) is rejected.
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(4 + 1 + 4 + 4 + 4);
            buf.put(new byte[]{0x52, 0x43, 0x46, 0x47});
            buf.put((byte) 1);
            buf.putInt(1);
            buf.putInt(1);
            buf.putInt(1000);
            assertThrows(IllegalArgumentException.class,
                    () -> RaftNode.deserializeConfigChange(buf.array()));
        }

        @Test
        void roundTripsJointConfigThroughDeserialize() {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(4 + 1 + 4 + 4 + 4 + 8);
            buf.put(new byte[]{0x52, 0x43, 0x46, 0x47});
            buf.put((byte) 1); // joint
            buf.putInt(1);     // oldCount
            buf.putInt(1);     // old id 1
            buf.putInt(2);     // newCount
            buf.putInt(1);     // new id 1
            buf.putInt(2);     // new id 2
            ClusterConfig cfg = RaftNode.deserializeConfigChange(buf.array());
            assertTrue(cfg.isJoint());
            assertEquals(Set.of(N1), cfg.voters());
            assertEquals(Set.of(N1, N2), cfg.newVoters());
        }
    }
}
