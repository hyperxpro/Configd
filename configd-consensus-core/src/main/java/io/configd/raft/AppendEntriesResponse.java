package io.configd.raft;

import io.configd.common.NodeId;

public record AppendEntriesResponse(
        long term,
        boolean success,
        long matchIndex,
        NodeId from
) implements RaftMessage {
}
