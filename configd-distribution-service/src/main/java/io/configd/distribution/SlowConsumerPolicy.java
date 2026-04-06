package io.configd.distribution;

import io.configd.common.Clock;
import io.configd.common.NodeId;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Implements the slow consumer policy (ADR-0003 §7).
 * <p>
 * When an edge node falls behind and cannot keep up with the delta stream,
 * it progresses through escalating states:
 * <pre>
 *   HEALTHY ──(>threshold)──► SLOW ──(>disconnectMs)──► DISCONNECTED ──(>quarantineMs)──► QUARANTINED
 * </pre>
 * <ul>
 *   <li><b>HEALTHY</b> — consumer is keeping up. Reset on every successful ack.</li>
 *   <li><b>SLOW</b> — consumer's pending queue exceeds the threshold.
 *       Warning emitted, but messages are still sent.</li>
 *   <li><b>DISCONNECTED</b> — consumer has been slow for too long (30s default).
 *       Message delivery is stopped. Consumer must re-bootstrap.</li>
 *   <li><b>QUARANTINED</b> — consumer is blocked from reconnecting for a
 *       cooling-off period to prevent reconnect storms.</li>
 * </ul>
 * <p>
 * Thread safety: designed for single-threaded access from the distribution
 * service I/O thread.
 */
public final class SlowConsumerPolicy {

    /**
     * Consumer states, ordered by severity.
     */
    public enum ConsumerState {
        HEALTHY,
        SLOW,
        DISCONNECTED,
        QUARANTINED
    }

    private static final long DEFAULT_SLOW_THRESHOLD_MS = 5_000;
    private static final long DEFAULT_DISCONNECT_MS = 30_000;
    private static final long DEFAULT_QUARANTINE_MS = 60_000;

    private final Clock clock;
    private final long slowThresholdMs;
    private final long disconnectMs;
    private final long quarantineMs;
    private final int maxPendingEntries;
    private final Map<NodeId, ConsumerTracker> consumers;

    /**
     * Creates a slow consumer policy with the given thresholds.
     *
     * @param clock              time source
     * @param maxPendingEntries  maximum pending entries before marking SLOW (default 1000)
     * @param slowThresholdMs    time in SLOW state before disconnect (default 5s)
     * @param disconnectMs       time before disconnect after first slow signal (default 30s)
     * @param quarantineMs       time to quarantine after disconnect (default 60s)
     */
    public SlowConsumerPolicy(Clock clock, int maxPendingEntries,
                               long slowThresholdMs, long disconnectMs, long quarantineMs) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.maxPendingEntries = maxPendingEntries;
        this.slowThresholdMs = slowThresholdMs;
        this.disconnectMs = disconnectMs;
        this.quarantineMs = quarantineMs;
        this.consumers = new HashMap<>();
    }

    /**
     * Creates a slow consumer policy with default thresholds.
     */
    public SlowConsumerPolicy(Clock clock) {
        this(clock, 1000, DEFAULT_SLOW_THRESHOLD_MS, DEFAULT_DISCONNECT_MS, DEFAULT_QUARANTINE_MS);
    }

    /**
     * Registers a consumer for tracking.
     */
    public void register(NodeId node) {
        Objects.requireNonNull(node, "node must not be null");
        consumers.put(node, new ConsumerTracker(clock.currentTimeMillis()));
    }

    /**
     * Removes a consumer from tracking.
     */
    public void unregister(NodeId node) {
        consumers.remove(node);
    }

    /**
     * Records a successful acknowledgment from a consumer. Resets to HEALTHY.
     */
    public void recordAck(NodeId node) {
        ConsumerTracker tracker = consumers.get(node);
        if (tracker != null) {
            tracker.state = ConsumerState.HEALTHY;
            tracker.pendingCount = 0;
            tracker.lastAckMs = clock.currentTimeMillis();
            tracker.slowSinceMs = 0;
        }
    }

    /**
     * Records that a message was sent (or queued) for a consumer.
     * Updates the pending count and evaluates state transitions.
     */
    public void recordSend(NodeId node) {
        ConsumerTracker tracker = consumers.get(node);
        if (tracker == null) return;

        tracker.pendingCount++;
        long now = clock.currentTimeMillis();

        if (tracker.state == ConsumerState.HEALTHY && tracker.pendingCount > maxPendingEntries) {
            tracker.state = ConsumerState.SLOW;
            tracker.slowSinceMs = now;
        }

        if (tracker.state == ConsumerState.SLOW) {
            long slowDuration = now - tracker.slowSinceMs;
            if (slowDuration > disconnectMs) {
                tracker.state = ConsumerState.DISCONNECTED;
                tracker.disconnectedAtMs = now;
            }
        }
    }

    /**
     * Evaluates the current state of a consumer, including quarantine expiry.
     */
    public ConsumerState state(NodeId node) {
        ConsumerTracker tracker = consumers.get(node);
        if (tracker == null) return ConsumerState.HEALTHY;

        if (tracker.state == ConsumerState.DISCONNECTED) {
            long disconnectedDuration = clock.currentTimeMillis() - tracker.disconnectedAtMs;
            if (disconnectedDuration > quarantineMs) {
                tracker.state = ConsumerState.QUARANTINED;
            }
        }
        return tracker.state;
    }

    /**
     * Returns true if the consumer can receive messages (HEALTHY or SLOW).
     */
    public boolean canDeliver(NodeId node) {
        ConsumerState s = state(node);
        return s == ConsumerState.HEALTHY || s == ConsumerState.SLOW;
    }

    /**
     * Re-admits a quarantined or disconnected consumer. Must re-bootstrap
     * (full snapshot sync) before resuming normal operation.
     */
    public void readmit(NodeId node) {
        ConsumerTracker tracker = consumers.get(node);
        if (tracker != null) {
            tracker.state = ConsumerState.HEALTHY;
            tracker.pendingCount = 0;
            tracker.lastAckMs = clock.currentTimeMillis();
            tracker.slowSinceMs = 0;
            tracker.disconnectedAtMs = 0;
        }
    }

    /**
     * Returns all consumers in the given state.
     */
    public Set<NodeId> consumersInState(ConsumerState targetState) {
        var result = new java.util.HashSet<NodeId>();
        for (var entry : consumers.entrySet()) {
            if (state(entry.getKey()) == targetState) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Returns the pending count for a consumer.
     */
    public int pendingCount(NodeId node) {
        ConsumerTracker tracker = consumers.get(node);
        return (tracker != null) ? tracker.pendingCount : 0;
    }

    /**
     * Returns the total number of tracked consumers.
     */
    public int consumerCount() {
        return consumers.size();
    }

    private static final class ConsumerTracker {
        ConsumerState state = ConsumerState.HEALTHY;
        int pendingCount;
        long lastAckMs;
        long slowSinceMs;
        long disconnectedAtMs;

        ConsumerTracker(long now) {
            this.lastAckMs = now;
        }
    }
}
