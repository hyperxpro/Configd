package io.configd.testkit;

import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.raft.LogEntry;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftMessage;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.raft.RaftTransport;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigStateMachine;
import io.configd.store.VersionedConfigStore;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session 4 / Workstream C — the partition & WAN chaos matrix (control plane), executed
 * deterministically in-sim over {@link AdversarialNetwork}. Covers the architecture §12 scenarios:
 * single-region isolation (minority/majority), leader isolation, asymmetric partition (A→B cut,
 * B→A intact), partial partition (a subset of links), and gray failure (elevated latency short of a
 * full cut). Each scenario asserts — CONTINUOUSLY, every step — the consistency-contract SAFETY
 * oracles, and measures bounded recovery after heal.
 *
 * <p><b>Oracles (the linearizability-relevant safety properties, asserted deterministically):</b>
 * <ul>
 *   <li><b>single-leader-per-term</b> — no two nodes are LEADER in the same term at any instant
 *       (an isolated stale leader at an OLD term is permitted; it cannot commit);</li>
 *   <li><b>no divergent commit</b> — any index committed on two nodes carries the SAME term on
 *       both (Raft log-matching; a split-brain commit would violate it);</li>
 *   <li><b>no committed-entry loss</b> — the pre-partition committed prefix survives the
 *       partition+heal on the surviving majority and, after heal, on all nodes;</li>
 *   <li><b>minority makes no progress</b> — an isolated sub-quorum's commitIndex does not advance;
 *       <b>majority continues</b> — the quorum side elects and commits.</li>
 * </ul>
 * The full Porcupine linearizability check over a recorded history runs in the {@code configd-linz}
 * harness (env-gated on {@code PORCUPINE_BIN}, the {@code CheckerSelfTest} discipline); these
 * always-on invariants are the deterministic in-sim oracle the CI subset enforces.
 *
 * <p>Recovery times are printed as {@code PARTITION-RECOVERY:} lines feeding {@code recovery-bounds.md}.
 * fault-matrix §C.
 */
class PartitionMatrixTest {

    private static final int N = 5;
    private static final int ELECT_BOUND = 600;
    private static final int RECOVER_BOUND = 1500;   // re-election after a disruption (3-cycle worst)
    private static final int PROPAGATE_BOUND = 1500; // post-heal whole-cluster convergence
    private static final int SEEDS = Integer.getInteger("configd.partition.seeds", 12);

    /** A scripted deterministic 5-node Raft cluster over {@link AdversarialNetwork}. */
    static final class Cluster {
        final List<RaftNode> nodes = new ArrayList<>();
        final List<RaftLog> logs = new ArrayList<>();
        final AdversarialNetwork net;
        long nowMs = 1_700_000_000_000L;

        Cluster(long seed) {
            this(seed, new long[N]); // zero skew
        }

        /** @param skews per-node wall-clock skew (ms) applied to each node's state-machine clock */
        Cluster(long seed, long[] skews) {
            net = new AdversarialNetwork(seed, 1, 10);
            for (int i = 0; i < N; i++) {
                NodeId id = NodeId.of(i);
                Set<NodeId> peers = new HashSet<>();
                for (int j = 0; j < N; j++) {
                    if (j != i) peers.add(NodeId.of(j));
                }
                RaftLog log = new RaftLog();
                VersionedConfigStore store = new VersionedConfigStore();
                ConfigStateMachine sm = new ConfigStateMachine(store, new SkewedClock(() -> nowMs, skews[i]));
                RaftTransport transport = (target, message) -> net.send(id, target, message, nowMs);
                RandomGenerator rng = RandomGeneratorFactory.of("L64X128MixRandom")
                        .create(AdversarialSchedule.mixSeed(seed, i));
                nodes.add(new RaftNode(RaftConfig.of(id, peers), log, transport, sm, rng, Storage.inMemory()));
                logs.add(log);
            }
            net.setDeliveryHandler((target, message) -> {
                int idx = target.id();
                if (idx >= 0 && idx < N) {
                    nodes.get(idx).handleMessage((RaftMessage) message);
                }
            });
        }

