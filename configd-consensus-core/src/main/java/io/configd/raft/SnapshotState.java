package io.configd.raft;

import java.util.Arrays;
import java.util.Objects;

/**
 * Tracks the state of a snapshot available for transfer to lagging followers.
 * <p>
 * Created when the leader takes a snapshot via {@link StateMachine#snapshot()}.
 * Contains the serialized state machine data, the log position at which
 * the snapshot was taken, and the cluster configuration at that point.
 * <p>
 * The cluster config is included so that after log compaction (where config
 * entries may be discarded), the node can still recover its cluster config.
 * Without this, a node that restarts with a fully compacted log would
 * silently revert to the initial static configuration.
 * <p>
 * This implementation uses a single contiguous byte array. For large
 * snapshots, chunked transfer can be layered on top by reading slices
 * from {@link #data()} at the appropriate offset.
 *
 * @param data              the serialized snapshot bytes from the state machine
 * @param lastIncludedIndex the index of the last log entry included in this snapshot
 * @param lastIncludedTerm  the term of the last log entry included in this snapshot
 * @param clusterConfigData serialized cluster config at snapshot point (may be null
 *                          for snapshots taken before this field was added)
 */
public record SnapshotState(
        byte[] data,
        long lastIncludedIndex,
        long lastIncludedTerm,
        byte[] clusterConfigData
) {

    public SnapshotState {
        Objects.requireNonNull(data, "data");
        if (lastIncludedIndex < 0) {
            throw new IllegalArgumentException("lastIncludedIndex must be >= 0: " + lastIncludedIndex);
        }
        if (lastIncludedTerm < 0) {
            throw new IllegalArgumentException("lastIncludedTerm must be >= 0: " + lastIncludedTerm);
        }
    }

    /**
     * Convenience constructor for snapshots without cluster config metadata.
     * Used in tests and for backward compatibility.
     */
    public SnapshotState(byte[] data, long lastIncludedIndex, long lastIncludedTerm) {
        this(data, lastIncludedIndex, lastIncludedTerm, null);
    }

    /**
     * Returns the total size of the snapshot data in bytes.
     */
    public int size() {
        return data.length;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SnapshotState that
                && this.lastIncludedIndex == that.lastIncludedIndex
                && this.lastIncludedTerm == that.lastIncludedTerm
                && Arrays.equals(this.data, that.data)
                && Arrays.equals(this.clusterConfigData, that.clusterConfigData);
    }

    @Override
    public int hashCode() {
        int h = Arrays.hashCode(data);
        h = 31 * h + Long.hashCode(lastIncludedIndex);
        h = 31 * h + Long.hashCode(lastIncludedTerm);
        h = 31 * h + Arrays.hashCode(clusterConfigData);
        return h;
    }

    @Override
    public String toString() {
        return "SnapshotState[lastIncludedIndex=" + lastIncludedIndex
                + ", lastIncludedTerm=" + lastIncludedTerm
                + ", dataLen=" + data.length
                + ", hasConfig=" + (clusterConfigData != null) + "]";
    }
}
