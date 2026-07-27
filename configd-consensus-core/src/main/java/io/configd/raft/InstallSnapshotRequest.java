package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Arrays;
import java.util.Objects;

/**
 * Raft InstallSnapshot RPC request (Raft §7). Large snapshots chunked:
 * offset=byte offset, done=final chunk. Cluster config rides final chunk only.
 */
public record InstallSnapshotRequest(
        long term,
        NodeId leaderId,
        long lastIncludedIndex,
        long lastIncludedTerm,
        int offset,
        byte[] data,
        boolean done,
        byte[] clusterConfigData
) implements RaftMessage {

    public InstallSnapshotRequest(
            long term, NodeId leaderId, long lastIncludedIndex, long lastIncludedTerm,
            int offset, byte[] data, boolean done) {
        this(term, leaderId, lastIncludedIndex, lastIncludedTerm, offset, data, done, null);
    }

    public InstallSnapshotRequest {
        Objects.requireNonNull(leaderId, "leaderId");
        if (data == null) {
            data = new byte[0];
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof InstallSnapshotRequest that
                && this.term == that.term
                && this.leaderId.equals(that.leaderId)
                && this.lastIncludedIndex == that.lastIncludedIndex
                && this.lastIncludedTerm == that.lastIncludedTerm
                && this.offset == that.offset
                && Arrays.equals(this.data, that.data)
                && this.done == that.done
                && Arrays.equals(this.clusterConfigData, that.clusterConfigData);
    }

    @Override
    public int hashCode() {
        int h = Long.hashCode(term);
        h = 31 * h + leaderId.hashCode();
        h = 31 * h + Long.hashCode(lastIncludedIndex);
        h = 31 * h + Long.hashCode(lastIncludedTerm);
        h = 31 * h + offset;
        h = 31 * h + Arrays.hashCode(data);
        h = 31 * h + Boolean.hashCode(done);
        h = 31 * h + Arrays.hashCode(clusterConfigData);
        return h;
    }

    @Override
    public String toString() {
        return "InstallSnapshotRequest[term=" + term
                + ", leaderId=" + leaderId
                + ", lastIncludedIndex=" + lastIncludedIndex
                + ", lastIncludedTerm=" + lastIncludedTerm
                + ", offset=" + offset
                + ", dataLen=" + data.length
                + ", done=" + done
                + ", hasConfig=" + (clusterConfigData != null) + "]";
    }
}
