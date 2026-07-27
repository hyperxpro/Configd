package io.configd.testkit;

import io.configd.common.Clock;
import io.configd.common.NodeId;

import java.util.*;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

public final class RaftSimulation {

    private final long seed;
    private final RandomGenerator random;
    private final SimulatedClock clock;
    private final SimulatedNetwork network;
    private final int nodeCount;
    private final List<NodeId> nodeIds;

    private final List<Consumer<RaftSimulation>> invariantCheckers = new ArrayList<>();

    private long totalTicks;
    private long totalMessagesDelivered;
    private long totalPartitionsInjected;

    public RaftSimulation(long seed, int nodeCount) {
        this.seed = seed;
        this.random = new java.util.Random(seed);
        this.clock = new SimulatedClock();
        this.network = new SimulatedNetwork(seed, 1, 10);
        this.nodeCount = nodeCount;
        this.nodeIds = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            nodeIds.add(NodeId.of(i));
        }
    }

    public SimulatedClock clock() { return clock; }
    public SimulatedNetwork network() { return network; }
    public List<NodeId> nodeIds() { return Collections.unmodifiableList(nodeIds); }
    public long seed() { return seed; }

    /**
         * Returns a deterministic per-node {@link RandomGenerator} for driving a
         * node's election timeout, seeded purely from the master simulation seed
         * and the node id. This keeps "same seed = same execution" holding for the
         * election RNG too: an entropy-seeded generator (e.g. {@code RandomGenerator.of(name)})
         * would produce divergent election schedules on every run even with a fixed
         * simulation seed, making a failing seed unreplayable. The master seed is the
         * single source of all simulated randomness.
         * <p>
         * Production seeding is unaffected: this lives in the test simulation
         * harness; the live server keeps its own {@link RandomGenerator}.
         */
    public RandomGenerator electionRandom(NodeId nodeId) {
        long nodeSeed = mixSeed(seed, nodeId.id());
        return RandomGeneratorFactory.of("L64X128MixRandom").create(nodeSeed);
    }

    /**
     * SplitMix64 finalizer applied to a seed combined with the node id. This
     * decorrelates per-node streams so two nodes never share an election-timeout
     * sequence, while remaining a pure deterministic function of (seed, nodeId).
     */
    private static long mixSeed(long seed, int nodeId) {
        long z = seed + 0x9E3779B97F4A7C15L * (nodeId + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public void addInvariantChecker(Consumer<RaftSimulation> checker) {
        invariantCheckers.add(checker);
    }

    public void tick() {
        clock.advanceMs(1);
        int delivered = network.deliverDue(clock.currentTimeMillis());
        totalMessagesDelivered += delivered;
        totalTicks++;
        checkInvariants();
    }

    public void advanceToNextEvent() {
        long nextDelivery = network.nextDeliveryTime();
        if (nextDelivery == Long.MAX_VALUE) {
            // No pending messages - advance by one election timeout
            clock.advanceMs(300);
        } else {
            clock.setTimeMs(nextDelivery);
        }
        int delivered = network.deliverDue(clock.currentTimeMillis());
        totalMessagesDelivered += delivered;
        totalTicks++;
        checkInvariants();
    }

    public void runTicks(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tick();
        }
    }

    public void injectRandomPartition() {
        int a = random.nextInt(nodeCount);
        int b = random.nextInt(nodeCount);
        if (a != b) {
            network.isolate(nodeIds.get(a), nodeIds.get(b));
            totalPartitionsInjected++;
        }
    }

    public void isolateNode(NodeId node) {
        for (NodeId other : nodeIds) {
            if (!other.equals(node)) {
                network.isolate(node, other);
            }
        }
        totalPartitionsInjected++;
    }

    public void healAllPartitions() {
        network.healAll();
    }

    private void checkInvariants() {
        for (var checker : invariantCheckers) {
            checker.accept(this);
        }
    }

    public String stats() {
        return String.format("Simulation[seed=%d, ticks=%d, msgs=%d, partitions=%d]",
                seed, totalTicks, totalMessagesDelivered, totalPartitionsInjected);
    }
}
