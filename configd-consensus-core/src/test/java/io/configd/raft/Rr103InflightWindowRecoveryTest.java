package io.configd.raft;

import io.configd.common.NodeId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RR-103 discriminating test (EXP-001, Session 4 / Workstream A1).
 * <p>
 * The per-peer pipelining window ({@code inflightCount}, capped at
 * {@code maxInflightAppends}) is incremented on every {@code sendAppendEntries}
 * and decremented ONLY by a response. The periodic heartbeat is routed through
 * the SAME {@code sendAppendEntries} gate. So once {@code maxInflightAppends}
 * messages to a peer are lost (partition / crash / drop), the window pins at the
 * cap and the leader is PERMANENTLY silenced toward that peer for the whole term
 * — no heartbeat, no backfill, no InstallSnapshot, no error, no metric. Only a
 * term change (becomeLeader resets the map) recovers it.
 * <p>
 * Expected behavior (architecture.md §6 Failure Handling; standard Raft
 * liveness): once a partitioned follower heals, the leader's next heartbeat
 * reaches it and it is backfilled to the committed prefix <em>within the same
 * term</em>.
 * <p>
 * This test isolates a follower, pins the window, heals, and asserts same-term
 * catch-up. Pre-fix it stays permanently behind (RED); post-fix the heartbeat
 * decay frees the window and it catches up (GREEN). Deterministic, no sleeps.
 */
class Rr103InflightWindowRecoveryTest {

    private static final NodeId N1 = NodeId.of(1);
    private static final NodeId N3 = NodeId.of(3);

    @Test
    void leaderBackfillsHealedFollowerWithinSameTerm() {
        RoutingCluster cluster = new RoutingCluster(3);
        cluster.electFirst();
        RaftNode n1 = cluster.node(N1);
        RaftNode n3 = cluster.node(N3);

        assertEquals(RaftRole.LEADER, n1.role(), "precondition: N1 leader");
        assertTrue(n1.log().commitIndex() >= 1, "precondition: no-op committed");

        // Baseline: commit a batch with all three replicating, so N3 starts current.
        for (int i = 0; i < 4; i++) {
            assertTrue(n1.propose(("base" + i).getBytes()).accepted());
        }
        cluster.step(200);
        long baseCommit = n1.log().commitIndex();
        assertEquals(baseCommit, n3.log().commitIndex(), "precondition: N3 fully current before partition");

        // --- Injection: isolate N3, then pin the leader's window toward it. ---
        cluster.partition(N3);
        // Propose more than maxInflightAppends (=10) entries in a tight burst with NO
        // intervening tick (so no heartbeat, no decay): each broadcast increments
        // inflightCount[N3]; no response can arrive (N3 dropped), so the window climbs
        // monotonically and pins at the cap. N2 still acks → these commit.
        for (int i = 0; i < 14; i++) {
            assertTrue(n1.propose(("part" + i).getBytes()).accepted());
        }
        // Precondition (review §4 non-vacuity): the window is pinned at the cap. Peeked
        // BEFORE any step() so no heartbeat decay has run — this is the exact wedge state
        // the leak makes permanent. (RaftConfig default maxInflightAppends = 10.)
        assertEquals(10, n1.inflightCountForTest(N3),
                "precondition: leader's in-flight window toward N3 is pinned at the cap");

        cluster.step(400); // commit via N1+N2; N3 misses everything

        long leaderHead = n1.log().commitIndex();
        long termBeforeHeal = n1.currentTerm();
        assertEquals(RaftRole.LEADER, n1.role(), "N1 keeps quorum with N2 → stays leader");
        assertTrue(leaderHead > baseCommit, "leader committed new entries during the partition");
        assertTrue(n3.log().commitIndex() < leaderHead,
                "non-vacuity: N3 fell behind while partitioned (commit "
                        + n3.log().commitIndex() + " < " + leaderHead + ")");

        // --- Heal and MEASURE the recovery bound (review §4: catch slow-recovery, not
        // just permanent-wedge). PRE-FIX recoveryTicks stays -1 (never catches up). ---
        cluster.heal(N3);
        int recoveryTicks = -1;
        for (int t = 1; t <= 2000; t++) {
            cluster.step();
            if (n3.log().commitIndex() == leaderHead) {
                recoveryTicks = t;
                break;
            }
        }

        // RR-103 property: same-term backfill, no election needed.
        assertEquals(termBeforeHeal, n1.currentTerm(),
                "recovery must be by backfill within the SAME term — no new election");
        assertEquals(RaftRole.LEADER, n1.role(), "N1 must remain leader through recovery");
        assertTrue(recoveryTicks > 0 && recoveryTicks <= 500,
                "after heal, the healed follower must backfill to the committed prefix within a"
                        + " few heartbeat intervals — PRE-FIX it stays pinned behind forever"
                        + " (inflight window never freed). measured recoveryTicks=" + recoveryTicks);
        assertEquals(leaderHead, n3.log().commitIndex(), "N3 fully current after recovery");
        // recoveryTicks feeds docs/session-4/recovery-bounds.md (follower-backfill-after-heal).
        System.out.println("[RR-103] follower-backfill-after-heal recoveryTicks=" + recoveryTicks
                + " (sim ticks; heartbeat interval=" + n1.heartbeatTimeoutTicksForTest() + " ticks)");
    }
}
