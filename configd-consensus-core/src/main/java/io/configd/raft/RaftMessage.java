package io.configd.raft;

/**
 * Sealed interface for all Raft protocol messages.
 * <p>
 * Using a sealed hierarchy instead of {@code Object} allows the JIT to
 * prove a closed type set, enabling devirtualization and eliminating
 * megamorphic call sites on the Raft I/O thread hot path (AV-4 fix).
 * <p>
 * Every message exchanged between Raft peers must implement this interface.
 */
public sealed interface RaftMessage
        permits AppendEntriesRequest, AppendEntriesResponse,
                RequestVoteRequest, RequestVoteResponse,
                TimeoutNowRequest,
                InstallSnapshotRequest, InstallSnapshotResponse {
}
