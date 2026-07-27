package io.configd.testkit;

import io.configd.raft.RaftRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(300) // CI hygiene on the 2-vCPU box: fail loudly rather than hang the nightly
class CoalescedHeartbeatLivenessTest {

    private static final int NODES = 5;
    private static final ConsistencyPropertyTests.ClusterHarness.HeartbeatFault NONE =
            ConsistencyPropertyTests.ClusterHarness.HeartbeatFault.NONE;
    private static final ConsistencyPropertyTests.ClusterHarness.HeartbeatFault DROP =
            ConsistencyPropertyTests.ClusterHarness.HeartbeatFault.DROP;
    private static final ConsistencyPropertyTests.ClusterHarness.HeartbeatFault DELAY =
            ConsistencyPropertyTests.ClusterHarness.HeartbeatFault.DELAY;


    @Test
    void noSpuriousElectionUnderSustainedLoad() {
        int seeds = Integer.getInteger("configd.m3.livenessSeeds", 40);
        int loadTicks = Integer.getInteger("configd.m3.loadTicks", 800);
        int reached = 0;
        for (long seed = 0; seed < seeds; seed++) {
            ConsistencyPropertyTests.ClusterHarness c =
                    new ConsistencyPropertyTests.ClusterHarness(seed, NODES);
            int leader = electStableLeader(c, 1500);
            if (leader < 0) {
                continue; // rare election stall on this seed - accounted for by the vacuity floor below
            }
            long term0 = maxTerm(c);

            // Sustained write load: propose every tick for a long window. With the real coalescing drain,
            // the leader keeps every follower's election timer reset, so NO new term is ever started and
            // the same node stays leader - i.e. NO spurious election.
            for (int t = 0; t < loadTicks; t++) {
                c.proposePut(leader, "k", "v" + t);
                c.tick();
                assertEquals(term0, maxTerm(c),
                        "SPURIOUS ELECTION under load WITH coalescing (seed=" + seed + ", tick=" + t
                                + "): max term rose from " + term0 + " — a coalesced heartbeat was dropped,"
                                + " delayed, or mistimed.");
                assertEquals(RaftRole.LEADER, c.node(leader).role(),
                        "leader " + leader + " stepped down under load (seed=" + seed + ", tick=" + t + ")");
            }
            reached++;
        }
        assertTrue(reached >= seeds * 0.8,
                "vacuity: only " + reached + "/" + seeds + " seeds reached the under-load assertion "
                        + "(a stable leader must be electable on the overwhelming majority of no-fault seeds)");
    }

    @Test
    void noSpuriousElectionWhenIdle() {
        // The sharper test: with NO writes, the coalesced heartbeat is the ONLY thing keeping a follower's
        // election timer reset (under load, entry-carrying appends do that too). So an idle cluster is
        // where a dropped/delayed/mistimed coalesced heartbeat would bite hardest. With the real drain, an
        // idle cluster never spuriously re-elects.
        int seeds = Integer.getInteger("configd.m3.livenessSeeds", 40);
        int idleTicks = Integer.getInteger("configd.m3.idleTicks", 800);
        int reached = 0;
        for (long seed = 0; seed < seeds; seed++) {
            ConsistencyPropertyTests.ClusterHarness c =
                    new ConsistencyPropertyTests.ClusterHarness(seed, NODES);
            int leader = electStableLeader(c, 1500);
            if (leader < 0) {
                continue;
            }
            long term0 = maxTerm(c);
            for (int t = 0; t < idleTicks; t++) {
                c.tick(); // idle - only heartbeats flow
                assertEquals(term0, maxTerm(c),
                        "SPURIOUS ELECTION while idle WITH coalescing (seed=" + seed + ", tick=" + t
                                + "): the coalesced heartbeat failed to keep a follower alive.");
                assertEquals(RaftRole.LEADER, c.node(leader).role(),
                        "idle leader " + leader + " stepped down (seed=" + seed + ", tick=" + t + ")");
            }
            reached++;
        }
        assertTrue(reached >= seeds * 0.8,
                "vacuity: only " + reached + "/" + seeds + " seeds reached the idle assertion");
    }


    @Test
    void droppedCoalescedHeartbeat_preventsStableLeadership() {
        // With every coalesced heartbeat dropped, votes still flow (RequestVote is not coalesced), so a
        // leader is elected - but it cannot hold leadership without heartbeats, so no node is ever a
        // stable leader for 120 consecutive ticks. If a stable leader DID persist, the no-spurious-election
        // proof above would be vacuous (it would pass even with a broken drain).
        int churned = 0;
        int trials = 10;
        for (long seed = 0; seed < trials; seed++) {
            ConsistencyPropertyTests.ClusterHarness c =
                    new ConsistencyPropertyTests.ClusterHarness(seed, NODES);
            c.injectHeartbeatFault(DROP, 0);
            if (electStableLeader(c, 2500) < 0) {
                churned++;
            }
        }
        assertTrue(churned >= trials - 1,
                "a DROPPED coalesced heartbeat MUST destabilize leadership (got " + churned + "/" + trials
                        + " churning) — the no-spurious-election sweep would otherwise be vacuous.");
    }

