package io.configd.raft;

import io.configd.common.NodeId;

/**
 * Raft RequestVote RPC request (Raft §5.2), extended with PreVote (§9.6).
 * <p>
 * When {@code preVote} is true, this is a PreVote request: the candidate
 * does not increment its term. Peers respond based on whether they
 * <em>would</em> grant a vote, without actually recording a vote.
 * This prevents term inflation from partitioned nodes.
 *
 * @param term         candidate's term (or current term for PreVote)
 * @param candidateId  candidate requesting the vote
 * @param lastLogIndex index of candidate's last log entry
 * @param lastLogTerm  term of candidate's last log entry
 * @param preVote      true if this is a PreVote (§9.6)
 */
public record RequestVoteRequest(
        long term,
        NodeId candidateId,
        long lastLogIndex,
        long lastLogTerm,
        boolean preVote
) implements RaftMessage {
}
