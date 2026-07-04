package io.configd.distribution.wire;

import java.util.List;
import java.util.Objects;

/**
 * The watch resume position: a {@code topologyEpoch} bound to a <b>per-shard cursor vector</b>
 * (W3-1 / W3-5, A4). The vector is an ordered list of {@code (gid, S)} components, where {@code gid}
 * is a shard group id (a {@code uint32} compared and ordered <b>UNSIGNED</b>) and {@code S} is the
 * applied-mutation sequence of that shard the client has already processed.
 *
 * <p><b>Topology-epoch binding (A4).</b> Every resume token carries the {@code topologyEpoch} of the
 * topology generation that minted it (the server's {@code ShardMap.epoch()}, sourced from the
 * authenticated {@code topology-descriptor.dat}; v1 = {@link #INITIAL_TOPOLOGY_EPOCH}). The server
 * refuses a cursor whose epoch does not match its current epoch with
 * {@link ErrorCode#STALE_TOPOLOGY} - the whole cursor generation is invalid and the client must drop
 * it and fully re-hydrate (the etcd {@code ErrCompacted} model). Epoch {@code 0}
 * ({@link #EPOCH_UNSET}) is reserved-illegal and decodes as {@link ErrorCode#FRAME_CORRUPT}. At v1
 * static-N (one deploy-time epoch) the epoch is always {@link #INITIAL_TOPOLOGY_EPOCH}, so the check
 * never fires - behavior is byte-identical.
 *
 * <p><b>Vector-native always - there is no scalar form (W1-1).</b> A driver MUST treat the
 * resume token as a vector even at {@code N = 1}, where it is the one-element vector
 * {@code (gid = 0, S)}. A scalar-cursor / global-order assumption is FORBIDDEN even at
 * {@code N = 1} because it silently corrupts the moment the cluster shards. The
 * {@link #fromNow()} empty vector ({@code count == 0}) means "start at each shard's current
 * {@code S}" (W3-4) - it does <b>not</b> mean "replay all history".
 *
 * <p><b>Construction-time invariant.</b> The {@code topologyEpoch} MUST be non-zero, and components
 * MUST be strictly ascending by <b>unsigned</b> {@code gid}; a zero epoch, duplicate, or out-of-order
 * {@code gid} is rejected ({@link IllegalArgumentException}). The codec relies on this so a malformed
 * cursor on the wire decodes as {@link ErrorCode#FRAME_CORRUPT}. The record is immutable and
 * value-equal (two cursors with the same vector but different epochs are NOT equal - they are
 * different topology generations).
 *
 * <p>The wire encoding (shared with the list continuation cursor, W3-5) is
 * {@code [ topologyEpoch:u64 ][ count:u32 ]( gid:u32  S:u64 )*count}, the vector ordered by
 * {@code gid} unsigned ascending. It is produced/consumed by {@link EdgeFrameCodec}'s shared cursor
 * helper, not here, so the single golden-pinned wire-format implementation stays in the codec.
 */
public record WatchCursor(long topologyEpoch, List<Component> components) {

    /**
     * The v1 static-N topology epoch. The authoritative source is the server's
     * {@code ShardMap.epoch()} (the {@code topology-descriptor.dat} value); this is the wire-layer
     * mirror of {@code TopologyDescriptor.INITIAL_EPOCH} (distribution-service does not depend on
     * consensus-core). The convenience factories that omit an epoch stamp this value - correct for a
     * v1 static-N deployment; a v2 multi-epoch caller MUST use the epoch-taking overloads.
     */
    public static final long INITIAL_TOPOLOGY_EPOCH = 1L;

    /** Reserved-illegal epoch ("unset / pre-epoch"); rejected on construction and on decode. */
    public static final long EPOCH_UNSET = 0L;

    /**
     * A cursor over {@code components} at the v1 static topology epoch
     * ({@link #INITIAL_TOPOLOGY_EPOCH}). Convenience for the common single-epoch case (tests /
     * static-N tooling); a v2 multi-epoch caller MUST use the canonical constructor with the live epoch.
     */
    public WatchCursor(List<Component> components) {
        this(INITIAL_TOPOLOGY_EPOCH, components);
    }

    public WatchCursor {
        if (topologyEpoch <= EPOCH_UNSET) {
            throw new IllegalArgumentException(
                    "cursor topologyEpoch must be in [1, 2^63) (0 is reserved-illegal): " + topologyEpoch);
        }
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

    /**
     * The "from now per shard" cursor at the v1 static topology epoch - an empty vector
     * ({@code count == 0}; W3-4). See {@link #INITIAL_TOPOLOGY_EPOCH} on the epoch default.
     */
    public static WatchCursor fromNow() {
        return fromNow(INITIAL_TOPOLOGY_EPOCH);
    }

    /** The "from now per shard" cursor bound to {@code topologyEpoch} (W3-4). */
    public static WatchCursor fromNow(long topologyEpoch) {
        return new WatchCursor(topologyEpoch, List.of());
    }

    /**
     * A single-component cursor at the v1 static topology epoch - the {@code N = 1} form is
     * {@code of(0, S)} (W3-5). See {@link #INITIAL_TOPOLOGY_EPOCH} on the epoch default.
     */
    public static WatchCursor of(int gid, long s) {
        return of(INITIAL_TOPOLOGY_EPOCH, gid, s);
    }

    /** A single-component cursor {@code (gid, S)} bound to {@code topologyEpoch} (W3-5). */
    public static WatchCursor of(long topologyEpoch, int gid, long s) {
        return new WatchCursor(topologyEpoch, List.of(new Component(gid, s)));
    }

    /** True iff this is the empty "from now per shard" cursor (W3-4); independent of the epoch. */
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