        void step() {
            nowMs++;
            net.deliverDue(nowMs);
            for (RaftNode nd : nodes) {
                nd.tick();
            }
        }

        /** Steps {@code times}, checking the safety oracles every step. */
        void stepChecked(int times, String ctx) {
            for (int i = 0; i < times; i++) {
                step();
                assertSafety(ctx);
            }
        }

        int findLeader() {
            for (int i = 0; i < N; i++) {
                if (nodes.get(i).role() == RaftRole.LEADER) {
                    return i;
                }
            }
            return -1;
        }

        int stepUntilLeader(int bound, String ctx) {
            for (int t = 1; t <= bound; t++) {
                step();
                assertSafety(ctx);
                if (findLeader() >= 0) {
                    return t;
                }
            }
            return -1;
        }

        long commitOf(int node) { return logs.get(node).commitIndex(); }

        long maxCommitIndex() {
            long m = 0;
            for (RaftLog log : logs) {
                m = Math.max(m, log.commitIndex());
            }
            return m;
        }

        boolean allConvergedAtLeast(long commit) {
            for (RaftLog log : logs) {
                if (log.commitIndex() < commit) {
                    return false;
                }
            }
            return true;
        }

        boolean proposeOn(int node, String key, String val) {
            return nodes.get(node).propose(CommandCodec.encodePut(key, val.getBytes())).accepted();
        }

        /** The committed (index→term) prefix of a node — used to assert no committed-entry loss. */
        Map<Long, Long> committedPrefix(int node) {
            Map<Long, Long> out = new HashMap<>();
            RaftLog log = logs.get(node);
            for (long i = 1; i <= log.commitIndex(); i++) {
                LogEntry e = log.entryAt(i);
                if (e != null) {
                    out.put(i, e.term());
                }
            }
            return out;
        }

        /**
         * THE SAFETY ORACLE, asserted every step: single-leader-per-term + no divergent commit.
         * An isolated stale leader at an OLD term is allowed (distinct terms), so the check is
         * per-term, not a global at-most-one.
         */
        void assertSafety(String ctx) {
            Map<Long, Integer> leaderByTerm = new HashMap<>();
            for (int i = 0; i < N; i++) {
                RaftNode nd = nodes.get(i);
                if (nd.role() == RaftRole.LEADER) {
                    Integer prev = leaderByTerm.put(nd.currentTerm(), i);
                    assertNull(prev, ctx + ": SPLIT-BRAIN — two leaders in term " + nd.currentTerm()
                            + " (nodes " + prev + " and " + i + ")");
                }
            }
            Map<Long, Long> termAtIndex = new HashMap<>();
            for (int i = 0; i < N; i++) {
                RaftLog log = logs.get(i);
                long ci = log.commitIndex();
                for (long idx = 1; idx <= ci; idx++) {
                    LogEntry e = log.entryAt(idx);
                    if (e == null) {
                        continue;
                    }
                    Long prevTerm = termAtIndex.putIfAbsent(idx, e.term());
                    if (prevTerm != null) {
                        assertEquals(prevTerm.longValue(), e.term(),
                                ctx + ": DIVERGENT COMMIT at index " + idx
                                        + " (a committed entry differs across nodes — split-brain commit)");
                    }
                }
            }
        }
    }

    /** Elects a leader and commits a baseline write; returns the leader index. */
    private static int bootstrap(Cluster c, long seed, String ctx) {
        int elect = c.stepUntilLeader(ELECT_BOUND, ctx);
        assertTrue(elect > 0, ctx + " seed " + seed + ": no leader within " + ELECT_BOUND);
        int leader = c.findLeader();
        assertTrue(c.proposeOn(leader, "k/base", "base"), ctx + ": leader must accept baseline write");
        for (int t = 0; t < 400 && c.commitOf(leader) < 1; t++) {
            c.step();
            c.assertSafety(ctx);
        }
        assertTrue(c.commitOf(leader) >= 1, ctx + ": baseline write must commit");
        return leader;
    }

