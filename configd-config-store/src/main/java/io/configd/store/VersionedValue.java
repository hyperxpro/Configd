package io.configd.store;

import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable versioned config value stored in the HAMT.
 * <p>
 * The value bytes are defensively copied on construction to guarantee
 * immutability. Version is the monotonic sequence number from the Raft log;
 * timestamp is the HLC timestamp at commit time.
 *
 * @param value     raw config bytes (never null, never mutated after construction)
 * @param version   monotonic sequence number (ADR-0004)
 * @param timestamp HLC timestamp in milliseconds
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
        // Defensive copy — callers cannot mutate our internal state
        value = value.clone();
    }

    /**
     * Returns a copy of the value bytes. Caller may mutate the returned array
     * without affecting this record.
     */
    @Override
    public byte[] value() {
        return value.clone();
    }

    /**
     * Returns the raw internal array WITHOUT copying. Only for internal
     * read-path use where zero-allocation is critical and the caller
     * guarantees not to mutate the returned array.
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
