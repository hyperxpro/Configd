package io.configd.distribution.wire;

import java.util.List;
import java.util.Objects;

/**
 * The watch resume position: a <b>per-shard cursor vector</b> (W3-1 / W3-5). An ordered list
 * of {@code (gid, S)} components, where {@code gid} is a shard group id (a {@code uint32}
 * compared and ordered <b>UNSIGNED</b>) and {@code S} is the applied-mutation sequence of
 * that shard the client has already processed.
 *
 * <p><b>Vector-native always - there is no scalar form (W1-1).</b> A driver MUST treat the
 * resume token as a vector even at {@code N = 1}, where it is the one-element vector
 * {@code (gid = 0, S)}. A scalar-cursor / global-order assumption is FORBIDDEN even at
 * {@code N = 1} because it silently corrupts the moment the cluster shards. The
 * {@link #fromNow()} empty vector ({@code count == 0}) means "start at each shard's current
 * {@code S}" (W3-4) - it does <b>not</b> mean "replay all history".
 *
 * <p><b>Construction-time invariant.</b> Components MUST be strictly ascending by
 * <b>unsigned</b> {@code gid}; a duplicate or out-of-order {@code gid} is rejected
 * ({@link IllegalArgumentException}). The codec relies on this so a malformed cursor on the
 * wire decodes as {@link ErrorCode#FRAME_CORRUPT}. The record is immutable and value-equal.
 *
 * <p>The wire encoding (shared with the list continuation cursor, W3-5) is
 * {@code [ count:u32 ]( gid:u32  S:u64 )*count}, ordered by {@code gid} unsigned ascending.
 * It is produced/consumed by {@link EdgeFrameCodec}'s shared cursor helper, not here, so the
 * single golden-pinned wire-format implementation stays in the codec.
 */
public record WatchCursor(List<Component> components) {

    public WatchCursor {
        Objects.requireNonNull(components, "components must not be null");
        components = List.copyOf(components); // immutable; rejects null elements
        for (int i = 1; i < components.size(); i++) {
            int prev = components.get(i - 1).gid();
            int cur = components.get(i).gid();
            if (Integer.compareUnsigned(cur, prev) <= 0) {
                throw new IllegalArgumentException(
                        "cursor components must be strictly ascending by UNSIGNED gid (no duplicates): gid["
                                + (i - 1) + "]=" + Integer.toUnsignedLong(prev) + ", gid[" + i + "]="
                                + Integer.toUnsignedLong(cur));
            }
        }
    }

    /** The "from now per shard" cursor - an empty vector ({@code count == 0}; W3-4). */
    public static WatchCursor fromNow() {
        return new WatchCursor(List.of());
    }

    /** A single-component cursor - the {@code N = 1} form is {@code of(0, S)} (W3-5). */
    public static WatchCursor of(int gid, long s) {
        return new WatchCursor(List.of(new Component(gid, s)));
    }

    /** True iff this is the empty "from now per shard" cursor (W3-4). */
    public boolean isFromNow() {
        return components.isEmpty();
    }

    /**
     * One {@code (gid, S)} cursor component.
     *
     * @param gid the shard group id - a {@code uint32}; the raw 32 bits are stored in this
     *            {@code int} and MUST be compared/ordered as <b>unsigned</b>
     *            ({@link #gidUnsigned()}). Opaque full {@code uint32} range (not range-checked).
     * @param s   the applied-mutation sequence {@code S} already processed (the per-shard
     *            analogue of etcd's revision, W3-2). <b>Validated non-negative</b>, so its
     *            effective range is {@code [0, 2^63)} even though the wire field is a
     *            {@code uint64}: a high-bit-set value decodes as {@link ErrorCode#FRAME_CORRUPT}.
     *            A cross-language (Rust/Go) driver using a true {@code u64} MUST keep {@code S}
     *            (and the other sequence/timestamp fields) within {@code [0, 2^63)}.
     */
    public record Component(int gid, long s) {
        public Component {
            if (s < 0) {
                throw new IllegalArgumentException("cursor S must be non-negative (u64 applied-seq): " + s);
            }
        }

        /** The {@code gid} as an unsigned value (the raw {@code uint32} widened to a long). */
        public long gidUnsigned() {
            return Integer.toUnsignedLong(gid);
        }
    }
}
