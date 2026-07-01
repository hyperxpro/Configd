package io.configd.raft;

/**
 * The outcome of a proposed entry, delivered to a
 * {@link RaftNode#whenCommitOutcome(long, long, java.util.function.Consumer)}
 * callback exactly once.
 * <p>
 * The predicates are deliberately conservative - only Raft-permanent facts are
 * reported as definite outcomes:
 * <ul>
 *   <li>{@link Kind#COMMITTED} - {@code lastApplied >= index} and the applied
 *       entry at {@code index} carries the proposed {@code term}. By Raft Log
 *       Matching (section 5.3), {@code (index, term)} uniquely identifies the entry's
 *       content, so this is <em>this</em> proposal, now committed and applied.
 *       Carries {@code seq}, the applied-mutation sequence the state machine
 *       assigned to this entry (the client's read cursor).</li>
 *   <li>{@link Kind#LOST} - {@code lastApplied >= index} and the applied entry at
 *       {@code index} carries a <em>different</em> term. Log Matching makes that
 *       slot permanent on every replica, so this proposal can never commit as
 *       this proposal. This is the ONLY definite-loss trigger. (Truncation
 *       without a replacement applied, and step-down, are NOT definitive - a
 *       replica still holding the entry can win a later election and commit it;
 *       those resolve on a later apply at {@code index} or surface as
 *       Indeterminate at the service deadline.)</li>
 *   <li>{@link Kind#INDETERMINATE_LOCALLY}  -  an {@code InstallSnapshot} covering
 *       {@code index} arrived on a node whose {@code lastApplied} had not reached
 *       {@code index}: the per-index term is unrecoverable from a snapshot, so
 *       this node can no longer decide the outcome locally. The service maps this
 *       to Indeterminate.</li>
 * </ul>
 */
public record CommitOutcome(Kind kind, long seq) {

    public enum Kind { COMMITTED, LOST, INDETERMINATE_LOCALLY }

    /** Sequence sentinel for non-committed outcomes (no applied-mutation seq exists). */
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
