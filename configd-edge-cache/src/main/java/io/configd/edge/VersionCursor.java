package io.configd.edge;

/**
 * Opaque cursor tracking the last version read by a client.
 * <p>
 * Used to enforce monotonic reads: if a client's cursor version exceeds
 * the edge store's current version, the store is stale relative to that
 * client and must either wait for catch-up or signal staleness.
 *
 * @param version   the last sequence number read by the client
 * @param timestamp the HLC timestamp of the last read
 */
public record VersionCursor(long version, long timestamp) {

    /** Initial cursor for clients that have never read. */
    public static final VersionCursor INITIAL = new VersionCursor(0, 0);

    public VersionCursor {
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative: " + version);
        }
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp must be non-negative: " + timestamp);
        }
    }

    /** True if this cursor is ahead of {@code other}. */
    public boolean isNewerThan(VersionCursor other) {
        return this.version > other.version;
    }
}
