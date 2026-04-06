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
 * {@link HashMap} — no atomic operations are needed.
 *
 * @see io.configd.raft.RaftNode
 */
public final class FlowController {

    private final int initialCredits;

    /**
     * Maps each follower to its current available credit count.
     * A follower not present in this map has not been registered.
     */
    private final Map<NodeId, Integer> credits;

    /**
     * Creates a new FlowController.
     *
     * @param initialCredits the number of credits each follower starts with;
     *                       also the maximum credits a follower can hold
     * @throws IllegalArgumentException if {@code initialCredits} is not positive
     */
    public FlowController(int initialCredits) {
        if (initialCredits <= 0) {
            throw new IllegalArgumentException("initialCredits must be positive: " + initialCredits);
        }
        this.initialCredits = initialCredits;
        this.credits = new HashMap<>();
    }

    /**
     * Attempts to acquire credits for sending entries to a follower.
     * <p>
     * The actual number of credits granted may be less than requested
     * if the follower does not have enough available. Returns zero if
     * the follower is fully throttled.
     *
     * @param follower the target follower node
     * @param count    the number of credits requested (must be positive)
     * @return the actual number of credits granted (0 to {@code count})
     * @throws NullPointerException     if {@code follower} is null
     * @throws IllegalArgumentException if {@code count} is not positive
     * @throws IllegalStateException    if the follower has not been added
     */
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

    /**
     * Returns the number of credits currently available for the given follower.
     *
     * @param follower the follower node to query
     * @return the available credit count
     * @throws NullPointerException  if {@code follower} is null
     * @throws IllegalStateException if the follower has not been added
     */
    public int availableCredits(NodeId follower) {
        Objects.requireNonNull(follower, "follower");
        Integer available = credits.get(follower);
        if (available == null) {
            throw new IllegalStateException("Follower not registered: " + follower);
        }
        return available;
    }

    /**
     * Registers a follower with full initial credits.
     * <p>
     * If the follower is already registered, this is a no-op (the existing
     * credit count is preserved).
     *
     * @param follower the follower node to add
     * @throws NullPointerException if {@code follower} is null
     */
    public void addFollower(NodeId follower) {
        Objects.requireNonNull(follower, "follower");
        credits.putIfAbsent(follower, initialCredits);
    }

    /**
     * Removes a follower from flow control tracking.
     *
     * @param follower the follower node to remove
     * @throws NullPointerException  if {@code follower} is null
     * @throws IllegalStateException if the follower has not been added
     */
    public void removeFollower(NodeId follower) {
        Objects.requireNonNull(follower, "follower");
        if (credits.remove(follower) == null) {
            throw new IllegalStateException("Follower not registered: " + follower);
        }
    }

    /**
     * Returns whether the given follower is throttled (has zero credits).
     *
     * @param follower the follower node to check
     * @return {@code true} if the follower has zero available credits
     * @throws NullPointerException  if {@code follower} is null
     * @throws IllegalStateException if the follower has not been added
     */
    public boolean isThrottled(NodeId follower) {
        return availableCredits(follower) == 0;
    }

    /**
     * Restores all registered followers to their full initial credit allocation.
     * Useful when a new leader is elected and replication state is reset.
     */
    public void resetAll() {
        credits.replaceAll((node, current) -> initialCredits);
    }

    /**
     * Returns the initial credit value configured for this controller.
     *
     * @return the initial credit count per follower
     */
    public int initialCredits() {
        return initialCredits;
    }
}
