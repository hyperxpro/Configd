package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Arrays;
import java.util.Objects;

/**
 * Raft InstallSnapshot RPC request (Raft §7).
 * <p>
 * Sent by the leader to followers that are too far behind to catch up
 * via AppendEntries (i.e., the leader has already compacted the log
 * entries the follower needs). The follower replaces its state machine
 * state with the snapshot and resets its log.
 * <p>
 * In this implementation, the entire snapshot is sent in a single
 * message ({@code offset} is always 0 and {@code done} is always true).
 * Chunked transfer can be added later for large snapshots.
 *
 * @param term              leader's current term
 * @param leaderId          leader sending the snapshot (so follower can redirect clients)
 * @param lastIncludedIndex the snapshot replaces all entries up through and including this index
 * @param lastIncludedTerm  term of {@code lastIncludedIndex}
 * @param offset            byte offset within the snapshot data (0 for single-chunk transfer)
 * @param data              raw snapshot bytes
 * @param done              true if this is the last (or only) chunk
 * @param clusterConfigData serialized cluster config at snapshot point (may be null)
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

    /**
     * Convenience constructor without cluster config (backward compatibility).
     */
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
