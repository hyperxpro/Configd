package io.configd.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Seed-derived, fully replayable edge-fault schedule for {@link EdgeFanOutSim}
 * - the edge-plane analogue of {@link AdversarialSchedule}, kept
 * <b>entirely separate</b> from it. It draws from its <em>own</em>
 * {@code mixSeed} sub-stream ({@link #TAG_EDGE_FAULT}) so it never perturbs the CP
 * schedule's fault/workload streams: every existing CP seed (the 507-seed gate,
 * {@code SeedSweepTest}) stays byte-identical.
 *
 * <h2>mixSeed tag registry</h2>
 * All edge tags are {@code >= 1_010} and collide with no existing tag (CP tags:
 * 1_001 fault, 1_002 workload, 2_001 net, 3_001 skew, 3_002 netcfg; per-node
 * election streams use the small node id). They live in {@link EdgeFanOutSim} /
 * here:
 * <ul>
 *   <li>{@code 1_010} - CP->edge network seed ({@code EdgeFanOutSim.TAG_EDGE_NET})</li>
 *   <li>{@code 1_011} - edge network config: dup/drop base rates
 *       ({@code EdgeFanOutSim.TAG_EDGE_NETCFG})</li>
 *   <li>{@code 1_012} - this edge-fault schedule ({@link #TAG_EDGE_FAULT})</li>
 * </ul>
 *
 * <p>Edge fault families (do NOT reuse {@link AdversarialSchedule.FaultKind} -
 * the CP grammar is untouched): edge partition add/remove (CP<->edge channel),
 * edge crash + restart, and lag begin/end.
 */
final class EdgeFaultSchedule {

    /** Edge-fault sub-stream tag (distinct from all CP tags). */
    static final int TAG_EDGE_FAULT = 1_012;

    enum EdgeFaultKind {
        /** Partition the CP->edge channel for one edge (it stops receiving). */
        EDGE_PARTITION_ADD,
        /** Heal a previously-partitioned CP->edge channel. */
        EDGE_PARTITION_REMOVE,
        /** Crash an edge (drop all cache state, bump incarnation). */
        EDGE_CRASH,
        /** Restart a crashed edge (fresh empty store, awaiting bootstrap). */
        EDGE_RESTART,
        /** Begin lagging: the edge stops draining its inbox (keeps queueing). */
        EDGE_LAG_BEGIN,
        /** End lagging: the edge resumes draining. */
        EDGE_LAG_END
    }

    /** A scheduled edge fault at a logical tick targeting edge index {@code edgeIndex}. */
    record EdgeFault(int tick, EdgeFaultKind kind, int edgeIndex) {}

    private final List<EdgeFault> faults;

    EdgeFaultSchedule(long seed, int edgeCount, int totalTicks, int faultCount) {
        this.faults = expand(seed, edgeCount, totalTicks, faultCount);
    }

    List<EdgeFault> faults() {
        return faults;
    }

    private static List<EdgeFault> expand(long seed, int edgeCount, int totalTicks, int faultCount) {
        List<EdgeFault> out = new ArrayList<>();
        if (edgeCount == 0 || faultCount == 0) {
            return out;
        }
        RandomGenerator rng = RandomGeneratorFactory.of("L64X128MixRandom")
                .create(AdversarialSchedule.mixSeed(seed, TAG_EDGE_FAULT));
        // Spread edge faults across the middle 80% so edges have a chance to be
        // bootstrapped (once C1 exists) before being faulted, and to converge after.
        int lo = totalTicks / 10;
        int hi = totalTicks - totalTicks / 10;
        for (int i = 0; i < faultCount; i++) {
            int tick = lo + rng.nextInt(Math.max(1, hi - lo));
            EdgeFaultKind kind = pickKind(rng);
            int edgeIndex = rng.nextInt(edgeCount);
            out.add(new EdgeFault(tick, kind, edgeIndex));
        }
        out.sort((x, y) -> Integer.compare(x.tick(), y.tick()));
        return out;
    }

    private static EdgeFaultKind pickKind(RandomGenerator rng) {
        int r = rng.nextInt(100);
        if (r < 25) return EdgeFaultKind.EDGE_PARTITION_ADD;
        if (r < 45) return EdgeFaultKind.EDGE_PARTITION_REMOVE;
        if (r < 60) return EdgeFaultKind.EDGE_CRASH;
        if (r < 75) return EdgeFaultKind.EDGE_RESTART;
        if (r < 88) return EdgeFaultKind.EDGE_LAG_BEGIN;
        return EdgeFaultKind.EDGE_LAG_END;
    }
}
