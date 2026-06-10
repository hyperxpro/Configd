package io.configd.raft;

/**
 * The outcome of a {@link RaftNode#propose(byte[])} attempt.
 * <p>
 * RR-004 / ADR-0033: {@code propose} no longer returns a bare
 * {@link ProposalResult}. On acceptance it now also carries the assigned log
 * position {@code (index, term)} so the caller can register a commit-outcome
 * callback ({@link RaftNode#whenCommitOutcome}) against the exact entry it just
 * appended. The rejection reasons are unchanged — {@link #result} still carries
 * the {@link ProposalResult} ({@code NOT_LEADER}, {@code TRANSFER_IN_PROGRESS},
 * {@code OVERLOADED}); for those, {@code index} and {@code term} are {@code -1}.
 *
 * @param result the proposal result (acceptance or a rejection reason)
 * @param index  the assigned log index when {@code result == ACCEPTED}, else {@code -1}
 * @param term   the assigned term when {@code result == ACCEPTED}, else {@code -1}
 */
public record ProposeOutcome(ProposalResult result, long index, long term) {

    /** Position sentinel used for every non-accepted outcome. */
    public static final long NO_POSITION = -1L;

    /** Builds an accepted outcome carrying the assigned log position. */
    public static ProposeOutcome accepted(long index, long term) {
        return new ProposeOutcome(ProposalResult.ACCEPTED, index, term);
    }

    /** Builds a rejected outcome (no log position) for the given reason. */
    public static ProposeOutcome rejected(ProposalResult reason) {
        if (reason == ProposalResult.ACCEPTED) {
            throw new IllegalArgumentException("rejected() requires a rejection reason, not ACCEPTED");
        }
        return new ProposeOutcome(reason, NO_POSITION, NO_POSITION);
    }

    /** True iff the proposal was accepted and appended to the leader's log. */
    public boolean accepted() {
        return result == ProposalResult.ACCEPTED;
    }
}
