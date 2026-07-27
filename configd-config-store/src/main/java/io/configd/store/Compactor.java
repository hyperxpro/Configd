package io.configd.store;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * <b>Thread safety:</b> Safe for concurrent access - the compaction thread may run concurrently
 * with threads calling {@link #addSnapshot} or {@link #getSnapshot}.
 */
public final class Compactor {

    public static final int DEFAULT_RETENTION_COUNT = 10;

    private final int retentionCount;

    /**
     * Historical snapshots keyed by version. ConcurrentSkipListMap provides
     * ordered access (ascending by version) and lock-free reads.
     */
    private final ConcurrentNavigableMap<Long, ConfigSnapshot> history =
            new ConcurrentSkipListMap<>();

    public Compactor(int retentionCount) {
        if (retentionCount < 1) {
            throw new IllegalArgumentException(
                    "retentionCount must be >= 1: " + retentionCount);
        }
        this.retentionCount = retentionCount;
    }

    public Compactor() {
        this(DEFAULT_RETENTION_COUNT);
    }

    public void addSnapshot(ConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        history.put(snapshot.version(), snapshot);
    }

    public Optional<ConfigSnapshot> getSnapshot(long version) {
        return Optional.ofNullable(history.get(version));
    }

    public Optional<Long> oldestRetainedVersion() {
        var entry = history.firstEntry();
        return (entry != null) ? Optional.of(entry.getKey()) : Optional.empty();
    }

    public Optional<Long> newestRetainedVersion() {
        var entry = history.lastEntry();
        return (entry != null) ? Optional.of(entry.getKey()) : Optional.empty();
    }

    public int snapshotCount() {
        return history.size();
    }

    /** Safe to call from any thread, including concurrently with {@link #addSnapshot} and {@link #getSnapshot}. */
    public int compact() {
        int removed = 0;
        while (history.size() > retentionCount) {
            var oldest = history.firstEntry();
            if (oldest == null) {
                break;
            }
            // Only remove if the map is still over the retention limit.
            // This handles concurrent adds gracefully.
            if (history.size() > retentionCount) {
                if (history.remove(oldest.getKey()) != null) {
                    removed++;
                }
            }
        }
        return removed;
    }

    public int retentionCount() {
        return retentionCount;
    }
}
