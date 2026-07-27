package io.configd.distribution;

import io.configd.common.Clock;
import io.configd.store.ConfigMutation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Under burst writes (e.g., batch imports, rolling deploys), individual
 * per-mutation notifications would overwhelm watchers and waste bandwidth.
 * <p>
 * Thread safety: designed for single-threaded access from the distribution
 * service I/O thread. No synchronization is used.
 */
public final class WatchCoalescer {

    /** Default coalescing window: 10ms - low enough for interactive use,
     *  high enough to batch burst writes. */
    private static final long DEFAULT_WINDOW_NANOS = 10_000_000L;

    private static final int DEFAULT_MAX_BATCH = 64;

    private final Clock clock;
    private final long windowNanos;
    private final int maxBatch;

    private final List<ConfigMutation> pending;
    private long latestVersion;
    private long batchStartNanos;

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

    public WatchCoalescer(Clock clock) {
        this(clock, DEFAULT_WINDOW_NANOS, DEFAULT_MAX_BATCH);
    }

    /** Called by the WatchService when the config store applies a committed entry. */
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
            batchStartNanos = clock.nanoTime();
        }
        pending.addAll(mutations);
        latestVersion = version;
    }

    /** Call this periodically (e.g., on each I/O tick) to determine when to flush. */
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

    public int pendingCount() {
        return pending.size();
    }

    public long pendingVersion() {
        return pending.isEmpty() ? 0 : latestVersion;
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public void reset() {
        pending.clear();
        latestVersion = 0;
        batchStartNanos = 0;
    }
}
