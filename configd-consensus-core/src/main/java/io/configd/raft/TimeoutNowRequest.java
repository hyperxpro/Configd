package io.configd.raft;

import io.configd.common.NodeId;

/**
 * Leadership transfer message (Raft §3.10). Sent after target's log caught up;
 * target immediately starts election (bypass election timeout and PreVote).
 */
public record TimeoutNowRequest(long term, NodeId leaderId) implements RaftMessage {
}
