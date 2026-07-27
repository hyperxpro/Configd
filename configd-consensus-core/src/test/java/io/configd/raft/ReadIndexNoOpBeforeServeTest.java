package io.configd.raft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression: fresh leader must commit a current-term no-op before serving linearizable reads (Raft 6.4). */
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

        // The gate: a just-elected leader must refuse a linearizable read (readIndex() == -1, which
        // the server maps to 503 + X-Leader-Hint so the client retries) until its current-term no-op
        // commits.
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
        // already satisfied the moment the node is leader; single-node read availability must not
        // regress.
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
