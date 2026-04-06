package io.configd.raft;

import io.configd.common.NodeId;

/**
 * Raft RequestVote RPC response (Raft §5.2), extended with PreVote (§9.6).
 *
 * @param term         current term of the responding node
 * @param voteGranted  true if the vote (or pre-vote) was granted
 * @param from         the node sending this response
 * @param preVote      true if this is a response to a PreVote request
 */
public record RequestVoteResponse(
        long term,
        boolean voteGranted,
        NodeId from,
        boolean preVote
) implements RaftMessage {
}