    @Test
    void delayedCoalescedHeartbeat_pastTimeout_causesSpuriousElection() {
        // Elect a stable leader with a healthy drain, then go IDLE (no writes - so the coalesced heartbeat
        // is the only liveness signal) while delaying heartbeats well past the election timeout. Followers
        // time out before the late heartbeat arrives and start an election -> the term rises (a spurious
        // election). At least one must occur, or "no spurious election while idle" is unfalsifiable.
        int spurious = 0;
        int reached = 0;
        int trials = 10;
        for (long seed = 0; seed < trials; seed++) {
            ConsistencyPropertyTests.ClusterHarness c =
                    new ConsistencyPropertyTests.ClusterHarness(seed, NODES);
            int leader = electStableLeader(c, 1500);
            if (leader < 0) {
                continue;
            }
            reached++;
            long term0 = maxTerm(c);
            int leaderNode = leader;
            c.injectHeartbeatFault(DELAY, 600); // 600ms >> the 150-300ms election timeout
            boolean churned = false;
            for (int t = 0; t < 600; t++) {
                c.tick(); // idle - only the (now delayed) heartbeats matter
                // Detect destabilization either way: a follower winning a fresh election (maxTerm rises)
                // OR the leader losing quorum and stepping down at the SAME term (CheckQuorum - invisible
                // to maxTerm alone, so we also check leadership directly).
                if (maxTerm(c) > term0 || c.node(leaderNode).role() != RaftRole.LEADER) {
                    churned = true;
                    break;
                }
            }
            if (churned) {
                spurious++;
            }
        }
        assertTrue(reached >= trials - 2, "vacuity: too few seeds elected a stable leader to test (" + reached + ")");
        assertTrue(spurious >= reached - 1,
                "DELAYING a coalesced heartbeat past the election timeout MUST destabilize leadership "
                        + "(got " + spurious + "/" + reached + ") — else the no-spurious proof is vacuous.");
    }

    @Test
    void noSpuriousElectionUnderLowLoad() {
        // The blind spot between idle and saturating load: one write every ~80 ticks is too
        // sparse for the entry-carrying appends to substitute for heartbeats (heartbeat interval is 50
        // ticks), so this is the regime where a delayed/dropped coalesced heartbeat bites hardest. With the
        // real drain, the leader stays stable here too.
        int seeds = Integer.getInteger("configd.m3.livenessSeeds", 40);
        int ticks = Integer.getInteger("configd.m3.lowLoadTicks", 800);
        int writeEvery = 80;
        int reached = 0;
        for (long seed = 0; seed < seeds; seed++) {
            ConsistencyPropertyTests.ClusterHarness c =
                    new ConsistencyPropertyTests.ClusterHarness(seed, NODES);
            int leader = electStableLeader(c, 1500);
            if (leader < 0) {
                continue;
            }
            long term0 = maxTerm(c);
            for (int t = 0; t < ticks; t++) {
                if (t % writeEvery == 0) {
                    c.proposePut(leader, "k", "v" + t);
                }
                c.tick();
                assertEquals(term0, maxTerm(c),
                        "SPURIOUS ELECTION under LOW load WITH coalescing (seed=" + seed + ", tick=" + t + ")");
                assertEquals(RaftRole.LEADER, c.node(leader).role(),
                        "low-load leader " + leader + " stepped down (seed=" + seed + ", tick=" + t + ")");
            }
            reached++;
        }
        assertTrue(reached >= seeds * 0.8,
                "vacuity: only " + reached + "/" + seeds + " seeds reached the low-load assertion");
    }

    @Test
    void singlePeerStarvation_doesNotCauseSpuriousElection_preVoteShield() {
        // The partial-aggregate failure: a coalescer bug that drops only ONE peer's slot.
        // That follower times out and churns PreVotes - but the other followers still hear the leader and
        // reject the PreVotes (the PreVote shield), so NO spurious election occurs and the leader (which
        // keeps a quorum: only one of five peers is starved) stays leader. This proves S3's property holds
        // even under a per-peer fault, and the PreVote counter proves the fault is REAL (not vacuous).
        int starved = 0;
        int reached = 0;
        int trials = 10;
        for (long seed = 0; seed < trials; seed++) {
            ConsistencyPropertyTests.ClusterHarness c =
                    new ConsistencyPropertyTests.ClusterHarness(seed, NODES);
            int leader = electStableLeader(c, 1500);
            if (leader < 0) {
                continue;
            }
            reached++;
            long term0 = maxTerm(c);
            int victim = (leader + 1) % NODES; // a follower
            int preVotesBefore = c.preVotesSent(victim);
            c.dropHeartbeatsToPeer(io.configd.common.NodeId.of(victim));
            for (int t = 0; t < 600; t++) {
                c.tick(); // idle
                assertEquals(term0, maxTerm(c),
                        "per-peer starvation caused a SPURIOUS ELECTION (seed=" + seed + ", tick=" + t
                                + ") — PreVote should have shielded it.");
                assertEquals(RaftRole.LEADER, c.node(leader).role(),
                        "leader stepped down under single-peer starvation (quorum should hold; seed=" + seed + ")");
            }
            if (c.preVotesSent(victim) > preVotesBefore) {
                starved++; // the victim really was starved (it churned PreVotes) - non-vacuity
            }
        }
        assertTrue(reached >= trials - 2, "vacuity: too few seeds elected a stable leader (" + reached + ")");
        assertTrue(starved >= reached - 1,
                "the single-peer drop must actually starve the victim (it should churn PreVotes) — got "
                        + starved + "/" + reached + "; otherwise this test is vacuous.");
    }

    private static int electStableLeader(ConsistencyPropertyTests.ClusterHarness c, int maxTicks) {
        int candidate = -1;
        int stable = 0;
        for (int t = 0; t < maxTicks; t++) {
            c.tick();
            int leader = c.findLeader();
            if (leader >= 0 && leader == candidate) {
                if (++stable >= 120) {
                    return leader;
                }
            } else {
                candidate = leader;
                stable = (leader >= 0) ? 1 : 0;
            }
        }
        return -1;
    }

    private static long maxTerm(ConsistencyPropertyTests.ClusterHarness c) {
        long max = 0;
        for (int i = 0; i < NODES; i++) {
            max = Math.max(max, c.node(i).currentTerm());
        }
        return max;
    }
}
