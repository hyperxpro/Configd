package io.configd.raft;

/**
 * Application-level state machine driven by committed Raft log entries.
 * <p>
 * Implementations must be deterministic: the same sequence of
 * {@link #apply} calls must produce the same state on every node.
 */
public interface StateMachine {

    /**
     * Sentinel returned by {@link #apply} for a non-mutating entry (no-op, or a
     * command that does not advance the applied-mutation sequence). A caller
     * surfacing a commit sequence for a non-mutating entry should report the
     * current sequence (any S &le; current version satisfies read-your-writes for
     * a no-op).
     */
    long NON_MUTATING = -1L;

    /**
     * Applies a committed log entry to the state machine.
     * <p>
     * Returns the <b>applied-mutation sequence</b> assigned to
     * this entry - the per-group, gap-free-over-mutations counter that the read
     * path serves as the version cursor (contract section 6 read-your-writes). For
     * non-mutating entries (no-op election entries, empty commands) the
     * implementation returns {@link #NON_MUTATING} ({@code -1}); the caller then
     * surfaces the current sequence. This return value lets the commit-outcome
     * seam report the correct per-{@code index} commit sequence to the write
     * client without exposing a separate {@code (index -> seq)} map.
     *
     * @param index   the log index of the committed entry
     * @param term    the term of the committed entry
     * @param command the opaque command bytes (may be empty for no-op entries)
     * @return the applied-mutation sequence assigned to this entry, or
     *         {@link #NON_MUTATING} for a non-mutating apply
     */
    long apply(long index, long term, byte[] command);

    /**
     * Serializes the current state machine state for snapshot transfer.
     *
     * @return serialized snapshot bytes
     */
    byte[] snapshot();

    /**
     * Restores the state machine from a previously taken snapshot.
     *
     * @param snapshot serialized snapshot bytes produced by {@link #snapshot()}
     */
    void restoreSnapshot(byte[] snapshot);
}
