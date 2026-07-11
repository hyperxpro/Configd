package io.configd.client.edge;

import java.util.List;
import java.util.Objects;

/**
 * One delivered watch event — the matching changes of exactly one shard-commit, tagged with its
 * {@code (gid, S)}. {@code s} is the shard's applied-mutation sequence for this commit (the cursor
 * advance); {@code commitTs} is the leader commit wall-clock — <b>freshness only, never a cursor</b>.
 *
 * <p><b>Ordering (read this).</b> The only order this stream asserts is <b>per-key / per-shard</b>: two events
 * are ORDERED iff they carry the same {@code gid} (then ascending {@code s}); two events with different
 * {@code gid} are <b>CONCURRENT — no order in either direction, ever</b>. Two events for the same key
 * are always same-{@code gid} (a key maps to one shard for the cluster's life), so per-key order always holds.
 * The cross-{@code gid} interleaving the publisher emits is an <b>arbitrary, non-normative UNION merge</b>
 * — a consumer MUST NOT infer a global/cross-shard order from arrival sequence, from {@code s}
 * magnitude across {@code gid}s, or from {@code commitTs}. Use {@link #ordered(WatchEvent, WatchEvent)}.
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

    /** The {@code gid} as an unsigned value (the raw {@code uint32} widened). */
    public long gidUnsigned() {
        return Integer.toUnsignedLong(gid);
    }

    /**
     * The <b>only</b> order relation this stream asserts: {@code true} iff {@code a} precedes {@code b}
     * — same {@code gid} and {@code a.s < b.s}. Two events with different {@code gid} are CONCURRENT, so this
     * returns {@code false} in both directions for them (they are never ordered). Transitive within a gid.
     */
    public static boolean ordered(WatchEvent a, WatchEvent b) {
        return a.gid == b.gid && a.s < b.s;
    }

    @Override
    public String toString() {
        return "WatchEvent[gid=" + gidUnsigned() + ", s=" + s + ", changes=" + changes.size() + "]";
    }
}
