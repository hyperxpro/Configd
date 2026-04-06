package io.configd.store;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Manages retention of old {@link ConfigSnapshot}s for delta computation.
 * <p>
 * The compactor tracks a sliding window of historical snapshots keyed by
 * version. When {@link #compact()} is called, snapshots beyond the
 * retention count are removed, freeing them for garbage collection.
 * <p>
 * <b>Thread safety:</b> All methods are safe for concurrent access.
 * The internal map uses a {@link ConcurrentSkipListMap} for lock-free
 * reads and ordered iteration. This allows the compaction thread to
 * run concurrently with delta computation threads that read historical
 * snapshots.
 * <p>
 * <b>Usage pattern:</b>
 * <ol>
 *   <li>After each successful apply in the state machine, call
 *       {@link #addSnapshot(ConfigSnapshot)} with the new snapshot.</li>
 *   <li>Periodically call {@link #compact()} from a background thread
 *       to remove old snapshots beyond the retention window.</li>
 *   <li>The delta computer calls {@link #getSnapshot(long)} to retrieve
 *       a base snapshot for computing deltas.</li>
 * </ol>
 *
 * @see ConfigSnapshot
 * @see DeltaComputer
 */
public final class Compactor {

    /** Default number of snapshots to retain. */
    public static final int DEFAULT_RETENTION_COUNT = 10;

    private final int retentionCount;

    /**
     * Historical snapshots keyed by version. ConcurrentSkipListMap provides
     * ordered access (ascending by version) and lock-free reads.
     */
    private final ConcurrentNavigableMap<Long, ConfigSnapshot> history =
            new ConcurrentSkipListMap<>();

    /**
     * Creates a compactor with the specified retention count.
     *
     * @param retentionCount number of old snapshots to retain (must be >= 1)
     * @throws IllegalArgumentException if retentionCount < 1
     */
    public Compactor(int retentionCount) {
        if (retentionCount < 1) {
            throw new IllegalArgumentException(
                    "retentionCount must be >= 1: " + retentionCount);
        }
        this.retentionCount = retentionCount;
    }

    /**
     * Creates a compactor with the {@link #DEFAULT_RETENTION_COUNT default}
     * retention count.
     */
    public Compactor() {
        this(DEFAULT_RETENTION_COUNT);
    }

    // -----------------------------------------------------------------------
    // Snapshot management
    // -----------------------------------------------------------------------

    /**
     * Adds a snapshot to the history. If a snapshot with the same version
     * already exists, it is replaced.
     *
     * @param snapshot the snapshot to add (non-null)
     */
    public void addSnapshot(ConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        history.put(snapshot.version(), snapshot);
    }

    /**
     * Retrieves a historical snapshot by exact version number.
     *
     * @param version the version to look up
     * @return the snapshot, or empty if not retained
     */
    public Optional<ConfigSnapshot> getSnapshot(long version) {
        return Optional.ofNullable(history.get(version));
    }

    /**
     * Returns the version number of the oldest retained snapshot, or
     * empty if no snapshots are retained.
     *
     * @return the oldest retained version, or empty
     */
    public Optional<Long> oldestRetainedVersion() {
        var entry = history.firstEntry();
        return (entry != null) ? Optional.of(entry.getKey()) : Optional.empty();
    }

    /**
     * Returns the version number of the newest retained snapshot, or
     * empty if no snapshots are retained.
     *
     * @return the newest retained version, or empty
     */
    public Optional<Long> newestRetainedVersion() {
        var entry = history.lastEntry();
        return (entry != null) ? Optional.of(entry.getKey()) : Optional.empty();
    }

    /**
     * Returns the number of snapshots currently retained.
     */
    public int snapshotCount() {
        return history.size();
    }

    // -----------------------------------------------------------------------
    // Compaction
    // -----------------------------------------------------------------------

    /**
     * Removes snapshots beyond the retention window.
     * <p>
     * Keeps the most recent {@code retentionCount} snapshots and removes
     * all older ones. Returns the number of snapshots removed.
     * <p>
     * This method is safe to call from any thread, including concurrently
     * with {@link #addSnapshot} and {@link #getSnapshot}.
     *
     * @return the number of snapshots removed
     */
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

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    /** Returns the configured retention count. */
    public int retentionCount() {
        return retentionCount;
    }
}
