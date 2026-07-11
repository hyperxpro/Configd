package io.configd.raft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the ReadIndex no-op gate (Raft dissertation section 6.4, step 1; Ongaro,
 * raft-dev 2015): a newly-elected leader MUST commit an entry from its CURRENT term before it may
 * serve a linearizable read. Without the gate, a fresh leader whose local {@code commitIndex} still
 * lags an already-committed-and-acked write captures {@code readIndex = commitIndex} below that
 * write's index; {@link RaftNode#isReadReady} then passes ({@code lastApplied >= readIndex}) and the
 * read is served from an applied state that is behind the committed write - a phantom-stale /
 * phantom-absent linearizable read.
 *
 * <p>Discovered by the E1 faulted-linearizability matrix (seeds 20018 / 24017, N=3, adversarial
 * combination faults): a linearizable GET returned 404/absent for a committed-present key. The fix
 * gates {@link RaftNode#readIndex()} on the same {@code noopCommittedInCurrentTerm} signal that
 * {@code proposeConfigChange} already required ("Must commit no-op first").
 */
final class ReadIndexNoOpBeforeServeTest {

    @Test
    void freshLeaderDoesNotServeReadsUntilItsNoOpCommits() {
        RoutingCluster c = new RoutingCluster(3);
        RaftNode n1 = c.node(c.first());

        // Drive n1 alone to time out -> candidate (only n1 ticks, so no split vote).
        for (int i = 0; i < 301; i++) {
            n1.tick();
        }
        // Deliver rounds until n1 wins the election, and STOP the instant it is leader: at that
        // point its term no-op is appended but has not yet replicated + committed.
        for (int r = 0; r < 60 && n1.role() != RaftRole.LEADER; r++) {
            c.deliverRound();
        }
        assertEquals(RaftRole.LEADER, n1.role(), "n1 should have become leader");

        // THE GATE: a just-elected leader must refuse a linearizable read (readIndex() == -1, which
        // the server maps to 503 + X-Leader-Hint so the client retries) until its current-term no-op
        // commits. Before the fix this returned a valid but stale-low read id.
        assertEquals(-1L, n1.readIndex(),
                "a fresh leader must not serve a linearizable read before its term no-op commits");

        // Let the no-op replicate to a quorum and commit.
        for (int r = 0; r < 10; r++) {
            c.deliverRound();
        }
        assertTrue(n1.readIndex() >= 0L,
                "after the current-term no-op commits, the leader may serve linearizable reads");
    }

    @Test
    void singleNodeLeaderServesReadsImmediately() {
        // N=1: becomeLeader commits the no-op synchronously (self is a quorum), so the gate is
        // already satisfied the moment the node is leader - the fix must not regress single-node
        // read availability.
        RoutingCluster c = new RoutingCluster(1);
        RaftNode n1 = c.node(c.first());
        for (int i = 0; i < 301; i++) {
            n1.tick();
        }
        for (int r = 0; r < 5; r++) {
            c.deliverRound();
        }
        assertEquals(RaftRole.LEADER, n1.role(), "single-node cluster elects itself");
        assertTrue(n1.readIndex() >= 0L, "single-node leader must serve reads immediately");
    }
}
