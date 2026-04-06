package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Objects;

/**
 * Raft InstallSnapshot RPC response (Raft §7).
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
 */
public record InstallSnapshotResponse(
        long term,
        boolean success,
        NodeId from,
        long lastIncludedIndex
) implements RaftMessage {

    public InstallSnapshotResponse {
        Objects.requireNonNull(from, "from");
        if (lastIncludedIndex < 0) {
            throw new IllegalArgumentException(
                    "lastIncludedIndex must be non-negative: " + lastIncludedIndex);
        }
    }
}
