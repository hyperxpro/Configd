package io.configd.raft;

import io.configd.common.NodeId;

public record RequestVoteResponse(
        long term,
        boolean voteGranted,
        NodeId from,
        boolean preVote
) implements RaftMessage {
}
