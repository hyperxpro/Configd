package io.configd.edge;

/**
 * Client cursor for monotonic-read enforcement. Record: version read + timestamp.
 */
public record VersionCursor(long version, long timestamp) {

    public static final VersionCursor INITIAL = new VersionCursor(0, 0);

    public VersionCursor {
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative: " + version);
        }
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp must be non-negative: " + timestamp);
        }
    }

    public boolean isNewerThan(VersionCursor other) {
        return this.version > other.version;
    }
}
