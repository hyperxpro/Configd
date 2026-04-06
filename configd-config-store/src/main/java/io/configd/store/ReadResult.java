package io.configd.store;

import java.util.Arrays;

/**
 * Immutable result of a config store read operation.
 * <p>
 * Instances are obtained from the pre-allocated {@link #NOT_FOUND} sentinel
 * or from the {@link #found(byte[], long)} factory. The {@code found} flag
 * distinguishes a missing key from a present key with an empty value.
 * <p>
 * Each found result is a lightweight 24-byte object (object header + reference
 * + long + boolean), trivially collected by ZGC in the nursery. The previous
 * ThreadLocal flyweight pattern was removed because it introduced a mutable
 * aliasing hazard: two consecutive {@code get()} calls would silently overwrite
 * the first result's data.
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

    /**
     * Creates a found result with the given value and version.
     *
     * @param value   raw config bytes (non-null)
     * @param version the version at which this value was written
     * @return a new immutable ReadResult
     */
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

    /** Raw config bytes, or empty array if not found. */
    public byte[] value() {
        return value;
    }

    /** The version at which this value was written (0 if not found). */
    public long version() {
        return version;
    }

    /** True if the key exists in the store. */
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
