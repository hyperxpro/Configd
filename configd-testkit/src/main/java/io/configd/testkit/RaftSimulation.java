package io.configd.testkit;

import io.configd.common.Clock;
import io.configd.common.NodeId;

import java.util.*;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * Deterministic simulation harness for Raft consensus testing.
 * Runs a simulated Raft cluster on a single thread with controlled
 * time advancement and network fault injection.
 *
 * Inspired by FoundationDB's deterministic simulation.
 * Uses a seeded PRNG for full reproducibility — same seed = same execution.
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
            // No pending messages — advance by one election timeout
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
