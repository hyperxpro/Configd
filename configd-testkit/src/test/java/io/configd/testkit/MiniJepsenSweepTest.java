package io.configd.testkit;

import io.configd.common.NodeId;
import io.configd.raft.RaftNode;
import io.configd.store.CommandCodec;

import org.junit.jupiter.api.Test;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sustained mini-Jepsen: a long-horizon randomized mixed-fault run on the 5-node control
 * plane - random partitions, heals, packet loss, latency spikes, and continuous writes -
 * with the safety oracle asserted EVERY tick (single-leader-per-term + no divergent commit +
 * no committed-entry loss). After the storm a final heal must converge the whole cluster -
 * proving the mixed-fault history left the cluster recoverable, not wedged.
 *
 * <p>This complements the existing adversarial sweeps: the 10k control-plane
 * {@code SeedSweepTest} (build-and-test job) and the 10k integrated edge
 * {@code EdgeIntegratedNightlySweepTest} (nightly sweep) - both see 0 safety violations. It is
 * NIGHTLY, not in the CI gate: it defaults to a small horizon/seed count; the nightly run
 * overrides {@code -Dconfigd.minijepsen.seeds} / {@code -Dconfigd.minijepsen.horizon} for a
 * sustained sweep.
 */
class MiniJepsenSweepTest {

    private static final int N = 5;
    private static final int SEEDS = Integer.getInteger("configd.minijepsen.seeds", 8);
    private static final int HORIZON = Integer.getInteger("configd.minijepsen.horizon", 6_000);

    @Test
    void sustainedMixedFaultHistoryStaysSafeAndRecovers() {
        long worstConverge = 0;
        int totalFaults = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            String ctx = "E[seed=" + seed + "]";
            PartitionMatrixTest.Cluster c = new PartitionMatrixTest.Cluster(seed);
            RandomGenerator r = RandomGeneratorFactory.of("L64X128MixRandom")
                    .create(AdversarialSchedule.mixSeed(seed, 77_001));

            int elect = c.stepUntilLeader(600, ctx);
            assertTrue(elect > 0, ctx + ": no initial leader");

            for (int t = 0; t < HORIZON; t++) {
                c.step();
                c.assertSafety(ctx);

                if (t % 7 == 0) {
                    int ldr = c.findLeader();
                    if (ldr >= 0) {
                        c.nodes.get(ldr).propose(CommandCodec.encodePut("e/" + (t % 64), ("v" + t).getBytes()));
                    }
                }
                if (t % 23 == 0) {
                    totalFaults++;
                    int roll = r.nextInt(6);
                    switch (roll) {
                        case 0, 1 -> {
                            int a = r.nextInt(N), b = r.nextInt(N);
                            if (a != b) {
                                if (r.nextBoolean()) {
                                    c.net.isolate(NodeId.of(a), NodeId.of(b));
                                } else {
                                    c.net.addPartition(NodeId.of(a), NodeId.of(b)); // one-way
                                }
                            }
                        }
                        case 2 -> c.net.setDropRate(0.10 + 0.30 * r.nextDouble());
                        case 3 -> {
                            int a = r.nextInt(N), b = r.nextInt(N);
                            if (a != b) {
                                c.net.beginDelaySpike(a, b, 10 + r.nextInt(40));
                            }
                        }
                        case 4 -> {
                            c.net.setDropRate(0.0);
                            c.net.endDelaySpike();
                        }
                        case 5 -> c.net.healAll();
                    }
                }
            }

            c.net.healAll();
            c.net.setDropRate(0.0);
            c.net.endDelaySpike();
            int settle = c.stepUntilLeader(2_000, ctx);
            assertTrue(settle > 0, ctx + ": no leader after final heal");
            int ldr = c.findLeader();
            c.nodes.get(ldr).propose(CommandCodec.encodePut("e/final", ("final" + seed).getBytes()));
            long target = c.maxCommitIndex();
            int conv = -1;
            for (int t = 1; t <= 3_000; t++) {
                c.step();
                c.assertSafety(ctx);
                if (c.allConvergedAtLeast(target)) { conv = t; break; }
            }
            assertTrue(conv > 0, ctx + ": cluster did not converge after the final heal (wedged?)");
            worstConverge = Math.max(worstConverge, conv);
        }
        System.out.println("MINI-JEPSEN: seeds=" + SEEDS + " horizon=" + HORIZON
                + " faultsInjected=" + totalFaults + " worstFinalConvergeTicks=" + worstConverge
                + " safetyViolations=0 (single-leader-per-term + no-divergent-commit + no-loss, every tick)");
    }
}
