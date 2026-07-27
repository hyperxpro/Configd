package io.configd.raft;

import io.configd.common.NodeId;

import java.util.List;

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
