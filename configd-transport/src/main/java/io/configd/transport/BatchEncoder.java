package io.configd.transport;

import io.configd.common.NodeId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Batches outbound messages per destination with bounded delay.
 * <p>
 * Instead of sending each message individually, messages are accumulated
 * per peer and flushed when either:
 * <ul>
 *   <li>The maximum batch size is reached</li>
 *   <li>The maximum batch delay has elapsed since the first buffered message</li>
 *   <li>{@link #flush(NodeId)} or {@link #flushAll()} is called explicitly</li>
 * </ul>
 * <p>
 * Default delays from ADR-0010:
 * <ul>
 *   <li>Raft AppendEntries: 200μs max delay, 64 entries or 256 KB trigger</li>
 *   <li>Plumtree EagerPush: 100μs max delay, 32 events or 128 KB trigger</li>
 * </ul>
 * <p>
 * Thread safety: designed for single-threaded access from the transport
 * I/O thread. No synchronization is used.
 *
 * @see FrameCodec
 */
public final class BatchEncoder {

    private final int maxBatchSize;
    private final long maxBatchDelayNanos;
    private final Map<NodeId, PeerBatch> batches;

    /**
     * Creates a batch encoder with the given thresholds.
     *
     * @param maxBatchSize       maximum messages per batch before auto-flush
     * @param maxBatchDelayNanos maximum nanoseconds to hold a message before flush
     */
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

    /**
     * Convenience factory for Raft batching (200μs, 64 entries).
     */
    public static BatchEncoder forRaft() {
        return new BatchEncoder(64, 200_000);
    }

    /**
     * Convenience factory for Plumtree batching (100μs, 32 entries).
     */
    public static BatchEncoder forPlumtree() {
        return new BatchEncoder(32, 100_000);
    }

    /**
     * Adds a message to the batch for the given peer.
     *
     * @param peer        the destination node
     * @param message     the message to batch
     * @param currentNanos current monotonic time in nanoseconds
     * @return true if the batch for this peer has reached maxBatchSize and should be flushed
     */
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

    /**
     * Returns the peers that have batches ready for flushing, either because
     * the batch is full or the delay has elapsed.
     *
     * @param currentNanos current monotonic time in nanoseconds
     * @return set of peers with ready batches
     */
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

    /**
     * Flushes the batch for a specific peer, returning all accumulated messages.
     *
     * @param peer the peer to flush
     * @return the list of batched messages (empty if no pending messages)
     */
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

    /**
     * Flushes all peers, returning a map of peer to their batched messages.
     * Only includes peers that had pending messages.
     *
     * @return map of peer to batched messages
     */
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

    /**
     * Returns the number of pending messages for a peer.
     */
    public int pendingCount(NodeId peer) {
        PeerBatch batch = batches.get(peer);
        return (batch != null) ? batch.messages.size() : 0;
    }

    /**
     * Returns the total number of pending messages across all peers.
     */
    public int totalPending() {
        int total = 0;
        for (PeerBatch batch : batches.values()) {
            total += batch.messages.size();
        }
        return total;
    }

    /**
     * Discards all pending messages for all peers.
     */
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
