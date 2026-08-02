package io.configd.raft;

import io.configd.common.NodeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RaftNodeApiUnitTest {

    private static final NodeId N1 = NodeId.of(1);
    private static final NodeId N2 = NodeId.of(2);

    static final class CapturingTransport implements RaftTransport {
        final List<RaftMessage> sent = new ArrayList<>();
        @Override public void send(NodeId target, RaftMessage message) { sent.add(message); }
    }

    static final class CountingStateMachine implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    private static RaftNode singleNodeLeader() {
        RaftConfig config = RaftConfig.of(N1, Set.of());
        RaftNode node = new RaftNode(config, new RaftLog(), new CapturingTransport(),
                new CountingStateMachine(), new java.util.Random(42));
        for (int i = 0; i < 301; i++) node.tick();
        assertEquals(RaftRole.LEADER, node.role());
        return node;
    }

    private static RaftNode follower() {
        RaftConfig config = RaftConfig.of(N1, Set.of(N2));
        return new RaftNode(config, new RaftLog(), new CapturingTransport(),
                new CountingStateMachine(), new java.util.Random(1));
    }


    @Test
    void rejectsNullOrEmptyCommand() {
        RaftNode node = singleNodeLeader();
        assertThrows(IllegalArgumentException.class, () -> node.propose(null));
        assertThrows(IllegalArgumentException.class, () -> node.propose(new byte[0]));
    }

    @Test
    void rejectsOversizedCommand() {
        RaftNode node = singleNodeLeader();
        byte[] tooBig = new byte[1 * 1024 * 1024 + 1];
        assertThrows(IllegalArgumentException.class, () -> node.propose(tooBig));
        byte[] atLimit = new byte[1 * 1024 * 1024];
        atLimit[0] = 7;
        assertEquals(ProposalResult.ACCEPTED, node.propose(atLimit).result());
    }

    @Test
    void rejectsConfigChangeMagicFromClient() {
        RaftNode node = singleNodeLeader();
        byte[] rcfg = new byte[]{0x52, 0x43, 0x46, 0x47, 1, 2, 3};
        assertThrows(IllegalArgumentException.class, () -> node.propose(rcfg));
    }

    @Test
    void nonLeaderProposeReturnsNotLeader() {
        RaftNode node = follower();
        assertEquals(ProposalResult.NOT_LEADER, node.propose(new byte[]{1}).result());
    }

    @Test
    void singleNodeProposeCommitsImmediately() {
        RaftNode node = singleNodeLeader();
        long before = node.log().commitIndex();
        ProposeOutcome outcome = node.propose(new byte[]{1, 2, 3});
        assertEquals(ProposalResult.ACCEPTED, outcome.result());
        assertTrue(node.log().commitIndex() > before);
        assertEquals(node.log().lastIndex(), node.log().commitIndex());
    }


    @Test
    void transferRejectedWhenNotLeader() {
        RaftNode node = follower();
        assertFalse(node.transferLeadership(N2));
    }

    @Test
    void transferToSelfRejected() {
        RaftNode node = singleNodeLeader();
        assertFalse(node.transferLeadership(N1));
    }

    @Test
    void transferToNonVoterRejected() {
        RaftNode node = singleNodeLeader();
        assertFalse(node.transferLeadership(N2));
    }
}
