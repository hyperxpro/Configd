package io.configd.raft;

import io.configd.common.NodeId;

/**
 * Raft RequestVote RPC response (Raft §5.2) extended with PreVote (§9.6).
 */
public record RequestVoteResponse(
        long term,
        boolean voteGranted,
        NodeId from,
        boolean preVote
) implements RaftMessage {
}
