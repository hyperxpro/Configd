package io.configd.raft;

import io.configd.common.NodeId;

/**
 * Leadership transfer message (Raft §3.10).
 * <p>
 * The leader sends this to a target follower after the target's log
 * is caught up. The target immediately starts an election (bypassing
 * the election timeout and PreVote) to take over leadership.
 *
 * @param term     leader's current term
 * @param leaderId the leader initiating the transfer
 */
public record TimeoutNowRequest(long term, NodeId leaderId) implements RaftMessage {
}
