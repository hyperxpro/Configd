package io.configd.raft;

/**
 * Application state machine driven by committed Raft log entries (must be deterministic).
 */
public interface StateMachine {

    long NON_MUTATING = -1L;

    /**
     * Applies committed entry, returns applied-mutation sequence (gap-free counter for read-your-writes).
     * Return NON_MUTATING (-1) for non-mutating entries (no-op, empty command); caller surfaces current seq.
     */
    long apply(long index, long term, byte[] command);

    byte[] snapshot();

    /**
     * Restore from snapshot bytes produced by snapshot().
     */
    void restoreSnapshot(byte[] snapshot);
}
