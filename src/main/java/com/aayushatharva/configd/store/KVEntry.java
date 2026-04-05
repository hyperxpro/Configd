package com.aayushatharva.configd.store;

import java.util.Arrays;

/**
 * A key-value entry with associated version metadata.
 */
public record KVEntry(byte[] key, byte[] value, long version, boolean tombstone) {

    public static KVEntry of(byte[] key, byte[] value, long version) {
        return new KVEntry(key, value, version, false);
    }

    public static KVEntry tombstone(byte[] key, long version) {
        return new KVEntry(key, null, version, true);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KVEntry that)) return false;
        return version == that.version
                && tombstone == that.tombstone
                && Arrays.equals(key, that.key)
                && Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(key);
        result = 31 * result + Arrays.hashCode(value);
        result = 31 * result + Long.hashCode(version);
        result = 31 * result + Boolean.hashCode(tombstone);
        return result;
    }

    @Override
    public String toString() {
        return "KVEntry{key=" + Arrays.toString(key)
                + ", valueLen=" + (value != null ? value.length : 0)
                + ", version=" + version
                + ", tombstone=" + tombstone + '}';
    }
}
