package io.configd.raft;

import io.configd.common.NodeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@code RaftNode.becomeFollower} clears the pending ReadIndex state on
 * step-down ({@code readIndexState.clear()}). The untested gap was the
 * removal of that {@code clear()}: with it gone, a linearizable
 * read that was CONFIRMED under an OLD leadership term survives the step-down and
 * is served as "ready" again after the node RE-acquires leadership at a higher
 * term - a cross-term stale read (the read was confirmed against state/authority
 * that no longer holds). The per-call leadership re-check in {@code isReadReady}
 * masks the bug while the node is a follower, so the removal is only observable
 * across a step-down -> re-election cycle while the same read id is still held.
 */
class ReadIndexStepDownClearTest {

    private static final class NoopTransport implements RaftTransport {
        @Override public void send(NodeId target, RaftMessage message) { }
    }

    private static final class NoopStateMachine implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] s) { }
    }

    private static RaftNode singleNodeLeader() {
        RaftConfig config = RaftConfig.of(NodeId.of(1), Set.of()); // single-node: reads confirm immediately
        RaftNode node = new RaftNode(config, new RaftLog(), new NoopTransport(), new NoopStateMachine(),
                new java.util.Random(1));
        for (int i = 0; i < 400 && node.role() != RaftRole.LEADER; i++) {
            node.tick();
        }
        assertEquals(RaftRole.LEADER, node.role(), "single-node cluster must self-elect to LEADER");
        return node;
    }

    @Test
    void pendingReadIsClearedOnStepDownAndNotServedAfterReElection() {
        RaftNode node = singleNodeLeader();

        // Start a linearizable read; in a single-node cluster it is immediately
        // leadership-confirmed and READY (lastApplied >= readIndex).
        long readId = node.readIndex();
        assertTrue(readId >= 0, "leader must accept a read");
        assertTrue(node.isReadReady(readId), "single-node read is immediately ready");

        // Step DOWN: a higher-term AppendEntries from another node forces
        // becomeFollower(higherTerm), whose readIndexState.clear() must discard the
        // pending read. (While a follower, isReadReady is false anyway via the
        // role re-check - that is NOT what this test pins.)
        long higherTerm = node.currentTerm() + 5;
        node.handleMessage(new AppendEntriesRequest(higherTerm, NodeId.of(2), 0, 0, List.of(), 0));
        assertEquals(RaftRole.FOLLOWER, node.role(), "a higher-term AppendEntries steps the leader down");

        // RE-ACQUIRE leadership at a still-higher term.
        for (int i = 0; i < 400 && node.role() != RaftRole.LEADER; i++) {
            node.tick();
        }
        assertEquals(RaftRole.LEADER, node.role(), "the single node re-elects itself");
        assertTrue(node.currentTerm() > higherTerm, "re-election is at a strictly higher term");

        // The OLD read id (confirmed under the old term) MUST NOT be serveable now.
        // becomeFollower's clear() discarded it, so isReadReady(oldReadId) is false
        // (the read id is unknown to ReadIndexState). WITHOUT the clear, the stale
        // read survives, lastApplied still covers its readIndex,
        // role is LEADER again -> isReadReady returns TRUE: a read confirmed under
        // the OLD leadership is served under the NEW one (linearizability break).
        assertFalse(node.isReadReady(readId),
                "RR-085: a ReadIndex confirmed under the OLD leadership term must be CLEARED on "
                        + "step-down and must NOT be served after re-election — leaving it (the "
                        + "removed readIndexState.clear()) serves a cross-term stale read");

        // A FRESH read on the new leadership is of course serveable (liveness).
        long freshRead = node.readIndex();
        assertTrue(node.isReadReady(freshRead), "a fresh read on the re-acquired leadership is ready");
    }
}
