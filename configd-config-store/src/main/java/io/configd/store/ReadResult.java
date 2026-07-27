package io.configd.store;

import java.util.Arrays;

/**
 * Pre-allocated NOT_FOUND singleton for zero-alloc miss. Found instances ~24 bytes (header+ref+long+bool).
 * ThreadLocal flyweight removed due to mutable aliasing hazard (consecutive gets overwrote first result).
 */
public final class ReadResult {

    /** Pre-allocated sentinel for cache-friendly "not found" responses. */
    private static final byte[] EMPTY = new byte[0];

    /** Singleton not-found result -- zero allocation on miss. */
    public static final ReadResult NOT_FOUND = new ReadResult(EMPTY, 0, false);

    private final byte[] value;
    private final long version;
    private final boolean found;

    private ReadResult(byte[] value, long version, boolean found) {
        this.value = value;
        this.version = version;
        this.found = found;
    }

    public static ReadResult found(byte[] value, long version) {
        return new ReadResult(value, version, true);
    }

    /**
     * @deprecated Use {@link #found(byte[], long)} instead.
     */
    @Deprecated(forRemoval = true)
    public static ReadResult foundReusable(byte[] value, long version) {
        return found(value, version);
    }

    public byte[] value() {
        return value;
    }

    public long version() {
        return version;
    }

    public boolean found() {
        return found;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ReadResult that
                && this.found == that.found
                && this.version == that.version
                && Arrays.equals(this.value, that.value);
    }

    @Override
    public int hashCode() {
        int h = Boolean.hashCode(found);
        h = 31 * h + Long.hashCode(version);
        h = 31 * h + Arrays.hashCode(value);
        return h;
    }

    @Override
    public String toString() {
        if (!found) {
            return "ReadResult[NOT_FOUND]";
        }
        return "ReadResult[len=" + value.length + ", version=" + version + "]";
    }
}
