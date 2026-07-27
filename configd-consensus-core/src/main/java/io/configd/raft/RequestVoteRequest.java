package io.configd.raft;

import io.configd.common.NodeId;

/**
 * Raft RequestVote RPC request (Raft §5.2) extended with PreVote (§9.6).
 * PreVote: candidate doesn't increment term; peers respond hypothetically without recording vote
 * (prevents term inflation from partitioned nodes).
 */
public record RequestVoteRequest(
        long term,
        NodeId candidateId,
        long lastLogIndex,
        long lastLogTerm,
        boolean preVote
) implements RaftMessage {
}
