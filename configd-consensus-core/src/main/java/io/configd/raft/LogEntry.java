package io.configd.raft;

import java.util.Arrays;

/**
 * A single entry in the Raft log.
 *
 * @param index   1-based log position (monotonically increasing)
 * @param term    the leader term in which this entry was created
 * @param command opaque command bytes to be applied to the state machine;
 *                may be empty for no-op entries used during leader election
 */
public record LogEntry(long index, long term, byte[] command) {

    public LogEntry {
        if (index < 1) {
            throw new IllegalArgumentException("index must be >= 1: " + index);
        }
        if (term < 0) {
            throw new IllegalArgumentException("term must be non-negative: " + term);
        }
        if (command == null) {
            command = new byte[0];
        }
    }

    /**
     * Convenience factory for no-op entries (used by leader to commit
     * entries from prior terms per Raft §5.4.2).
     */
    public static LogEntry noop(long index, long term) {
        return new LogEntry(index, term, new byte[0]);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LogEntry that
                && this.index == that.index
                && this.term == that.term
                && Arrays.equals(this.command, that.command);
    }

    @Override
    public int hashCode() {
        int h = Long.hashCode(index);
        h = 31 * h + Long.hashCode(term);
        h = 31 * h + Arrays.hashCode(command);
        return h;
    }

    @Override
    public String toString() {
        return "LogEntry[index=" + index + ", term=" + term + ", cmdLen=" + command.length + "]";
    }
}
