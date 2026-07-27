package io.configd.raft;

/**
 * Proposal outcome: result (acceptance or rejection reason), and (index, term) if accepted
 * (for registering commit-outcome callbacks). On rejection, index/term are -1.
 */
public record ProposeOutcome(ProposalResult result, long index, long term) {

    public static final long NO_POSITION = -1L;

    public static ProposeOutcome accepted(long index, long term) {
        return new ProposeOutcome(ProposalResult.ACCEPTED, index, term);
    }

    public static ProposeOutcome rejected(ProposalResult reason) {
        if (reason == ProposalResult.ACCEPTED) {
            throw new IllegalArgumentException("rejected() requires a rejection reason, not ACCEPTED");
        }
        return new ProposeOutcome(reason, NO_POSITION, NO_POSITION);
    }

    public boolean accepted() {
        return result == ProposalResult.ACCEPTED;
    }
}
