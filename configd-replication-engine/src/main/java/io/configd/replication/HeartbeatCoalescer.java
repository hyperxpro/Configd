package io.configd.replication;

import io.configd.common.NodeId;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Coalesces heartbeats across multiple Raft groups sharing the same
 * peer node. Instead of each group sending its own heartbeat, the
 * coalescer batches heartbeat "intents" and sends one network message
 * per peer per tick.
 * <p>
 * This is the CockroachDB pattern for reducing heartbeat amplification
 * with many Raft groups (ADR-0010). Without coalescing, N Raft groups
 * each send heartbeats to every peer independently, resulting in
 * O(N * peers) messages per heartbeat interval. With coalescing, the
 * driver records heartbeat intents and drains them into a single
 * batched message per peer, reducing network overhead to O(peers).
 * <p>
 * Usage: During each tick, the driver calls
 * {@link #recordHeartbeat(NodeId, int)} for each group that needs a
 * heartbeat. After the tick, it calls {@link #drainAll()} to collect
 * all pending intents grouped by peer, then sends one coalesced
 * message per peer.
 * <p>
 * Designed for single-threaded access from the Raft I/O thread.
 * No synchronization is used.
 */
public final class HeartbeatCoalescer {

    private final long coalescingWindowNanos;

    /**
     * Maps each peer to the set of group IDs that have pending heartbeats.
     * Cleared on drain.
     */
    private final Map<NodeId, Set<Integer>> pending;

    /**
     * Nanotime when the first heartbeat intent was recorded in the
     * current window. Reset to {@code -1} when drained.
     */
    private long windowStartNanos;

    /**
     * Creates a new HeartbeatCoalescer.
     *
     * @param coalescingWindowNanos the maximum nanoseconds to wait before
     *                              flushing coalesced heartbeats
     * @throws IllegalArgumentException if {@code coalescingWindowNanos} is not positive
     */
    public HeartbeatCoalescer(long coalescingWindowNanos) {
        if (coalescingWindowNanos <= 0) {
            throw new IllegalArgumentException(
                    "coalescingWindowNanos must be positive: " + coalescingWindowNanos);
        }
        this.coalescingWindowNanos = coalescingWindowNanos;
        this.pending = new HashMap<>();
        this.windowStartNanos = -1;
    }

    /**
     * Records a heartbeat intent for the given peer and Raft group.
     * <p>
     * If this is the first intent in the current coalescing window,
     * the window timer starts. Duplicate intents (same peer + group)
     * are deduplicated automatically.
     *
     * @param peer    the target peer node
     * @param groupId the Raft group that needs a heartbeat to this peer
     * @throws NullPointerException if {@code peer} is null
     */
    public void recordHeartbeat(NodeId peer, int groupId) {
        Objects.requireNonNull(peer, "peer");
        pending.computeIfAbsent(peer, k -> new HashSet<>()).add(groupId);
    }

    /**
     * Returns the set of peers that have at least one pending heartbeat intent.
     *
     * @return an unmodifiable set of peer node IDs; never null
     */
    public Set<NodeId> pendingPeers() {
        return Collections.unmodifiableSet(pending.keySet());
    }

    /**
     * Drains all pending heartbeat intents for a specific peer, returning
     * the group IDs that need heartbeats. After this call, the peer has
     * no pending intents.
     * <p>
     * If the peer has no pending intents, returns an empty set.
     *
     * @param peer the target peer node
     * @return an unmodifiable set of group IDs; never null
     * @throws NullPointerException if {@code peer} is null
     */
    public Set<Integer> drain(NodeId peer) {
        Objects.requireNonNull(peer, "peer");
        Set<Integer> groups = pending.remove(peer);
        if (groups == null) {
            return Collections.emptySet();
        }
        // Reset window if no more pending intents
        if (pending.isEmpty()) {
            windowStartNanos = -1;
        }
        return Collections.unmodifiableSet(groups);
    }

    /**
     * Drains all pending heartbeat intents for all peers. Returns a map
     * from peer node ID to the set of group IDs needing heartbeats.
     * After this call, all pending state is cleared.
     *
     * @return an unmodifiable map of peer to group IDs; never null
     */
    public Map<NodeId, Set<Integer>> drainAll() {
        if (pending.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<NodeId, Set<Integer>> result = new HashMap<>();
        for (var entry : pending.entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
        }
        pending.clear();
        windowStartNanos = -1;
        return Collections.unmodifiableMap(result);
    }

    /**
     * Checks whether the coalescing window has expired and pending
     * heartbeats should be flushed.
     * <p>
     * If there are pending intents and no window start has been recorded,
     * the window starts at the provided {@code currentNanos}.
     *
     * @param currentNanos the current monotonic nanotime
     * @return {@code true} if the window has expired and there are pending intents
     */
    public boolean shouldFlush(long currentNanos) {
        if (pending.isEmpty()) {
            return false;
        }
        if (windowStartNanos == -1) {
            windowStartNanos = currentNanos;
        }
        return (currentNanos - windowStartNanos) >= coalescingWindowNanos;
    }

    /**
     * Discards all pending heartbeat intents and resets the coalescing window.
     */
    public void reset() {
        pending.clear();
        windowStartNanos = -1;
    }
}
