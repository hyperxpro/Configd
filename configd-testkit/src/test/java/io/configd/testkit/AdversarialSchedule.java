package io.configd.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Seed-derived, fully replayable fault + workload schedule for the adversarial
 * simulation (adversarial-sim-design §2). Every fault and client op is a pure
 * function of the master seed via the RR-010 {@code mixSeed} pattern, so a run is
 * reproducible by seed alone and a failing seed can be expanded into a concrete,
 * minimizable schedule (§5 ddmin).
 * <p>
 * The schedule is <em>expanded eagerly</em> at construction into an ordered list of
 * {@link Event}s keyed by logical tick. Eager expansion is what makes the schedule
 * a first-class, serializable, shrinkable object (a failing seed → a concrete event
 * list that ddmin can reduce), rather than a hidden side effect of per-tick RNG
 * draws.
 * <p>
 * Stream separation: each fault family draws from its own sub-stream
 * ({@code mixSeed(seed, TAG)} with a family-specific tag distinct from every node
 * id), so adding/removing a family never perturbs another family's draws — a
 * property that keeps historical seeds stable.
 */
final class AdversarialSchedule {

    // Stream tags — fixed constants, all >= 1_000 so they never collide with a
    // node id (node ids are small non-negative ints) used by RaftSimulation's
    // per-node election streams.
    private static final int TAG_FAULT = 1_001;
    private static final int TAG_WORKLOAD = 1_002;

    enum FaultKind {
        /** Begin dropping a fraction of messages cluster-wide for a window. */
        DROP_WINDOW_BEGIN,
        /** End the active drop window (restore zero drop). */
        DROP_WINDOW_END,
        /** Add a (possibly asymmetric / partial) partition between two nodes. */
        PARTITION_ADD,
        /** Remove a specific partition. */
        PARTITION_REMOVE,
        /** Heal all partitions (first-class scheduled event). */
        HEAL_ALL,
        /** Begin a delay-spike window on a chosen ordered pair. */
        DELAY_SPIKE_BEGIN,
        /** End the active delay-spike window. */
        DELAY_SPIKE_END,
        /** Arm a crash on a node after N of its storage writes (CrashStorage). */
        CRASH_ARM
    }

    enum OpKind { PUT, DELETE, READ }

    /** A scheduled fault at a logical tick. {@code a}/{@code b} are node ids or -1. */
    record Event(int tick, FaultKind kind, int a, int b, double param, int intParam) {}

    /** A scheduled client operation at a logical tick. */
    record Op(int tick, OpKind kind, int clientId, int opSeq, String key, String value) {}

    private final long seed;
    private final int nodeCount;
    private final int totalTicks;
    private final List<Event> events;
    private final List<Op> ops;

    AdversarialSchedule(long seed, int nodeCount, int totalTicks) {
        this(seed, nodeCount, totalTicks, defaultIntensity());
    }

    AdversarialSchedule(long seed, int nodeCount, int totalTicks, Intensity intensity) {
        this.seed = seed;
        this.nodeCount = nodeCount;
        this.totalTicks = totalTicks;
        this.events = expandFaults(intensity);
        this.ops = expandWorkload(intensity);
    }

    /** Construct directly from explicit events/ops — used by ddmin minimization. */
    private AdversarialSchedule(long seed, int nodeCount, int totalTicks,
            List<Event> events, List<Op> ops) {
        this.seed = seed;
        this.nodeCount = nodeCount;
        this.totalTicks = totalTicks;
        this.events = List.copyOf(events);
        this.ops = List.copyOf(ops);
    }

    /** Knobs controlling fault/op density — defaults give a busy adversarial run. */
    record Intensity(int faultCount, int opCount, double minPartitionFraction) {}

    static Intensity defaultIntensity() {
        return new Intensity(8, 40, 0.0);
    }

    long seed() {
        return seed;
    }

    int totalTicks() {
        return totalTicks;
    }

    List<Event> events() {
        return events;
    }

    List<Op> ops() {
        return ops;
    }

    private RandomGenerator stream(int tag) {
        return RandomGeneratorFactory.of("L64X128MixRandom").create(mixSeed(seed, tag));
    }

    private List<Event> expandFaults(Intensity intensity) {
        RandomGenerator rng = stream(TAG_FAULT);
        List<Event> out = new ArrayList<>();
        // Spread faults across the middle 80% of the run so the cluster has time to
        // form a leader first and settle at the end.
        int lo = totalTicks / 10;
        int hi = totalTicks - totalTicks / 10;
        for (int i = 0; i < intensity.faultCount(); i++) {
            int tick = lo + rng.nextInt(Math.max(1, hi - lo));
            FaultKind kind = pickFaultKind(rng);
            int a = rng.nextInt(nodeCount);
            int b = rng.nextInt(nodeCount);
            double param = rng.nextDouble();
            int intParam = 1 + rng.nextInt(8);
            // Avoid self-pairs for partition events.
            if ((kind == FaultKind.PARTITION_ADD || kind == FaultKind.PARTITION_REMOVE)
                    && a == b) {
                b = (b + 1) % nodeCount;
            }
            out.add(new Event(tick, kind, a, b, param, intParam));
        }
        out.sort((x, y) -> Integer.compare(x.tick(), y.tick()));
        return out;
    }

    private FaultKind pickFaultKind(RandomGenerator rng) {
        // Weighted toward partitions and drops (the highest-signal Raft faults);
        // crash arming is rarer.
        int r = rng.nextInt(100);
        if (r < 30) return FaultKind.PARTITION_ADD;
        if (r < 45) return FaultKind.PARTITION_REMOVE;
        if (r < 55) return FaultKind.HEAL_ALL;
        if (r < 70) return FaultKind.DROP_WINDOW_BEGIN;
        if (r < 80) return FaultKind.DROP_WINDOW_END;
        if (r < 88) return FaultKind.DELAY_SPIKE_BEGIN;
        if (r < 95) return FaultKind.DELAY_SPIKE_END;
        return FaultKind.CRASH_ARM;
    }

    private List<Op> expandWorkload(Intensity intensity) {
        RandomGenerator rng = stream(TAG_WORKLOAD);
        List<Op> out = new ArrayList<>();
        String[] keys = {"k0", "k1", "k2", "k3"};
        int lo = totalTicks / 10;
        int hi = totalTicks - totalTicks / 20;
        for (int i = 0; i < intensity.opCount(); i++) {
            int tick = lo + rng.nextInt(Math.max(1, hi - lo));
            int r = rng.nextInt(100);
            OpKind kind = r < 60 ? OpKind.PUT : (r < 80 ? OpKind.READ : OpKind.DELETE);
            int clientId = rng.nextInt(4);
            String key = keys[rng.nextInt(keys.length)];
            String value = "v" + i + "-" + Integer.toHexString(rng.nextInt());
            out.add(new Op(tick, kind, clientId, i, key, value));
        }
        out.sort((x, y) -> Integer.compare(x.tick(), y.tick()));
        return out;
    }

    /** Returns a copy with the given event/op lists (used by ddmin shrinking). */
    AdversarialSchedule withEventsAndOps(List<Event> newEvents, List<Op> newOps, int newTicks) {
        return new AdversarialSchedule(seed, nodeCount, newTicks, newEvents, newOps);
    }

    /**
     * SplitMix64 finalizer — identical to {@code RaftSimulation.mixSeed} (RR-010),
     * duplicated here because that method is private. Keeping the two in lockstep
     * is intentional: both derive independent streams from one master seed.
     */
    static long mixSeed(long seed, int tag) {
        long z = seed + 0x9E3779B97F4A7C15L * (tag + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
