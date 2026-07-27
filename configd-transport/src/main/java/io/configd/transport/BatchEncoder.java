package io.configd.transport;

import io.configd.common.NodeId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Thread safety: designed for single-threaded access from the transport
 * I/O thread. No synchronization is used.
 */
public final class BatchEncoder {

    private final int maxBatchSize;
    private final long maxBatchDelayNanos;
    private final Map<NodeId, PeerBatch> batches;

    public BatchEncoder(int maxBatchSize, long maxBatchDelayNanos) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive: " + maxBatchSize);
        }
        if (maxBatchDelayNanos <= 0) {
            throw new IllegalArgumentException("maxBatchDelayNanos must be positive: " + maxBatchDelayNanos);
        }
        this.maxBatchSize = maxBatchSize;
        this.maxBatchDelayNanos = maxBatchDelayNanos;
        this.batches = new HashMap<>();
    }

    public static BatchEncoder forRaft() {
        return new BatchEncoder(64, 200_000);
    }

    public static BatchEncoder forPlumtree() {
        return new BatchEncoder(32, 100_000);
    }

    public boolean offer(NodeId peer, Object message, long currentNanos) {
        Objects.requireNonNull(peer, "peer must not be null");
        Objects.requireNonNull(message, "message must not be null");

        PeerBatch batch = batches.computeIfAbsent(peer, k -> new PeerBatch());
        if (batch.messages.isEmpty()) {
            batch.firstMessageNanos = currentNanos;
        }
        batch.messages.add(message);

        return batch.messages.size() >= maxBatchSize;
    }

    public Set<NodeId> readyPeers(long currentNanos) {
        var ready = new java.util.HashSet<NodeId>();
        for (var entry : batches.entrySet()) {
            PeerBatch batch = entry.getValue();
            if (!batch.messages.isEmpty()) {
                if (batch.messages.size() >= maxBatchSize
                        || (currentNanos - batch.firstMessageNanos) >= maxBatchDelayNanos) {
                    ready.add(entry.getKey());
                }
            }
        }
        return ready;
    }

    public List<Object> flush(NodeId peer) {
        PeerBatch batch = batches.get(peer);
        if (batch == null || batch.messages.isEmpty()) {
            return List.of();
        }
        List<Object> result = List.copyOf(batch.messages);
        batch.messages.clear();
        batch.firstMessageNanos = 0;
        return result;
    }

    public Map<NodeId, List<Object>> flushAll() {
        Map<NodeId, List<Object>> result = new HashMap<>();
        for (var entry : batches.entrySet()) {
            PeerBatch batch = entry.getValue();
            if (!batch.messages.isEmpty()) {
                result.put(entry.getKey(), List.copyOf(batch.messages));
                batch.messages.clear();
                batch.firstMessageNanos = 0;
            }
        }
        return result;
    }

    public int pendingCount(NodeId peer) {
        PeerBatch batch = batches.get(peer);
        return (batch != null) ? batch.messages.size() : 0;
    }

    public int totalPending() {
        int total = 0;
        for (PeerBatch batch : batches.values()) {
            total += batch.messages.size();
        }
        return total;
    }

    public void reset() {
        for (PeerBatch batch : batches.values()) {
            batch.messages.clear();
            batch.firstMessageNanos = 0;
        }
    }

    private static final class PeerBatch {
        final List<Object> messages = new ArrayList<>();
        long firstMessageNanos;
    }
}
