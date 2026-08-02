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
 * Follows the Read-Copy-Update (RCU) pattern. The writer thread must be
 * externally serialized - no internal synchronization is provided for writes.
 *
 * @see ConfigSnapshot
 * @see HamtMap
 */
public final class LocalConfigStore {

    private final Clock clock;

    /**
     * The current snapshot. Single volatile pointer - readers load this
     * with acquire semantics; the writer stores with release semantics.
     * No AtomicReference wrapper to avoid the extra indirection.
     */
    @SuppressWarnings("FieldMayBeFinal")
    private volatile ConfigSnapshot currentSnapshot;

    /**
     * Optional invariant monitor for the monotonic-read guarantee. When
     * non-null, {@link #get(String, VersionCursor)} reports a violation
     * whenever the current snapshot's version falls below the client's cursor.
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
     * report the violation through the monitor.
     */
    public LocalConfigStore(ConfigSnapshot initialSnapshot, Clock clock,
                            InvariantMonitor invariantMonitor) {
        Objects.requireNonNull(initialSnapshot, "initialSnapshot must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        this.currentSnapshot = initialSnapshot;
        this.clock = clock;
        this.invariantMonitor = invariantMonitor;
    }

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

    public LocalConfigStore() {
        this(ConfigSnapshot.EMPTY, Clock.system());
    }

    public ReadResult get(String key) {
        Objects.requireNonNull(key, "key must not be null");
        ConfigSnapshot snap = currentSnapshot;
        VersionedValue vv = snap.data().get(key);
        if (vv == null) {
            return ReadResult.NOT_FOUND;
        }
        return ReadResult.found(vv.valueUnsafe(), vv.version());
    }

    public ReadResult get(String key, VersionCursor cursor) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(cursor, "cursor must not be null");
        ConfigSnapshot snap = currentSnapshot;
        if (snap.version() < cursor.version()) {
            // Store is behind the client -- a monotonic-read violation.
            // Route through InvariantMonitor when wired so that
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

    // Zero-allocation internal path (no ReadResult wrapper). Returns length; negative if too small.
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

    public long currentVersion() {
        return currentSnapshot.version();
    }

    public ConfigSnapshot snapshot() {
        return currentSnapshot;
    }

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

    public void loadSnapshot(ConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        currentSnapshot = snapshot;
    }
}
