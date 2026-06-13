package io.configd.testkit;

import io.configd.common.NodeId;
import io.configd.common.Storage;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * First-class bounded-progress LIVENESS verdict for the consensus plane (Session 4 /
 * Workstream A2, EXP-002). Closes the asymmetry the RR-095 work exposed: safety had
 * 10k-seed sweep coverage, liveness had only anecdotes.
 * <p>
 * Safety sweeps ask "is anything incorrect ever observed". This sweep asks the dual
 * liveness question with a DEADLINE, which is the only honest way to tell a benign
 * never-healed-schedule stall (RR-095 — no recovery was ever possible) apart from a
 * recoverable-but-stuck defect (RR-103 class — the network healed but the cluster did not
 * recover in bounded time). The bound is applied ONLY after the fault clears, so a
 * never-healed schedule is correctly NOT a violation.
 * <p>
 * Per seed, scripted and deterministic (no sleeps):
 * <ol>
 *   <li><b>Bootstrap liveness:</b> a fresh 5-node cluster must elect a leader within
 *       {@link #ELECT_BOUND} ticks and commit a baseline write.</li>
 *   <li><b>Shatter:</b> partition into sub-quorum components (no group ≥ 3) — the cluster
 *       MUST stop having a leader within an election timeout (non-vacuity: the disruption is
 *       real; this is the temporary analog of an RR-095 never-healed schedule).</li>
 *   <li><b>Post-heal election (bounded progress):</b> heal fully; a leader MUST be
 *       (re-)elected within {@link #RECOVER_BOUND} ticks of the heal.</li>
 *   <li><b>Post-heal propagation (bounded progress):</b> a write proposed on the recovered
 *       leader MUST commit and propagate to ALL nodes within {@link #PROPAGATE_BOUND} ticks
 *       — the consensus-plane counterpart of "committed write propagates within the
 *       staleness bound after edge reconnect".</li>
 * </ol>
 * A deadline miss fails the test WITH the seed (deterministic replay/shrink handle), exactly
 * as a safety violation does. Default 200 seeds (gate set); override with
 * {@code -Dconfigd.liveness.sweepCount=N}.
 */
class LivenessBoundedProgressSweepTest {

    private static final int N = 5;
    private static final int ELECT_BOUND = 2_000;     // fresh-cluster election (ticks)
    private static final int RECOVER_BOUND = 1_500;   // majority re-election after losing leader
    private static final int PROPAGATE_BOUND = 800;   // post-heal convergence to all N nodes

    /** A scripted deterministic 5-node Raft cluster over {@link AdversarialNetwork}. */
    static final class Cluster {
        final List<RaftNode> nodes = new ArrayList<>();
        final List<RaftLog> logs = new ArrayList<>();
        final List<VersionedConfigStore> stores = new ArrayList<>();
        final AdversarialNetwork net;
        long nowMs = 1_700_000_000_000L;

        Cluster(long seed) {
            net = new AdversarialNetwork(seed, 1, 10);
            for (int i = 0; i < N; i++) {
                NodeId id = NodeId.of(i);
                Set<NodeId> peers = new HashSet<>();
                for (int j = 0; j < N; j++) {
                    if (j != i) peers.add(NodeId.of(j));
                }
                RaftLog log = new RaftLog();
                VersionedConfigStore store = new VersionedConfigStore();
                ConfigStateMachine sm = new ConfigStateMachine(store, new SkewedClock(() -> nowMs, 0));
                RaftTransport transport = (target, message) -> net.send(id, target, message, nowMs);
                RandomGenerator rng = RandomGeneratorFactory.of("L64X128MixRandom")
                        .create(AdversarialSchedule.mixSeed(seed, i));
                RaftNode node = new RaftNode(RaftConfig.of(id, peers), log, transport, sm, rng,
                        Storage.inMemory());
                nodes.add(node);
                logs.add(log);
                stores.add(store);
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

        void step(int times) {
            for (int i = 0; i < times; i++) {
                step();
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

        /** Steps until a leader exists, up to {@code bound}; returns ticks used, or -1 if none. */
        int stepUntilLeader(int bound) {
            for (int t = 1; t <= bound; t++) {
                step();
                if (findLeader() >= 0) {
                    return t;
                }
            }
            return -1;
        }

        long maxCommitIndex() {
            long m = 0;
            for (RaftLog log : logs) {
                m = Math.max(m, log.commitIndex());
            }
            return m;
        }

        boolean allConvergedBeyond(long commit) {
            for (RaftLog log : logs) {
                if (log.commitIndex() <= commit) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Isolate {@code leader} plus one seed-chosen follower into a 2-node minority,
         * leaving a 3-node majority that retains quorum. The majority can elect a new
         * leader during the partition; the minority cannot make progress. Returns the
         * minority node ids.
         */
        Set<Integer> isolateLeaderMinority(long seed, int leader) {
            RandomGenerator r = RandomGeneratorFactory.of("L64X128MixRandom")
                    .create(AdversarialSchedule.mixSeed(seed, 9_001));
            int mate;
            do {
                mate = r.nextInt(N);
            } while (mate == leader);
            Set<Integer> minority = Set.of(leader, mate);
            for (int x = 0; x < N; x++) {
                for (int y = 0; y < N; y++) {
                    if (x != y && minority.contains(x) != minority.contains(y)) {
                        net.isolate(NodeId.of(x), NodeId.of(y)); // cut cross-group edges only
                    }
                }
            }
            return minority;
        }
    }

    @Test
    void postHealElectionAndPropagationAreBounded() {
        int count = Integer.getInteger("configd.liveness.sweepCount", 200);
        long worstElect = 0, worstReElect = 0, worstConverge = 0;
        long sumConverge = 0;
        for (long seed = 0; seed < count; seed++) {
            Cluster c = new Cluster(seed);

            // (1) Bootstrap liveness: fresh cluster must elect a leader and commit a write.
            int electTicks = c.stepUntilLeader(ELECT_BOUND);
            assertTrue(electTicks > 0,
                    "seed " + seed + ": fresh cluster did not elect a leader within "
                            + ELECT_BOUND + " ticks (bootstrap liveness)");
            worstElect = Math.max(worstElect, electTicks);
            int leader0 = c.findLeader();
            assertTrue(c.nodes.get(leader0).propose(
                            CommandCodec.encodePut("k/base", "base".getBytes())).accepted(),
                    "seed " + seed + ": leader must accept a baseline write");
            for (int t = 0; t < 400 && c.logs.get(leader0).commitIndex() < 1; t++) {
                c.step();
            }

            // (2) Isolate the leader + one follower into a 2-node minority; the 3-node
            //     majority keeps quorum and must elect a NEW leader. The old leader must be
            //     shed (CheckQuorum). This is "election completes after a disruption", with a
            //     stable majority (no total-shatter contention).
            Set<Integer> minority = c.isolateLeaderMinority(seed, leader0);
            int reElectTicks = -1;
            for (int t = 1; t <= RECOVER_BOUND; t++) {
                c.step();
                int ldr = c.findLeader();
                if (ldr >= 0 && !minority.contains(ldr)) {
                    reElectTicks = t;
                    break;
                }
            }
            assertTrue(reElectTicks > 0,
                    "seed " + seed + ": LIVENESS VIOLATION — the 3-node majority did not elect a"
                            + " new leader within " + RECOVER_BOUND + " ticks of losing the old one");
            worstReElect = Math.max(worstReElect, reElectTicks);
            // Soak the partition: the majority leader keeps committing writes the minority
            // can't see AND keeps heartbeating the two dropped minority peers for >10
            // heartbeat intervals — so its per-peer inflight window toward each pins at the
            // cap. This is the precondition RR-103 makes permanent; without it the partition
            // is too short to pin the window and the leak never triggers.
            for (int t = 0; t < 700; t++) {
                c.step();
                int ldr = c.findLeader();
                if (ldr >= 0 && !minority.contains(ldr) && t % 50 == 0) {
                    c.nodes.get(ldr).propose(
                            CommandCodec.encodePut("k/maj", ("v" + seed + "-" + t).getBytes()));
                }
            }
            long preHealCommit = c.maxCommitIndex(); // committed only on the majority

            // (3) Heal → the minority rejoins and the WHOLE cluster must return to full
            //     service (a write committed on ALL N nodes) within a bound. This is the
            //     propagation-after-reconnect liveness check — and exactly the path RR-103
            //     broke (the new leader's inflight window toward the rejoining minority).
            c.net.healAll();
            c.net.setDropRate(0.0);
            int convergeTicks = -1;
            for (int t = 1; t <= PROPAGATE_BOUND; t++) {
                c.step();
                int ldr = c.findLeader();
                if (ldr >= 0) {
                    // keep a leader proposing so progress can actually move to everyone
                    c.nodes.get(ldr).propose(CommandCodec.encodePut("k/heal", ("h" + t).getBytes()));
                }
                if (c.allConvergedBeyond(preHealCommit)) {
                    convergeTicks = t;
                    break;
                }
            }
            assertTrue(convergeTicks > 0,
                    "seed " + seed + ": LIVENESS VIOLATION — after heal the cluster did not return"
                            + " to full service (a write committed on ALL " + N + " nodes) within "
                            + PROPAGATE_BOUND + " ticks; the rejoined minority stayed behind. This is"
                            + " the RR-103 failure shape — a healed-but-stuck follower.");
            worstConverge = Math.max(worstConverge, convergeTicks);
            sumConverge += convergeTicks;
        }
        System.out.printf(
                "[liveness-bounded-progress] seeds=%d worstBootstrapElect=%d worstMajorityReElect=%d"
                        + " worstPostHealConverge=%d (mean=%.0f) ticks — 0 liveness violations%n",
                count, worstElect, worstReElect, worstConverge, (double) sumConverge / count);
    }
}
