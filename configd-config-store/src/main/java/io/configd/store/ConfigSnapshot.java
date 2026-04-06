package io.configd.store;

import java.util.Objects;

/**
 * An immutable point-in-time snapshot of the config store.
 * <p>
 * The underlying {@link HamtMap} is persistent and thread-safe for concurrent
 * reads. A snapshot can be shared across threads without synchronization.
 *
 * @param data      the immutable HAMT containing all config key-value pairs
 * @param version   monotonic sequence number (ADR-0004)
 * @param timestamp HLC timestamp in milliseconds
 */
public record ConfigSnapshot(
        HamtMap<String, VersionedValue> data,
        long version,
        long timestamp
) {

    /** Empty snapshot at version 0. */
    public static final ConfigSnapshot EMPTY =
            new ConfigSnapshot(HamtMap.empty(), 0, 0);

    public ConfigSnapshot {
        Objects.requireNonNull(data, "data must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative: " + version);
        }
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp must be non-negative: " + timestamp);
        }
    }

    /**
     * Returns the raw config bytes for the given key, or {@code null} if absent.
     * <p>
     * This returns the internal byte array without copying for zero-allocation
     * reads. Callers MUST NOT mutate the returned array.
     */
    public byte[] get(String key) {
        VersionedValue vv = data.get(key);
        return (vv == null) ? null : vv.valueUnsafe();
    }

    /** True if the snapshot contains a mapping for the given key. */
    public boolean containsKey(String key) {
        return data.containsKey(key);
    }

    /** Number of config entries in this snapshot. */
    public int size() {
        return data.size();
    }
}
