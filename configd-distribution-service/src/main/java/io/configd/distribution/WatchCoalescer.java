package io.configd.distribution;

import io.configd.common.Clock;
import io.configd.store.ConfigMutation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Batches rapid config mutations within a bounded time window before
 * producing a single coalesced {@link WatchEvent}.
 * <p>
 * Under burst writes (e.g., batch imports, rolling deploys), individual
 * per-mutation notifications would overwhelm watchers and waste bandwidth.
 * The coalescer accumulates mutations and flushes them as a single event
 * when any of three thresholds is met:
 * <ol>
 *   <li><b>Time</b> — the coalescing window (default 10ms) has elapsed
 *       since the first mutation in the current batch.</li>
 *   <li><b>Count</b> — the batch has reached the maximum number of
 *       mutations (default 64).</li>
 *   <li><b>Explicit flush</b> — the caller forces a flush.</li>
 * </ol>
 * <p>
 * Thread safety: designed for single-threaded access from the distribution
 * service I/O thread. No synchronization is used.
 *
 * @see WatchService
 * @see WatchEvent
 */
public final class WatchCoalescer {

    /** Default coalescing window: 10ms — low enough for interactive use,
     *  high enough to batch burst writes. */
    private static final long DEFAULT_WINDOW_NANOS = 10_000_000L; // 10ms

    /** Default maximum mutations per coalesced event. */
    private static final int DEFAULT_MAX_BATCH = 64;

    private final Clock clock;
    private final long windowNanos;
    private final int maxBatch;

    private final List<ConfigMutation> pending;
    private long latestVersion;
    private long batchStartNanos;

    /**
     * Creates a coalescer with the given thresholds.
     *
     * @param clock       time source
     * @param windowNanos coalescing window in nanoseconds
     * @param maxBatch    maximum mutations per batch
     */
    public WatchCoalescer(Clock clock, long windowNanos, int maxBatch) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (windowNanos <= 0) {
            throw new IllegalArgumentException("windowNanos must be positive: " + windowNanos);
        }
        if (maxBatch <= 0) {
            throw new IllegalArgumentException("maxBatch must be positive: " + maxBatch);
        }
        this.windowNanos = windowNanos;
        this.maxBatch = maxBatch;
        this.pending = new ArrayList<>();
        this.latestVersion = 0;
        this.batchStartNanos = 0;
    }

    /**
     * Creates a coalescer with default thresholds (10ms window, 64 max batch).
     */
    public WatchCoalescer(Clock clock) {
        this(clock, DEFAULT_WINDOW_NANOS, DEFAULT_MAX_BATCH);
    }

    /**
     * Adds mutations to the current batch. Called by the WatchService when
     * the config store applies a committed entry.
     *
     * @param mutations the mutations applied
     * @param version   the store version after applying
     */
    public void add(List<ConfigMutation> mutations, long version) {
        Objects.requireNonNull(mutations, "mutations must not be null");
        if (mutations.isEmpty()) {
            return;
        }
        if (version <= latestVersion && !pending.isEmpty()) {
            throw new IllegalArgumentException(
                    "version must be monotonically increasing: " + version
                            + " <= " + latestVersion);
        }

        if (pending.isEmpty()) {
            // Lazy initialization of batch start time
            batchStartNanos = clock.nanoTime();
        }
        pending.addAll(mutations);
        latestVersion = version;
    }

    /**
     * Returns true if the coalescing window has expired or the batch is full.
     * Call this periodically (e.g., on each I/O tick) to determine when to
     * flush.
     */
    public boolean shouldFlush() {
        if (pending.isEmpty()) {
            return false;
        }
        if (pending.size() >= maxBatch) {
            return true;
        }
        long elapsed = clock.nanoTime() - batchStartNanos;
        return elapsed >= windowNanos;
    }

    /**
     * Flushes the current batch, returning a coalesced event, or null if
     * there is nothing to flush.
     *
     * @return a WatchEvent containing all batched mutations, or null if empty
     */
    public WatchEvent flush() {
        if (pending.isEmpty()) {
            return null;
        }
        WatchEvent event = new WatchEvent(List.copyOf(pending), latestVersion);
        pending.clear();
        batchStartNanos = 0;
        return event;
    }

    /**
     * Returns the number of mutations in the current pending batch.
     */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * Returns the latest version in the current batch, or 0 if empty.
     */
    public long pendingVersion() {
        return pending.isEmpty() ? 0 : latestVersion;
    }

    /**
     * Returns true if there are no pending mutations.
     */
    public boolean isEmpty() {
        return pending.isEmpty();
    }

    /**
     * Discards all pending mutations without producing an event.
     */
    public void reset() {
        pending.clear();
        latestVersion = 0;
        batchStartNanos = 0;
    }
}
