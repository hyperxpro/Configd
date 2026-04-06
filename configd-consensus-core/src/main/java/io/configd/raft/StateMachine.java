package io.configd.raft;

/**
 * Application-level state machine driven by committed Raft log entries.
 * <p>
 * Implementations must be deterministic: the same sequence of
 * {@link #apply} calls must produce the same state on every node.
 */
public interface StateMachine {

    /**
     * Applies a committed log entry to the state machine.
     *
     * @param index   the log index of the committed entry
     * @param term    the term of the committed entry
     * @param command the opaque command bytes (may be empty for no-op entries)
     */
    void apply(long index, long term, byte[] command);

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