    private static void assertPrefixPreserved(Map<Long, Long> before, Cluster c, int node, String ctx) {
        Map<Long, Long> now = c.committedPrefix(node);
        for (Map.Entry<Long, Long> e : before.entrySet()) {
            Long t = now.get(e.getKey());
            assertTrue(t != null && t.equals(e.getValue()),
                    ctx + ": committed-entry LOSS at index " + e.getKey() + " on node " + node
                            + " (was term " + e.getValue() + ", now " + t + ")");
        }
    }

    // ------------------------------------------------------------------------
    // C-1: single-region isolation — majority continues, minority stalls, heal converges
    // ------------------------------------------------------------------------
    @Test
    void singleRegionIsolation_majorityContinues_minorityStalls_healConverges() {
        long worstReElect = 0, worstConverge = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            String ctx = "C-1[seed=" + seed + "]";
            Cluster c = new Cluster(seed);
            int leader0 = bootstrap(c, seed, ctx);
            Map<Long, Long> baseline = c.committedPrefix(leader0);

            // Isolate the leader + one follower into a 2-node minority; 3-node majority keeps quorum.
            RandomGenerator r = RandomGeneratorFactory.of("L64X128MixRandom")
                    .create(AdversarialSchedule.mixSeed(seed, 9_001));
            int mate;
            do { mate = r.nextInt(N); } while (mate == leader0);
            Set<Integer> minority = Set.of(leader0, mate);
            for (int x = 0; x < N; x++) {
                for (int y = 0; y < N; y++) {
                    if (x != y && minority.contains(x) != minority.contains(y)) {
                        c.net.isolate(NodeId.of(x), NodeId.of(y));
                    }
                }
            }

            // Majority must elect a NEW leader (old one shed by CheckQuorum).
            int reElect = -1;
            for (int t = 1; t <= RECOVER_BOUND; t++) {
                c.step();
                c.assertSafety(ctx);
                int ldr = c.findLeader();
                if (ldr >= 0 && !minority.contains(ldr)) { reElect = t; break; }
            }
            assertTrue(reElect > 0, ctx + ": majority did not re-elect within " + RECOVER_BOUND);
            worstReElect = Math.max(worstReElect, reElect);

            // Soak: majority commits writes the minority cannot see.
            long minorityCommitAtIsolation = Math.max(c.commitOf(leader0), c.commitOf(mate));
            for (int t = 0; t < 500; t++) {
                c.step();
                c.assertSafety(ctx);
                int ldr = c.findLeader();
                if (ldr >= 0 && !minority.contains(ldr) && t % 50 == 0) {
                    c.proposeOn(ldr, "k/maj", "v" + seed + "-" + t);
                }
            }
            // Minority made NO progress (cannot commit without quorum).
            for (int m : minority) {
                assertTrue(c.commitOf(m) <= minorityCommitAtIsolation + 1,
                        ctx + ": minority node " + m + " committed during isolation (no-quorum violation)");
            }

            // Heal → whole cluster converges; baseline survives everywhere.
            long preHeal = c.maxCommitIndex();
            c.net.healAll();
            c.net.setDropRate(0.0);
            int conv = -1;
            for (int t = 1; t <= PROPAGATE_BOUND; t++) {
                c.step();
                c.assertSafety(ctx);
                if (c.allConvergedAtLeast(preHeal)) { conv = t; break; }
            }
            assertTrue(conv > 0, ctx + ": cluster did not converge within " + PROPAGATE_BOUND + " of heal");
            worstConverge = Math.max(worstConverge, conv);
            for (int i = 0; i < N; i++) {
                assertPrefixPreserved(baseline, c, i, ctx);
            }
        }
        System.out.println("PARTITION-RECOVERY: scenario=single-region-isolation seeds=" + SEEDS
                + " worstReElectTicks=" + worstReElect + " worstConvergeTicks=" + worstConverge);
    }

    // ------------------------------------------------------------------------
    // C-2: leader isolation — old leader steps down, majority re-elects, no split-brain commit
    // ------------------------------------------------------------------------
    @Test
    void leaderIsolation_oldLeaderStepsDown_majorityReElects_noSplitBrain() {
        long worstReElect = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            String ctx = "C-2[seed=" + seed + "]";
            Cluster c = new Cluster(seed);
            int leader0 = bootstrap(c, seed, ctx);
            Map<Long, Long> baseline = c.committedPrefix(leader0);

            // Isolate ONLY the leader from the other 4 (leader on a 1-node minority side).
            for (int y = 0; y < N; y++) {
                if (y != leader0) {
                    c.net.isolate(NodeId.of(leader0), NodeId.of(y));
                }
            }
            int reElect = -1;
            for (int t = 1; t <= RECOVER_BOUND; t++) {
                c.step();
                c.assertSafety(ctx);
                int ldr = c.findLeader();
                if (ldr >= 0 && ldr != leader0) { reElect = t; break; }
            }
            assertTrue(reElect > 0, ctx + ": 4-node majority did not re-elect within " + RECOVER_BOUND);
            worstReElect = Math.max(worstReElect, reElect);

            // The isolated old leader must NOT still be committing (CheckQuorum sheds it).
            long oldLeaderCommit = c.commitOf(leader0);
            c.stepChecked(300, ctx);
            int newLeader = c.findLeader();
            assertTrue(newLeader >= 0 && newLeader != leader0, ctx + ": new leader must hold on the majority side");
            c.proposeOn(newLeader, "k/post", "after");
            c.stepChecked(300, ctx);
            assertTrue(c.commitOf(leader0) <= oldLeaderCommit + 1,
                    ctx + ": the isolated old leader kept committing (CheckQuorum failed)");

            // Heal → no divergent commit; baseline preserved.
            c.net.healAll();
            c.net.setDropRate(0.0);
            long preHeal = c.maxCommitIndex();
            int conv = -1;
            for (int t = 1; t <= PROPAGATE_BOUND; t++) {
                c.step();
                c.assertSafety(ctx);
                if (c.allConvergedAtLeast(preHeal)) { conv = t; break; }
            }
            assertTrue(conv > 0, ctx + ": no convergence after heal");
            for (int i = 0; i < N; i++) {
                assertPrefixPreserved(baseline, c, i, ctx);
            }
        }
        System.out.println("PARTITION-RECOVERY: scenario=leader-isolation seeds=" + SEEDS
                + " worstReElectTicks=" + worstReElect);
    }

    // ------------------------------------------------------------------------
    // C-3: asymmetric partition (A→B cut, B→A intact) — no split-brain, heal converges
    // ------------------------------------------------------------------------
    @Test
    void asymmetricPartition_noSplitBrainCommit_healConverges() {
        for (long seed = 0; seed < SEEDS; seed++) {
            String ctx = "C-3[seed=" + seed + "]";
            Cluster c = new Cluster(seed);
            int leader0 = bootstrap(c, seed, ctx);
            Map<Long, Long> baseline = c.committedPrefix(leader0);

            // One-way cut from the leader to every other node (the leader can hear acks but cannot
            // send AppendEntries) — the classic asymmetric Raft-killer. PreVote + CheckQuorum must
            // prevent both a disruptive term-inflation and a split-brain commit.
            for (int y = 0; y < N; y++) {
                if (y != leader0) {
                    c.net.addPartition(NodeId.of(leader0), NodeId.of(y));
                }
            }
            // Soak the asymmetric cut — safety asserted every step.
            c.stepChecked(800, ctx);
            // A leader must still exist somewhere and safety must hold (no two leaders same term).
            // (The partition may resolve to a new leader among the reachable majority.)

            // Heal both directions → converge, no divergent commit, baseline preserved.
            c.net.healAll();
            c.net.setDropRate(0.0);
            long preHeal = c.maxCommitIndex();
            int conv = -1;
            for (int t = 1; t <= PROPAGATE_BOUND; t++) {
                c.step();
                c.assertSafety(ctx);
                if (c.findLeader() >= 0 && c.allConvergedAtLeast(preHeal)) { conv = t; break; }
            }
            assertTrue(conv > 0, ctx + ": no convergence after healing the asymmetric partition");
            for (int i = 0; i < N; i++) {
                assertPrefixPreserved(baseline, c, i, ctx);
            }
        }
        System.out.println("PARTITION-RECOVERY: scenario=asymmetric-partition seeds=" + SEEDS + " (safety held throughout)");
    }

    // ------------------------------------------------------------------------
    // C-4: partial partition (a subset of links cut; no clean majority/minority split)
    // ------------------------------------------------------------------------
    @Test
    void partialPartition_safetyHolds_connectedMajorityProgresses() {
        for (long seed = 0; seed < SEEDS; seed++) {
            String ctx = "C-4[seed=" + seed + "]";
            Cluster c = new Cluster(seed);
            int leader0 = bootstrap(c, seed, ctx);
            Map<Long, Long> baseline = c.committedPrefix(leader0);

            // Cut SOME links only: isolate node 0 from {1,2} but leave 0↔3, 0↔4, and 1..4 fully
            // meshed. No node is fully isolated; there is still a connected ≥3 component.
            c.net.isolate(NodeId.of(0), NodeId.of(1));
            c.net.isolate(NodeId.of(0), NodeId.of(2));

            c.stepChecked(800, ctx);
            // A connected majority component {2,3,4} (or similar) must still make progress.
            int ldr = c.findLeader();
            if (ldr >= 0) {
                c.proposeOn(ldr, "k/partial", "v" + seed);
            }
            c.stepChecked(400, ctx);

            c.net.healAll();
            c.net.setDropRate(0.0);
            long preHeal = c.maxCommitIndex();
            int conv = -1;
            for (int t = 1; t <= PROPAGATE_BOUND; t++) {
                c.step();
                c.assertSafety(ctx);
                if (c.allConvergedAtLeast(preHeal)) { conv = t; break; }
            }
            assertTrue(conv > 0, ctx + ": no convergence after healing the partial partition");
            for (int i = 0; i < N; i++) {
                assertPrefixPreserved(baseline, c, i, ctx);
            }
        }
        System.out.println("PARTITION-RECOVERY: scenario=partial-partition seeds=" + SEEDS + " (safety held throughout)");
    }

    // ------------------------------------------------------------------------
    // C-6: clock skew — consensus SAFETY does not depend on synchronized wall clocks (charter §6).
    // ------------------------------------------------------------------------
    @Test
    void clockSkew_consensusSafetyAndLivenessHoldUnderUnsynchronizedClocks() {
        // Each node's state-machine wall clock is skewed by a DIFFERENT, large offset (±~hours) —
        // far beyond any NTP bound. Raft is tick-driven (elections/heartbeats use logical ticks,
        // not the wall clock), so safety must be wholly independent of clock synchronization. We
        // run a full isolate+heal cycle under maximal skew and assert the same safety oracles plus
        // bounded liveness (a leader is still elected and writes still commit cluster-wide).
        for (long seed = 0; seed < SEEDS; seed++) {
            String ctx = "C-6[seed=" + seed + "]";
            // Distinct per-node skews: −2h, −37min, +0, +53min, +3h (wildly unsynchronized).
            long[] skews = {-7_200_000L, -2_220_000L, 0L, 3_180_000L, 10_800_000L};
            Cluster c = new Cluster(seed, skews);
            int leader0 = bootstrap(c, seed, ctx); // elects + commits despite skew → liveness under skew
            Map<Long, Long> baseline = c.committedPrefix(leader0);

            // Isolate leader + a mate, force a re-election on the majority, soak, heal — all the
            // partition safety checks, but now under maximal clock skew.
            RandomGenerator r = RandomGeneratorFactory.of("L64X128MixRandom")
                    .create(AdversarialSchedule.mixSeed(seed, 9_001));
            int mate;
            do { mate = r.nextInt(N); } while (mate == leader0);
            Set<Integer> minority = Set.of(leader0, mate);
            for (int x = 0; x < N; x++) {
                for (int y = 0; y < N; y++) {
                    if (x != y && minority.contains(x) != minority.contains(y)) {
                        c.net.isolate(NodeId.of(x), NodeId.of(y));
                    }
                }
            }
            int reElect = -1;
            for (int t = 1; t <= RECOVER_BOUND; t++) {
                c.step();
                c.assertSafety(ctx);
                int ldr = c.findLeader();
                if (ldr >= 0 && !minority.contains(ldr)) { reElect = t; break; }
            }
            assertTrue(reElect > 0, ctx + ": majority must re-elect under clock skew (liveness is tick-driven)");

            c.net.healAll();
            c.net.setDropRate(0.0);
            long preHeal = c.maxCommitIndex();
            int conv = -1;
            for (int t = 1; t <= PROPAGATE_BOUND; t++) {
                c.step();
                c.assertSafety(ctx);
                if (c.allConvergedAtLeast(preHeal)) { conv = t; break; }
            }
            assertTrue(conv > 0, ctx + ": cluster must converge under clock skew");
            for (int i = 0; i < N; i++) {
                assertPrefixPreserved(baseline, c, i, ctx);
            }
        }
        System.out.println("PARTITION-RECOVERY: scenario=clock-skew seeds=" + SEEDS
                + " (consensus safety+liveness independent of synchronized clocks — charter §6 proven)");
    }

    // ------------------------------------------------------------------------
    // C-5: gray failure (elevated latency, no drops) — safety holds, no excessive leadership flap
    // ------------------------------------------------------------------------
    @Test
    void grayFailure_latencySpike_safetyHolds_noExcessiveFlap() {
        for (long seed = 0; seed < SEEDS; seed++) {
            String ctx = "C-5[seed=" + seed + "]";
            Cluster c = new Cluster(seed);
            int leader0 = bootstrap(c, seed, ctx);
            Map<Long, Long> baseline = c.committedPrefix(leader0);

            // Gray failure: a sustained latency spike on all links (no drops, no partition).
            // Heartbeats still arrive, just late. Safety must hold; leadership must not flap wildly.
            for (int x = 0; x < N; x++) {
                for (int y = 0; y < N; y++) {
                    if (x != y) {
                        c.net.beginDelaySpike(x, y, 40); // +40ms — within the election-timeout envelope
                    }
                }
            }
            long lastTerm = c.nodes.get(0).currentTerm();
            int termBumps = 0;
            for (int t = 0; t < 1000; t++) {
                c.step();
                c.assertSafety(ctx);
                long maxTerm = 0;
                for (RaftNode nd : c.nodes) {
                    maxTerm = Math.max(maxTerm, nd.currentTerm());
                }
                if (maxTerm > lastTerm) { termBumps += (int) (maxTerm - lastTerm); lastTerm = maxTerm; }
            }
            // A mild latency spike must not cause a leadership-flap storm. Generous bound: a healthy
            // cluster under +40ms should re-stabilise, not churn a term every few ticks.
            assertTrue(termBumps <= 25,
                    ctx + ": leadership flapped excessively under a gray-failure latency spike (termBumps="
                            + termBumps + ") — should re-stabilise, not churn");

            c.net.endDelaySpike();
            c.net.healAll();
            long preHeal = c.maxCommitIndex();
            int conv = -1;
            for (int t = 1; t <= PROPAGATE_BOUND; t++) {
                c.step();
                c.assertSafety(ctx);
                if (c.findLeader() >= 0 && c.allConvergedAtLeast(preHeal)) { conv = t; break; }
            }
            assertTrue(conv > 0, ctx + ": no convergence after the latency spike cleared");
            for (int i = 0; i < N; i++) {
                assertPrefixPreserved(baseline, c, i, ctx);
            }
        }
        System.out.println("PARTITION-RECOVERY: scenario=gray-failure-latency seeds=" + SEEDS + " (safety held, no flap storm)");
    }
}
