package io.configd.store;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable versioned config value stored in HAMT. Value bytes defensively copied
 * on construction. Version is monotonic Raft log sequence; timestamp is applying node's
 * local wall-clock (freshness-only, NOT cross-shard HLC or global order). Each replica
 * applies log independently, so timestamp is non-deterministic across replicas; MUST NOT
 * be used to order events across shards (RFC §2 W3-3/W6-2a).
 */
public record VersionedValue(byte[] value, long version, long timestamp) {

    public VersionedValue {
        Objects.requireNonNull(value, "value must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative: " + version);
        }
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp must be non-negative: " + timestamp);
        }
        value = value.clone();
    }

    @Override
    public byte[] value() {
        return value.clone();
    }

    /**
     * Returns internal array without copying: zero-allocation read-path use only.
     * Caller MUST NOT mutate returned array.
     */
    public byte[] valueUnsafe() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VersionedValue that
                && this.version == that.version
                && this.timestamp == that.timestamp
                && Arrays.equals(this.value, that.value);
    }

    @Override
    public int hashCode() {
        int h = Arrays.hashCode(value);
        h = 31 * h + Long.hashCode(version);
        h = 31 * h + Long.hashCode(timestamp);
        return h;
    }

    @Override
    public String toString() {
        return "VersionedValue[len=" + value.length + ", version=" + version
                + ", timestamp=" + timestamp + "]";
    }
}
