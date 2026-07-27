package io.configd.store;

import java.util.Objects;

/**
 * Immutable point-in-time config store snapshot. Underlying HamtMap is persistent
 * and thread-safe for concurrent reads; snapshot is shareable across threads.
 * Timestamp is freshness-only (node-local wall-clock, not cross-shard HLC or global order).
 */
public record ConfigSnapshot(
        HamtMap<String, VersionedValue> data,
        long version,
        long timestamp
) {

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
     * Get raw config bytes (zero-copy, no defensive copy). Callers MUST NOT mutate
     * returned array. Returns null if absent.
     */
    public byte[] get(String key) {
        VersionedValue vv = data.get(key);
        return (vv == null) ? null : vv.valueUnsafe();
    }

    public boolean containsKey(String key) {
        return data.containsKey(key);
    }

    public int size() {
        return data.size();
    }
}
