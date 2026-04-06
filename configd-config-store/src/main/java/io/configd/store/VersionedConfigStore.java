package io.configd.store;

import io.configd.common.Clock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MVCC versioned config store. Single-writer, multiple-reader.
 * <p>
 * The writer thread (typically the Raft apply thread) mutates the store by
 * applying puts, deletes, or batches. Each mutation produces a new immutable
 * {@link ConfigSnapshot} that is published via a volatile reference swap.
 * <p>
 * Reader threads access the current snapshot through the volatile pointer
 * with zero synchronization and zero locks. On miss, the pre-allocated
 * {@link ReadResult#NOT_FOUND} sentinel is returned (zero allocation).
 * On hit, a new {@link ReadResult} (~24 bytes) is allocated per read.
 * <p>
 * <b>Thread safety:</b>
 * <ul>
 *   <li>Write methods ({@link #put}, {@link #delete}, {@link #applyBatch})
 *       must be called from a single thread. No internal synchronization is
 *       provided for writes — the caller must ensure single-writer semantics.</li>
 *   <li>Read methods ({@link #get(String)}, {@link #currentVersion()},
 *       {@link #snapshot()}) may be called from any thread at any time.</li>
 * </ul>
 *
 * @see ConfigSnapshot
 * @see HamtMap
 */
public final class VersionedConfigStore {

    private final Clock clock;

    /**
     * The current snapshot. Published via volatile write by the single writer
     * and read by concurrent readers with acquire semantics.
     */
    private volatile ConfigSnapshot currentSnapshot;

    /**
     * Creates a store initialized with the given snapshot and clock.
     *
     * @param initialSnapshot the initial snapshot (non-null)
     * @param clock           the clock to use for timestamps (non-null)
     */
    public VersionedConfigStore(ConfigSnapshot initialSnapshot, Clock clock) {
        Objects.requireNonNull(initialSnapshot, "initialSnapshot must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        this.currentSnapshot = initialSnapshot;
        this.clock = clock;
    }

    /**
     * Creates a store initialized with the given snapshot, using the system clock.
     */
    public VersionedConfigStore(ConfigSnapshot initialSnapshot) {
        this(initialSnapshot, Clock.system());
    }

    /**
     * Creates an empty store at version 0 with the given clock.
     *
     * @param clock the clock to use for timestamps (non-null)
     */
    public VersionedConfigStore(Clock clock) {
        this(ConfigSnapshot.EMPTY, clock);
    }

    /**
     * Creates an empty store at version 0, using the system clock.
     */
    public VersionedConfigStore() {
        this(ConfigSnapshot.EMPTY, Clock.system());
    }

    // -----------------------------------------------------------------------
    // Writer methods (single-threaded — called from Raft apply thread)
    // -----------------------------------------------------------------------

    /**
     * Inserts or updates a config key.
     *
     * @param key      config key (non-null, non-blank)
     * @param value    raw config bytes (non-null)
     * @param sequence the monotonic sequence number for this mutation
     */
    public void put(String key, byte[] value, long sequence) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        if (sequence <= currentSnapshot.version()) {
            throw new IllegalArgumentException(
                    "sequence (" + sequence + ") must be > current version ("
                            + currentSnapshot.version() + ")");
        }

        long timestamp = clock.currentTimeMillis();
        VersionedValue vv = new VersionedValue(value, sequence, timestamp);
        HamtMap<String, VersionedValue> newData = currentSnapshot.data().put(key, vv);
        currentSnapshot = new ConfigSnapshot(newData, sequence, timestamp);
    }

    /**
     * Deletes a config key. No-op if the key is absent.
     *
     * @param key      config key (non-null)
     * @param sequence the monotonic sequence number for this mutation
     */
    public void delete(String key, long sequence) {
        Objects.requireNonNull(key, "key must not be null");
        if (sequence <= currentSnapshot.version()) {
            throw new IllegalArgumentException(
                    "sequence (" + sequence + ") must be > current version ("
                            + currentSnapshot.version() + ")");
        }

        long timestamp = clock.currentTimeMillis();
        HamtMap<String, VersionedValue> newData = currentSnapshot.data().remove(key);
        currentSnapshot = new ConfigSnapshot(newData, sequence, timestamp);
    }

    /**
     * Atomically applies a batch of mutations as a single version bump.
     * All mutations in the batch share the same sequence number and timestamp.
     *
     * @param mutations list of mutations to apply (non-null, non-empty)
     * @param sequence  the monotonic sequence number for this batch
     */
    public void applyBatch(List<ConfigMutation> mutations, long sequence) {
        Objects.requireNonNull(mutations, "mutations must not be null");
        if (mutations.isEmpty()) {
            return;
        }
        if (sequence <= currentSnapshot.version()) {
            throw new IllegalArgumentException(
                    "sequence (" + sequence + ") must be > current version ("
                            + currentSnapshot.version() + ")");
        }

        long timestamp = clock.currentTimeMillis();
        HamtMap<String, VersionedValue> data = currentSnapshot.data();

        for (ConfigMutation mutation : mutations) {
            switch (mutation) {
                case ConfigMutation.Put put -> {
                    VersionedValue vv = new VersionedValue(
                            put.valueUnsafe(), sequence, timestamp);
                    data = data.put(put.key(), vv);
                }
                case ConfigMutation.Delete del -> {
                    data = data.remove(del.key());
                }
            }
        }

        currentSnapshot = new ConfigSnapshot(data, sequence, timestamp);
    }

    /**
     * Atomically replaces the current snapshot with the given one.
     * <p>
     * This is used during Raft snapshot restore to wholesale replace the
     * store state. The volatile write ensures that concurrent readers will
     * observe the new snapshot on their next read.
     * <p>
     * <b>Caller must ensure single-writer semantics</b> — this method is
     * intended to be called from the Raft apply thread only.
     *
     * @param snapshot the new snapshot to install (non-null)
     */
    public void restoreSnapshot(ConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.currentSnapshot = snapshot;
    }

    // -----------------------------------------------------------------------
    // Reader methods (any thread, lock-free, zero allocation on miss, ~24 B on hit)
    // -----------------------------------------------------------------------

    /**
     * Reads the current value for a key.
     * <p>
     * Returns {@link ReadResult#NOT_FOUND} (pre-allocated singleton) if the
     * key is absent — zero allocation on miss.
     */
    public ReadResult get(String key) {
        Objects.requireNonNull(key, "key must not be null");
        ConfigSnapshot snap = currentSnapshot; // single volatile read
        VersionedValue vv = snap.data().get(key);
        if (vv == null) {
            return ReadResult.NOT_FOUND;
        }
        return ReadResult.found(vv.valueUnsafe(), vv.version());
    }

    /**
     * Reads the value for a key, requiring at least {@code minVersion}.
     * If the store's current version is below {@code minVersion}, returns
     * {@link ReadResult#NOT_FOUND} to signal staleness.
     */
    public ReadResult get(String key, long minVersion) {
        Objects.requireNonNull(key, "key must not be null");
        ConfigSnapshot snap = currentSnapshot;
        if (snap.version() < minVersion) {
            return ReadResult.NOT_FOUND;
        }
        VersionedValue vv = snap.data().get(key);
        if (vv == null) {
            return ReadResult.NOT_FOUND;
        }
        return ReadResult.found(vv.valueUnsafe(), vv.version());
    }

    /**
     * Primitive-friendly read that avoids allocating a {@link ReadResult}
     * wrapper. Writes the value bytes into {@code dst} starting at offset 0
     * and stores the version at {@code versionOut[0]}.
     * <p>
     * See VDR-0001: this API exists for throughput-critical internal callers
     * (delta propagation, bulk replay) that want strict zero allocation on
     * both hit and miss paths. External consumers should keep using
     * {@link #get(String)} for ergonomics.
     *
     * @param key        the config key (non-null)
     * @param dst        destination buffer; must be at least as large as the value
     * @param versionOut one-element array that receives the version on a hit
     * @return the value length on hit, {@code -1} on miss, {@code -N-1} if
     *         {@code dst.length < N} (value length encoded as a negative so the
     *         caller can resize and retry)
     */
    public int getInto(String key, byte[] dst, long[] versionOut) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(dst, "dst must not be null");
        Objects.requireNonNull(versionOut, "versionOut must not be null");
        if (versionOut.length < 1) {
            throw new IllegalArgumentException("versionOut must have length >= 1");
        }
        ConfigSnapshot snap = currentSnapshot;
        VersionedValue vv = snap.data().get(key);
        if (vv == null) {
            return -1;
        }
        byte[] v = vv.valueUnsafe();
        int n = v.length;
        if (dst.length < n) {
            return -n - 1;
        }
        System.arraycopy(v, 0, dst, 0, n);
        versionOut[0] = vv.version();
        return n;
    }

    /**
     * Returns all key-value pairs whose keys start with the given prefix.
     * <p>
     * This scans the entire snapshot (O(N)) because HAMT does not support
     * ordered prefix iteration. For production use with large key sets,
     * consider maintaining a secondary index.
     */
    public Map<String, ReadResult> getPrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        ConfigSnapshot snap = currentSnapshot;
        Map<String, ReadResult> results = new LinkedHashMap<>();
        snap.data().forEach((key, vv) -> {
            if (key.startsWith(prefix)) {
                results.put(key, ReadResult.found(vv.valueUnsafe(), vv.version()));
            }
        });
        return results;
    }

    /** Returns the current monotonic version number. */
    public long currentVersion() {
        return currentSnapshot.version();
    }

    /**
     * Returns the current immutable snapshot. Safe to hold and read from
     * any thread — it will never change.
     */
    public ConfigSnapshot snapshot() {
        return currentSnapshot;
    }
}
