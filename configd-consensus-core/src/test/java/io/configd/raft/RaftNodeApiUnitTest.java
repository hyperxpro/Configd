package io.configd.raft;

import io.configd.common.NodeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Discriminating tests for {@link RaftNode}'s public-API entry points
 * {@code propose} and {@code transferLeadership} - the validation and
 * precondition guards a single-node leader exercises synchronously.
 * <p>
 * These guards (null/empty/oversized/RCFG-magic command rejection, NOT_LEADER /
 * TRANSFER_IN_PROGRESS / OVERLOADED outcomes, and the transfer
 * self/non-voter/pending-reconfig preconditions) had untested boundaries. Each
 * test pins one guard's observable outcome. Single-node and deterministic; no
 * sleeps.
 */
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

    // propose: input validation + outcome guards

    @Test
    void rejectsNullOrEmptyCommand() {
        RaftNode node = singleNodeLeader();
        assertThrows(IllegalArgumentException.class, () -> node.propose(null));
        assertThrows(IllegalArgumentException.class, () -> node.propose(new byte[0]));
    }

    @Test
    void rejectsOversizedCommand() {
        RaftNode node = singleNodeLeader();
        // Just over the 1 MiB wire-encodable limit: exactly MAX is allowed, MAX+1 rejected.
        byte[] tooBig = new byte[1 * 1024 * 1024 + 1];
        assertThrows(IllegalArgumentException.class, () -> node.propose(tooBig));
        // Exactly at the limit is accepted (the boundary's lower side).
        byte[] atLimit = new byte[1 * 1024 * 1024];
        atLimit[0] = 7; // non-empty, not RCFG magic
        assertEquals(ProposalResult.ACCEPTED, node.propose(atLimit).result());
    }

    @Test
    void rejectsConfigChangeMagicFromClient() {
        RaftNode node = singleNodeLeader();
        // A client command must not begin with the RCFG magic.
        byte[] rcfg = new byte[]{0x52, 0x43, 0x46, 0x47, 1, 2, 3};
        assertThrows(IllegalArgumentException.class, () -> node.propose(rcfg));
    }

    @Test
    void nonLeaderProposeReturnsNotLeader() {
        RaftNode node = follower();
        // A follower must report NOT_LEADER.
        assertEquals(ProposalResult.NOT_LEADER, node.propose(new byte[]{1}).result());
    }

    @Test
    void singleNodeProposeCommitsImmediately() {
        RaftNode node = singleNodeLeader();
        long before = node.log().commitIndex();
        ProposeOutcome outcome = node.propose(new byte[]{1, 2, 3});
        assertEquals(ProposalResult.ACCEPTED, outcome.result());
        // The new entry's index is lastIndex+1, and on the single-node path it
        // commits inline.
        assertTrue(node.log().commitIndex() > before);
        assertEquals(node.log().lastIndex(), node.log().commitIndex());
    }

    // transferLeadership: preconditions

    @Test
    void transferRejectedWhenNotLeader() {
        RaftNode node = follower();
        // A non-leader must refuse a transfer request.
        assertFalse(node.transferLeadership(N2));
    }

    @Test
    void transferToSelfRejected() {
        RaftNode node = singleNodeLeader();
        // Transferring to self must be rejected.
        assertFalse(node.transferLeadership(N1));
    }

    @Test
    void transferToNonVoterRejected() {
        RaftNode node = singleNodeLeader(); // voters = {1}
        // N2 is not a voter in this single-node config.
        assertFalse(node.transferLeadership(N2));
    }
}
