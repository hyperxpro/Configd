package io.configd.store;

import io.configd.common.Clock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MVCC single-writer multiple-reader. Writer (Raft apply) produces new immutable snapshots;
 * readers access via volatile with zero synchronization. Miss=pre-alloc NOT_FOUND (no alloc);
 * hit=24-byte ReadResult.
 *
 * @see ConfigSnapshot
 * @see HamtMap
 */
public final class VersionedConfigStore {

    private final Clock clock;

    private volatile ConfigSnapshot currentSnapshot;

    public VersionedConfigStore(ConfigSnapshot initialSnapshot, Clock clock) {
        Objects.requireNonNull(initialSnapshot, "initialSnapshot must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        this.currentSnapshot = initialSnapshot;
        this.clock = clock;
    }

    public VersionedConfigStore(ConfigSnapshot initialSnapshot) {
        this(initialSnapshot, Clock.system());
    }

    public VersionedConfigStore(Clock clock) {
        this(ConfigSnapshot.EMPTY, clock);
    }

    public VersionedConfigStore() {
        this(ConfigSnapshot.EMPTY, Clock.system());
    }

    /**
     * Single-writer only. Sequence must be > current version.
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
     * Single-writer only. No-op if key absent. Sequence must be > current version.
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
     * Single-writer only. Atomic batch with shared sequence+timestamp. Sequence > current version.
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
     * Raft snapshot restore: wholesale replace via volatile write. Single-writer only.
     */
    public void restoreSnapshot(ConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.currentSnapshot = snapshot;
    }

    /**
     * Miss returns pre-allocated NOT_FOUND singleton (zero alloc).
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
     * Returns NOT_FOUND if store version < minVersion (staleness signal).
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
     * Zero-alloc primitive-friendly read for throughput-critical paths (delta, replay).
     * Return: value len (hit), -1 (miss), -(N+1) (buffer too small, retry with size N).
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
     * O(N) full-snapshot scan (HAMT lacks ordered prefix iteration).
     * For large key sets, maintain a secondary index.
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

    public record PrefixScan(long version, Map<String, ReadResult> entries) {
    }

    /**
     * Like getPrefix but pairs with snapshot version (single volatile read = consistent).
     * Callers publish derived views using returned version for monotonic ordering.
     */
    public PrefixScan getPrefixVersioned(String prefix) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        ConfigSnapshot snap = currentSnapshot;
        Map<String, ReadResult> results = new LinkedHashMap<>();
        snap.data().forEach((key, vv) -> {
            if (key.startsWith(prefix)) {
                results.put(key, ReadResult.found(vv.valueUnsafe(), vv.version()));
            }
        });
        return new PrefixScan(snap.version(), results);
    }

    public long currentVersion() {
        return currentSnapshot.version();
    }

    /**
     * Immutable snapshot safe to hold across threads (never mutated after published).
     */
    public ConfigSnapshot snapshot() {
        return currentSnapshot;
    }
}
