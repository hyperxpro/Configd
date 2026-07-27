package io.configd.distribution.wire;

import java.util.List;
import java.util.Objects;

/**
 * The watch resume position: a {@code topologyEpoch} bound to a <b>per-shard cursor vector</b>
 * (W3-1 / W3-5). The vector is an ordered list of {@code (gid, S)} components, where {@code gid}
 * is a shard group id (a {@code uint32} compared and ordered <b>UNSIGNED</b>) and {@code S} is the
 * applied-mutation sequence of that shard the client has already processed.
 *
 * <p><b>Topology-epoch binding.</b> Every resume token carries the {@code topologyEpoch} of the
 * topology generation that minted it (the server's {@code ShardMap.epoch()}, sourced from the
 * authenticated {@code topology-descriptor.dat}; currently always {@link #INITIAL_TOPOLOGY_EPOCH}).
 * The server refuses a cursor whose epoch does not match its current epoch with
 * {@link ErrorCode#STALE_TOPOLOGY} - the whole cursor generation is invalid and the client must drop
 * it and fully re-hydrate (the etcd {@code ErrCompacted} model). Epoch {@code 0}
 * ({@link #EPOCH_UNSET}) is reserved-illegal and decodes as {@link ErrorCode#FRAME_CORRUPT}. At
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
     * The static-N topology epoch. The authoritative source is the server's
     * {@code ShardMap.epoch()} (the {@code topology-descriptor.dat} value); this is the wire-layer
     * mirror of {@code TopologyDescriptor.INITIAL_EPOCH} (distribution-service does not depend on
     * consensus-core). The convenience factories that omit an epoch stamp this value - correct for a
     * static-N deployment; a caller tracking multiple topology epochs MUST use the epoch-taking
     * overloads.
     */
    public static final long INITIAL_TOPOLOGY_EPOCH = 1L;

    public static final long EPOCH_UNSET = 0L;

    /**
     * Cursor at static topology epoch (INITIAL_TOPOLOGY_EPOCH). Convenience for single-epoch
     * case; caller tracking multiple epochs MUST use canonical constructor with live epoch.
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

    /** Empty "from now per shard" cursor (count==0; W3-4) at static topology epoch. */
    public static WatchCursor fromNow() {
        return fromNow(INITIAL_TOPOLOGY_EPOCH);
    }

    /** "From now per shard" cursor (W3-4) bound to topologyEpoch. */
    public static WatchCursor fromNow(long topologyEpoch) {
        return new WatchCursor(topologyEpoch, List.of());
    }

    /** Single-component cursor at static topology epoch; N=1 form is of(0, S) (W3-5). */
    public static WatchCursor of(int gid, long s) {
        return of(INITIAL_TOPOLOGY_EPOCH, gid, s);
    }

    /** Single-component cursor (gid, S) bound to topologyEpoch (W3-5). */
    public static WatchCursor of(long topologyEpoch, int gid, long s) {
        return new WatchCursor(topologyEpoch, List.of(new Component(gid, s)));
    }

    /** True iff empty "from now per shard" cursor (W3-4), independent of epoch. */
    public boolean isFromNow() {
        return components.isEmpty();
    }

    /**
     * (gid, S) cursor component. gid is uint32 stored as int, MUST be compared/ordered as
     * unsigned (see gidUnsigned()). S is applied-mutation sequence already processed
     * (per-shard etcd revision analogue, W3-2), validated non-negative: effective range [0, 2^63)
     * (high-bit-set wire value = FRAME_CORRUPT). Cross-language (Rust/Go) drivers using true
     * u64 MUST keep S and other sequence/timestamp fields within [0, 2^63).
     */
    public record Component(int gid, long s) {
        public Component {
            if (s < 0) {
                throw new IllegalArgumentException("cursor S must be non-negative (u64 applied-seq): " + s);
            }
        }

        /** gid as unsigned value (uint32 widened to long). */
        public long gidUnsigned() {
            return Integer.toUnsignedLong(gid);
        }
    }
}
