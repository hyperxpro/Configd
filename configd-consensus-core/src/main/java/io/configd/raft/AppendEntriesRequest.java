package io.configd.raft;

import io.configd.common.NodeId;

import java.util.List;

/**
 * Raft AppendEntries RPC request (Raft §5.3).
 * <p>
 * Sent by the leader to replicate log entries and as heartbeats
 * (empty entries list).
 *
 * @param term         leader's term
 * @param leaderId     leader sending this request (so follower can redirect clients)
 * @param prevLogIndex index of log entry immediately preceding new ones
 * @param prevLogTerm  term of prevLogIndex entry
 * @param entries      log entries to store (empty for heartbeat)
 * @param leaderCommit leader's commit index
 */
public record AppendEntriesRequest(
        long term,
        NodeId leaderId,
        long prevLogIndex,
        long prevLogTerm,
        List<LogEntry> entries,
        long leaderCommit
) implements RaftMessage {
    public AppendEntriesRequest {
        entries = List.copyOf(entries);
    }
}
