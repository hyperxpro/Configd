package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Objects;

/**
 * Raft InstallSnapshot RPC response (Raft section 7).
 * <p>
 * Sent by a follower after processing an {@link InstallSnapshotRequest}.
 * The leader uses the response to update its view of the follower's state.
 *
 * @param term               current term of the responding node, for the
 *                           leader to update itself
 * @param success            true if the snapshot was accepted (or already
 *                           present); false on stale-term reject
 * @param from               the node sending this response
 * @param lastIncludedIndex  the responder's highest applied/snapshot index
 *                           after handling this RPC. The leader can use
 *                           this to short-circuit a stale snapshot retry by
 *                           jumping straight to {@code AppendEntries} at
 *                           {@code lastIncludedIndex + 1} instead of
 *                           re-sending the snapshot. Echoed even on reject
 *                           paths so a stale-term leader sees that the
 *                           follower is already past it.
 * @param nextExpectedOffset for a chunked transfer, the number of contiguous
 *                           snapshot bytes the follower currently holds for the
 *                           in-progress reassembly - equivalently, the offset of
 *                           the next chunk it expects. This is the follower's
 *                           GROUND TRUTH: the leader sets its per-peer send
 *                           offset to this value rather than counting acks, which
 *                           is what makes the transfer correct under a lossy
 *                           transport, chunk reorder, and follower restart (a
 *                           restarted follower reports 0, so the leader re-sends
 *                           from the beginning). It is 0 when there is no matching
 *                           in-progress reassembly (reject / already-installed /
 *                           just-installed).
 */
public record InstallSnapshotResponse(
        long term,
        boolean success,
        NodeId from,
        long lastIncludedIndex,
        int nextExpectedOffset
) implements RaftMessage {

    /**
     * Convenience constructor for responses that carry no reassembly position -
     * a stale-term reject, an already-installed ack, or a just-completed install.
     * In every such case the follower holds no in-progress partial, so
     * {@code nextExpectedOffset} is 0.
     */
    public InstallSnapshotResponse(long term, boolean success, NodeId from, long lastIncludedIndex) {
        this(term, success, from, lastIncludedIndex, 0);
    }

    public InstallSnapshotResponse {
        Objects.requireNonNull(from, "from");
        if (lastIncludedIndex < 0) {
            throw new IllegalArgumentException(
                    "lastIncludedIndex must be non-negative: " + lastIncludedIndex);
        }
        if (nextExpectedOffset < 0) {
            throw new IllegalArgumentException(
                    "nextExpectedOffset must be non-negative: " + nextExpectedOffset);
        }
    }
}
