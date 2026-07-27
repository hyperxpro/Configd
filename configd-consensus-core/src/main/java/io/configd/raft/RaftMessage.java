package io.configd.raft;

/**
 * Sealed interface: enables JIT devirtualization on Raft I/O thread hot path
 * (closed type set eliminates megamorphic call sites).
 */
public sealed interface RaftMessage
        permits AppendEntriesRequest, AppendEntriesResponse,
                RequestVoteRequest, RequestVoteResponse,
                TimeoutNowRequest,
                InstallSnapshotRequest, InstallSnapshotResponse,
                WitnessMessage, WitnessReply {
}
