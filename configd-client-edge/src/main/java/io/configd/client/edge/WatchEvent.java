package io.configd.client.edge;

import java.util.List;
import java.util.Objects;

/**
 * Watch event from exactly one shard-commit. Order guarantee: per-key/per-shard only (same gid, ascending s).
 * Cross-shard events are CONCURRENT; never infer global order from arrival sequence or magnitude. Use
 * {@link #ordered(WatchEvent, WatchEvent)} to test order. commitTs is freshness only, never a cursor.
 */
public record WatchEvent(int gid, long s, long commitTs, List<ConfigChange> changes) {

    public WatchEvent {
        if (s < 0) {
            throw new IllegalArgumentException("S must be non-negative: " + s);
        }
        if (commitTs < 0) {
            throw new IllegalArgumentException("commitTs must be non-negative: " + commitTs);
        }
        Objects.requireNonNull(changes, "changes");
        changes = List.copyOf(changes);
    }

    public long gidUnsigned() {
        return Integer.toUnsignedLong(gid);
    }

    /** True iff a precedes b: same gid and a.s < b.s. Different gids are CONCURRENT (never ordered). */
    public static boolean ordered(WatchEvent a, WatchEvent b) {
        return a.gid == b.gid && a.s < b.s;
    }

    @Override
    public String toString() {
        return "WatchEvent[gid=" + gidUnsigned() + ", s=" + s + ", changes=" + changes.size() + "]";
    }
}
