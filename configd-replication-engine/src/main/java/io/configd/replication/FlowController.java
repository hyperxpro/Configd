package io.configd.replication;

import io.configd.common.NodeId;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Credit-based flow control for replication to individual followers.
 * Each follower starts with an initial credit budget. Sending entries
 * to a follower consumes credits; receiving an {@code AppendEntriesResponse}
 * restores them.
 * <p>
 * This prevents a slow follower from causing unbounded memory growth
 * on the leader. When a follower's credits reach zero, it is considered
 * "throttled" and no further entries should be sent until credits are
 * restored via acknowledgment.
 * <p>
 * Designed for single-threaded access from the Raft I/O thread.
 * No synchronization is used. Credit values are tracked in a plain
 * {@link HashMap} - no atomic operations are needed.
 *
 * @see io.configd.raft.RaftNode
 */
public final class FlowController {

    private final int initialCredits;

    private final Map<NodeId, Integer> credits;

    public FlowController(int initialCredits) {
        if (initialCredits <= 0) {
            throw new IllegalArgumentException("initialCredits must be positive: " + initialCredits);
        }
        this.initialCredits = initialCredits;
        this.credits = new HashMap<>();
    }

    public int acquireCredits(NodeId follower, int count) {
        Objects.requireNonNull(follower, "follower");
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive: " + count);
        }
        Integer available = credits.get(follower);
        if (available == null) {
            throw new IllegalStateException("Follower not registered: " + follower);
        }
        int granted = Math.min(available, count);
        credits.put(follower, available - granted);
        return granted;
    }

    /**
     * Restores credits for a follower after receiving an acknowledgment.
     * Credits are capped at the initial credit value to prevent overflow
     * from duplicate or out-of-order acknowledgments.
     *
     * @param follower the follower node whose credits to restore
     * @param count    the number of credits to restore (must be positive)
     * @throws NullPointerException     if {@code follower} is null
     * @throws IllegalArgumentException if {@code count} is not positive
     * @throws IllegalStateException    if the follower has not been added
     */
    public void releaseCredits(NodeId follower, int count) {
        Objects.requireNonNull(follower, "follower");
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive: " + count);
        }
        Integer available = credits.get(follower);
        if (available == null) {
            throw new IllegalStateException("Follower not registered: " + follower);
        }
        credits.put(follower, Math.min(initialCredits, available + count));
    }

    public int availableCredits(NodeId follower) {
        Objects.requireNonNull(follower, "follower");
        Integer available = credits.get(follower);
        if (available == null) {
            throw new IllegalStateException("Follower not registered: " + follower);
        }
        return available;
    }

    public void addFollower(NodeId follower) {
        Objects.requireNonNull(follower, "follower");
        credits.putIfAbsent(follower, initialCredits);
    }

    public void removeFollower(NodeId follower) {
        Objects.requireNonNull(follower, "follower");
        if (credits.remove(follower) == null) {
            throw new IllegalStateException("Follower not registered: " + follower);
        }
    }

    public boolean isThrottled(NodeId follower) {
        return availableCredits(follower) == 0;
    }

    public void resetAll() {
        credits.replaceAll((node, current) -> initialCredits);
    }

    public int initialCredits() {
        return initialCredits;
    }
}
