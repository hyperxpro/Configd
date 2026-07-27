package io.configd.raft;

import io.configd.common.NodeId;

/**
 * Point-in-time snapshot of Raft node state (via RaftNode.metrics() from I/O thread).
 * Rejection counters: appendSendRejected/snapshotChunkSendRejected (wire codec), snapshotReassemblyRefused (heap cap).
 */
public record RaftMetrics(
        NodeId nodeId,
        RaftRole role,
        long currentTerm,
        NodeId leaderId,
        long commitIndex,
        long lastApplied,
        long lastLogIndex,
        long snapshotIndex,
        int logSize,
        int replicationLagMax,
        long appendSendRejected,
        long snapshotChunkSendRejected,
        long snapshotReassemblyRefused
) {
}
