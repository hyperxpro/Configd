package io.configd.raft;

import io.configd.common.NodeId;

/**
 * Raft AppendEntries RPC response (Raft §5.3).
 *
 * @param term       current term of the responding node, for leader to update itself
 * @param success    true if follower contained entry matching prevLogIndex and prevLogTerm
 * @param matchIndex the index of the last entry the follower has replicated (used by leader
 *                   to advance matchIndex/nextIndex tracking)
 * @param from       the node sending this response
 */
public record AppendEntriesResponse(
        long term,
        boolean success,
        long matchIndex,
        NodeId from
) implements RaftMessage {
}
