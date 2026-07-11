package io.configd.testkit;

import io.configd.common.NodeId;

import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Deterministic simulated network with the full adversarial fault set: seeded latency, drop
 * windows, message duplication, delay spikes, message reorder, and uni/bi-directional partitions.
 * <p>
 * It is a standalone network (not a subclass of the {@code final}
 * {@link SimulatedNetwork}) so the adversarial harness owns every source of
 * non-determinism. All randomness comes from a single seed-derived stream
 * ({@code mixSeed(seed, TAG_NET)}), so a run is byte-replayable by seed alone -
 * the determinism invariant must continue to hold with all faults active.
 * <p>
 * <b>Determinism note.</b> Delivery is a {@link PriorityQueue} ordered first by
 * {@code deliverAtMs} and then by a strictly-increasing {@code seq} tie-break
 * field. The tie-break makes same-tick ordering explicit and stable instead of
 * depending on heap insertion order (a hidden determinism dependency the original
 * {@code SimulatedNetwork} had). Reorder is produced by widening the latency band,
 * not by perturbing the tie-break, so determinism is preserved.
 * <p>
 * Not thread-safe; the simulation is single-threaded.
 */
final class AdversarialNetwork {

    private static final int TAG_NET = 2_001;

    /** A queued message; {@code seq} gives a deterministic same-tick tie-break. */
    record Pending(long deliverAtMs, long seq, NodeId from, NodeId to, Object message)
            implements Comparable<Pending> {
        @Override
        public int compareTo(Pending o) {
            int c = Long.compare(deliverAtMs, o.deliverAtMs);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }

    private final PriorityQueue<Pending> queue = new PriorityQueue<>();
    private final Set<Long> partitions = new HashSet<>(); // (from<<32)|to
    private final RandomGenerator rng;
    private final int minLatencyMs;
    private final int maxLatencyMs;

    private long seqCounter;
    private double dropRate;

    // Delay-spike window state: while active, traffic on (spikeFrom->spikeTo) gets
    // a large added latency. -1 = inactive (applies to any pair when from==-1).
    private boolean spikeActive;
    private int spikeFrom = -1;
    private int spikeTo = -1;
    private int spikeExtraMs;

    // Duplication probability (seed-derived per run); a duplicated message is
    // re-enqueued once at deliverAt + a small delay. Raft RPCs are idempotent, so
    // a dup that changes state-machine output is a RED caught by SimInvariants.
    private double dupRate;

    private BiConsumer<NodeId, Object> deliveryHandler;

    AdversarialNetwork(long seed, int minLatencyMs, int maxLatencyMs) {
        this.rng = RandomGeneratorFactory.of("L64X128MixRandom")
                .create(AdversarialSchedule.mixSeed(seed, TAG_NET));
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
    }

    void setDeliveryHandler(BiConsumer<NodeId, Object> handler) {
        this.deliveryHandler = handler;
    }

    void setDropRate(double rate) {
        this.dropRate = rate;
    }

    void setDupRate(double rate) {
        this.dupRate = rate;
    }

    void beginDelaySpike(int from, int to, int extraMs) {
        this.spikeActive = true;
        this.spikeFrom = from;
        this.spikeTo = to;
        this.spikeExtraMs = extraMs;
    }

    void endDelaySpike() {
        this.spikeActive = false;
    }

    void addPartition(NodeId from, NodeId to) {
        partitions.add(encode(from, to));
    }

    void removePartition(NodeId from, NodeId to) {
        partitions.remove(encode(from, to));
    }

    void isolate(NodeId a, NodeId b) {
        addPartition(a, b);
        addPartition(b, a);
    }

    void healAll() {
        partitions.clear();
    }

    /** Current base drop rate (diagnosis seam - read the end-of-run network state). */
    double dropRateForTest() {
        return dropRate;
    }

    /** Number of active directed partition edges (diagnosis seam). */
    int activePartitionsForTest() {
        return partitions.size();
    }

    /** Send with simulated latency, possible drop, delay-spike, and duplication. */
    void send(NodeId from, NodeId to, Object message, long nowMs) {
        if (partitions.contains(encode(from, to))) {
            return;
        }
        if (rng.nextDouble() < dropRate) {
            return;
        }
        int latency = minLatencyMs + rng.nextInt(maxLatencyMs - minLatencyMs + 1);
        if (spikeActive && (spikeFrom == -1
                || (spikeFrom == from.id() && spikeTo == to.id()))) {
            latency += spikeExtraMs;
        }
        enqueue(from, to, message, nowMs + latency);

        // Duplication: re-enqueue the same message a little later.
        if (rng.nextDouble() < dupRate) {
            int dupDelay = 1 + rng.nextInt(maxLatencyMs);
            enqueue(from, to, message, nowMs + latency + dupDelay);
            dupCount++;
        }
    }

    /**
     * Lifetime count of duplicated sends (diagnostic; proves the duplication path actually
     * fires). Counting only - never consumes an RNG draw, so digests are untouched.
     */
    long dupCount() {
        return dupCount;
    }

    private long dupCount;

    private void enqueue(NodeId from, NodeId to, Object message, long deliverAt) {
        queue.add(new Pending(deliverAt, seqCounter++, from, to, message));
    }

    /** Deliver all messages due at or before {@code nowMs}; returns count delivered. */
    int deliverDue(long nowMs) {
        int count = 0;
        while (!queue.isEmpty() && queue.peek().deliverAtMs() <= nowMs) {
            Pending msg = queue.poll();
            // Re-check the partition at delivery time so a partition added after
            // send still drops in-flight traffic (and a heal lets it through).
            if (!partitions.contains(encode(msg.from(), msg.to()))) {
                if (deliveryHandler != null) {
                    deliveryHandler.accept(msg.to(), msg.message());
                }
                count++;
            }
        }
        return count;
    }

    int pendingCount() {
        return queue.size();
    }

    private static long encode(NodeId from, NodeId to) {
        return (long) from.id() << 32 | (to.id() & 0xFFFFFFFFL);
    }
}
