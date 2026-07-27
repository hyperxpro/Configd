package io.configd.raft;

/**
 * Outcome of a proposed entry (exactly once callback delivery).
 * Only Raft-permanent facts are definite:
 * COMMITTED: lastApplied >= index with matching term (Raft §5.3 Log Matching); carries applied mutation seq.
 * LOST: lastApplied >= index with different term (slot permanent on all replicas).
 * INDETERMINATE_LOCALLY: InstallSnapshot arrived before lastApplied reached index (term unrecoverable).
 */
public record CommitOutcome(Kind kind, long seq) {

    public enum Kind { COMMITTED, LOST, INDETERMINATE_LOCALLY }

    public static final long NO_SEQ = -1L;

    public static CommitOutcome committed(long seq) {
        return new CommitOutcome(Kind.COMMITTED, seq);
    }

    public static CommitOutcome lost() {
        return new CommitOutcome(Kind.LOST, NO_SEQ);
    }

    public static CommitOutcome indeterminateLocally() {
        return new CommitOutcome(Kind.INDETERMINATE_LOCALLY, NO_SEQ);
    }
}
