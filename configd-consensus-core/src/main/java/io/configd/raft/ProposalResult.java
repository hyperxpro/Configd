package io.configd.raft;

/**
 * Result of a proposal attempt. Replaces the boolean return from
 * {@link RaftNode#propose(byte[])} to provide actionable rejection reasons.
 * Clients should retry on OVERLOADED after backoff.
 */
public enum ProposalResult {
    /** Proposal accepted and appended to the leader's log. */
    ACCEPTED,
    /** This node is not the leader. Redirect to {@link RaftNode#leaderId()}. */
    NOT_LEADER,
    /** A leadership transfer is in progress. Retry after transfer completes. */
    TRANSFER_IN_PROGRESS,
    /** Too many uncommitted entries. Apply backpressure — retry after backoff. */
    OVERLOADED
}
