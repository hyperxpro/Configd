package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Objects;

/**
 * Raft InstallSnapshot RPC response (Raft §7).
 * nextExpectedOffset: follower's GROUND TRUTH reassembly position (not ack count);
 * survives lossy/reordered/restart because restarted follower reports 0 (re-send from beginning).
 */
public record InstallSnapshotResponse(
        long term,
        boolean success,
        NodeId from,
        long lastIncludedIndex,
        int nextExpectedOffset
) implements RaftMessage {

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
