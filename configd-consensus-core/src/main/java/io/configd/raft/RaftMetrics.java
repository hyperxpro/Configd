package io.configd.raft;

import io.configd.common.NodeId;

/**
 * Immutable snapshot of Raft node state for monitoring and diagnostics.
 * <p>
 * Captured via {@link RaftNode#metrics()} at a single point in time
 * from the Raft I/O thread. All fields reflect the node's state at
 * the moment of capture.
 *
 * @param nodeId           this node's identifier
 * @param role             current role (FOLLOWER, CANDIDATE, or LEADER)
 * @param currentTerm      the latest term this node has seen
 * @param leaderId         the known leader (null if unknown)
 * @param commitIndex      highest log index known to be committed
 * @param lastApplied      highest log index applied to the state machine
 * @param lastLogIndex     index of the last log entry
 * @param snapshotIndex    index of the last entry included in the most recent snapshot
 * @param logSize          number of entries currently stored in memory (excludes compacted)
 * @param replicationLagMax maximum replication lag across all peers (0 for non-leaders)
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
        int replicationLagMax
) {
}
