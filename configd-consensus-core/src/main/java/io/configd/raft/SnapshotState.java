package io.configd.raft;

import java.util.Arrays;
import java.util.Objects;

/**
 * Snapshot state for transfer to lagging followers. Includes cluster config so fully compacted logs
 * can recover config (prevent silent revert to initial static configuration).
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

    public SnapshotState(byte[] data, long lastIncludedIndex, long lastIncludedTerm) {
        this(data, lastIncludedIndex, lastIncludedTerm, null);
    }

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
