package io.configd.edge;

import io.configd.common.Clock;
import io.configd.observability.InvariantMonitor;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.ReadResult;
import io.configd.store.VersionedValue;

import java.util.Objects;

/**
 * Edge-local config store. Lock-free reads via volatile HAMT pointer.
 * <p>
 * This is the <b>HOT PATH</b> for config reads at the edge. The read path
 * has been designed for zero allocation, zero locks, and zero CAS operations:
 * <ol>
 *   <li>Load volatile pointer (single CPU instruction, acquire semantics)</li>
 *   <li>Traverse HAMT (O(log32 N) ~= O(1) for practical N)</li>
 *   <li>Return value + version cursor</li>
 * </ol>
 * <p>
 * <b>Write path</b> (single DeltaApplier thread only):
 * <ol>
 *   <li>Apply mutations to HAMT (produces new HAMT via structural sharing)</li>
 *   <li>Store new snapshot to volatile field (StoreStore barrier)</li>
 * </ol>
 * <p>
 * Follows the Read-Copy-Update (RCU) pattern (ADR-0005). The writer thread
 * must be externally serialized — no internal synchronization is provided
 * for writes.
 *
 * @see ConfigSnapshot
 * @see HamtMap
 */
public final class LocalConfigStore {

    private final Clock clock;

    /**
     * The current snapshot. Single volatile pointer — readers load this
     * with acquire semantics; the writer stores with release semantics.
     * No AtomicReference wrapper to avoid the extra indirection.
     */
    @SuppressWarnings("FieldMayBeFinal")
    private volatile ConfigSnapshot currentSnapshot;

    /**
     * Optional invariant monitor for INV-M1 ({@code monotonic_read}). When
     * non-null, {@link #get(String, VersionCursor)} reports a violation
     * whenever the current snapshot's version falls below the client's
     * cursor (F-0073).
     */
    private final InvariantMonitor invariantMonitor;

    /**
     * Creates a store initialized with the given snapshot and clock.
     *
     * @param initialSnapshot the initial snapshot (non-null)
     * @param clock           the clock to use for timestamps (non-null)
     */
    public LocalConfigStore(ConfigSnapshot initialSnapshot, Clock clock) {
        this(initialSnapshot, clock, null);
    }

    /**
     * Creates a store with an {@link InvariantMonitor} wired in. Cursor-bound
     * reads that cannot satisfy monotonic-read (cursor ahead of local version)
     * will report INV-M1 through the monitor.
     */
    public LocalConfigStore(ConfigSnapshot initialSnapshot, Clock clock,
                            InvariantMonitor invariantMonitor) {
        Objects.requireNonNull(initialSnapshot, "initialSnapshot must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        this.currentSnapshot = initialSnapshot;
        this.clock = clock;
        this.invariantMonitor = invariantMonitor;
    }

    /**
     * Creates a store initialized with the given snapshot, using the system clock.
     */
    public LocalConfigStore(ConfigSnapshot initialSnapshot) {
        this(initialSnapshot, Clock.system());
    }

    /**
     * Creates an empty store with the given clock.
     *
     * @param clock the clock to use for timestamps (non-null)
     */
    public LocalConfigStore(Clock clock) {
        this(ConfigSnapshot.EMPTY, clock);
    }

    /**
     * Creates an empty store at version 0, using the system clock.
     */
    public LocalConfigStore() {
        this(ConfigSnapshot.EMPTY, Clock.system());
    }

    // -----------------------------------------------------------------------
    // Reader methods — any thread, zero allocation on miss
    // -----------------------------------------------------------------------

    /**
     * Reads the current value for a config key.
     * <p>
     * Returns {@link ReadResult#NOT_FOUND} (pre-allocated singleton) on miss.
     * On hit, allocates a single {@link ReadResult} — unavoidable since each
     * result carries the value's version.
     *
     * @param key config key (non-null)
     * @return read result (never null)
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
     * Reads a value with monotonic read enforcement via a version cursor.
     * <p>
     * If the client's cursor version exceeds this store's current version,
     * the store is stale relative to that client — returns
     * {@link ReadResult#NOT_FOUND} to signal that the client should retry
     * or fall back to a different edge node.
     *
     * @param key    config key (non-null)
     * @param cursor the client's last-read cursor
     * @return read result; NOT_FOUND if store is stale or key is absent
     */
    public ReadResult get(String key, VersionCursor cursor) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(cursor, "cursor must not be null");
        ConfigSnapshot snap = currentSnapshot;
        if (snap.version() < cursor.version()) {
            // Store is behind the client — monotonic-read violation (INV-M1).
            // F-0073: route through InvariantMonitor when wired so that
            // configd.invariant.violation.monotonic_read increments.
            if (invariantMonitor != null) {
                invariantMonitor.assertMonotonicRead(key, cursor.version(), snap.version());
            }
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

    /** Returns the current monotonic version. */
    public long currentVersion() {
        return currentSnapshot.version();
    }

    /**
     * Returns the current immutable snapshot. Safe to hold and traverse
     * from any thread.
     */
    public ConfigSnapshot snapshot() {
        return currentSnapshot;
    }

    // -----------------------------------------------------------------------
    // Writer methods — single DeltaApplier thread only
    // -----------------------------------------------------------------------

    /**
     * Applies a delta (set of mutations) to the current snapshot, producing
     * a new snapshot via HAMT structural sharing and publishing it via
     * volatile store.
     * <p>
     * The delta's {@code fromVersion} must match the store's current version.
     * If it does not, the delta is rejected (gap detected — the caller must
     * request a full snapshot sync).
     *
     * @param delta the delta to apply
     * @throws IllegalArgumentException if delta.fromVersion != currentVersion
     */
    public void applyDelta(ConfigDelta delta) {
        Objects.requireNonNull(delta, "delta must not be null");

        ConfigSnapshot snap = currentSnapshot;
        if (delta.fromVersion() != snap.version()) {
            throw new IllegalArgumentException(
                    "Delta fromVersion (" + delta.fromVersion()
                            + ") does not match current version (" + snap.version()
                            + "). Full snapshot sync required.");
        }

        HamtMap<String, VersionedValue> data = snap.data();
        long timestamp = clock.currentTimeMillis();

        for (ConfigMutation mutation : delta.mutations()) {
            switch (mutation) {
                case ConfigMutation.Put put -> {
                    VersionedValue vv = new VersionedValue(
                            put.valueUnsafe(), delta.toVersion(), timestamp);
                    data = data.put(put.key(), vv);
                }
                case ConfigMutation.Delete del -> {
                    data = data.remove(del.key());
                }
            }
        }

        currentSnapshot = new ConfigSnapshot(data, delta.toVersion(), timestamp);
    }

    /**
     * Replaces the entire store with a full snapshot. Used for initial sync
     * or recovery after gap detection.
     *
     * @param snapshot the snapshot to load (must not be null)
     */
    public void loadSnapshot(ConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        currentSnapshot = snapshot;
    }
}
