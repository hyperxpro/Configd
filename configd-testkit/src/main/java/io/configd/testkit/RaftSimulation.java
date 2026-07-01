package io.configd.testkit;

import io.configd.common.Clock;
import io.configd.common.NodeId;

import java.util.*;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Deterministic simulation harness for Raft consensus testing.
 * Runs a simulated Raft cluster on a single thread with controlled
 * time advancement and network fault injection.
 *
 * Inspired by FoundationDB's deterministic simulation.
 * Uses a seeded PRNG for full reproducibility - same seed = same execution.
 */
public final class RaftSimulation {

    private final long seed;
    private final RandomGenerator random;
    private final SimulatedClock clock;
    private final SimulatedNetwork network;
    private final int nodeCount;
    private final List<NodeId> nodeIds;

    // Callbacks for custom assertions after each step
    private final List<Consumer<RaftSimulation>> invariantCheckers = new ArrayList<>();

    // Statistics
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
     * and the node id. Previously the harness constructed
     * the election RNG entropy-seeded ({@code RandomGenerator.of(name)}), so a
     * fixed seed produced divergent election schedules and failing seeds were
     * unreplayable. Threading the seed here makes "same seed = same execution"
     * actually hold for the election RNG too - the master seed is the single
     * source of all simulated randomness.
     * <p>
     * Production seeding is unaffected: this lives in the test simulation
     * harness; the live server keeps its own {@link RandomGenerator}.
     *
     * @param nodeId the node whose election RNG is requested
     * @return a fresh deterministic generator seeded from {@code mix(seed, nodeId)}
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

    /**
     * Register an invariant checker that runs after every simulation step.
     * If the checker throws, the simulation stops with a reproducible failure.
     */
    public void addInvariantChecker(Consumer<RaftSimulation> checker) {
        invariantCheckers.add(checker);
    }

    /**
     * Advance simulation by one tick (1ms).
     * Delivers any pending messages that are due.
     */
    public void tick() {
        clock.advanceMs(1);
        int delivered = network.deliverDue(clock.currentTimeMillis());
        totalMessagesDelivered += delivered;
        totalTicks++;
        checkInvariants();
    }

    /**
     * Advance simulation to the next interesting event (next message delivery or timeout).
     */
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

    /**
     * Run simulation for the given number of ticks.
     */
    public void runTicks(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tick();
        }
    }

    /**
     * Inject a random network partition between two random nodes.
     */
    public void injectRandomPartition() {
        int a = random.nextInt(nodeCount);
        int b = random.nextInt(nodeCount);
        if (a != b) {
            network.isolate(nodeIds.get(a), nodeIds.get(b));
            totalPartitionsInjected++;
        }
    }

    /**
     * Inject a partition isolating the given node from all others.
     */
    public void isolateNode(NodeId node) {
        for (NodeId other : nodeIds) {
            if (!other.equals(node)) {
                network.isolate(node, other);
            }
        }
        totalPartitionsInjected++;
    }

    /** Heal all network partitions. */
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
