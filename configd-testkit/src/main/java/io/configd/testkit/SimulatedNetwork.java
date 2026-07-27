package io.configd.testkit;

import io.configd.common.NodeId;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.random.RandomGenerator;

public final class SimulatedNetwork {

    public record PendingMessage(long deliverAtMs, NodeId from, NodeId to, Object message) 
            implements Comparable<PendingMessage> {
        @Override
        public int compareTo(PendingMessage other) {
            return Long.compare(this.deliverAtMs, other.deliverAtMs);
        }
    }

    private final PriorityQueue<PendingMessage> pendingMessages = new PriorityQueue<>();
    private final Set<Long> partitions = new HashSet<>();
    private final RandomGenerator random;
    private final int minLatencyMs;
    private final int maxLatencyMs;
    private double dropRate;
    private BiConsumer<NodeId, Object> deliveryHandler;

    public SimulatedNetwork(long seed, int minLatencyMs, int maxLatencyMs) {
        this.random = new java.util.Random(seed);
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
        this.dropRate = 0.0;
    }

    public SimulatedNetwork(long seed) {
        this(seed, 1, 10);
    }

    public void setDeliveryHandler(BiConsumer<NodeId, Object> handler) {
        this.deliveryHandler = handler;
    }

    public void setDropRate(double rate) {
        this.dropRate = rate;
    }

    public void addPartition(NodeId from, NodeId to) {
        partitions.add(encodePartition(from, to));
    }

    public void removePartition(NodeId from, NodeId to) {
        partitions.remove(encodePartition(from, to));
    }

    public void isolate(NodeId a, NodeId b) {
        addPartition(a, b);
        addPartition(b, a);
    }

    public void healAll() {
        partitions.clear();
    }

    public void send(NodeId from, NodeId to, Object message, long currentTimeMs) {
        if (partitions.contains(encodePartition(from, to))) return;
        if (random.nextDouble() < dropRate) return;

        int latency = minLatencyMs + random.nextInt(maxLatencyMs - minLatencyMs + 1);
        pendingMessages.add(new PendingMessage(currentTimeMs + latency, from, to, message));
    }

    public int deliverDue(long currentTimeMs) {
        int count = 0;
        while (!pendingMessages.isEmpty() && pendingMessages.peek().deliverAtMs <= currentTimeMs) {
            PendingMessage msg = pendingMessages.poll();
            if (!partitions.contains(encodePartition(msg.from, msg.to))) {
                if (deliveryHandler != null) {
                    deliveryHandler.accept(msg.to, msg.message);
                }
                count++;
            }
        }
        return count;
    }

    public int pendingCount() {
        return pendingMessages.size();
    }

    public boolean hasPending() {
        return !pendingMessages.isEmpty();
    }

    public long nextDeliveryTime() {
        return pendingMessages.isEmpty() ? Long.MAX_VALUE : pendingMessages.peek().deliverAtMs;
    }

    private static long encodePartition(NodeId from, NodeId to) {
        return (long) from.id() << 32 | (to.id() & 0xFFFFFFFFL);
    }
}
