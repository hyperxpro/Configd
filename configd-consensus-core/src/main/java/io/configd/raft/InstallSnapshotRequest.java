package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Arrays;
import java.util.Objects;

/**
 * Raft InstallSnapshot RPC request (Raft section 7).
 * <p>
 * Sent by the leader to followers that are too far behind to catch up
 * via AppendEntries (i.e., the leader has already compacted the log
 * entries the follower needs). The follower replaces its state machine
 * state with the snapshot and resets its log.
 * <p>
 * Large snapshots are sent as an ordered stream of chunks: {@code offset} is the byte offset of
 * this chunk's {@code data} within the whole snapshot and {@code done} marks the final chunk. A
 * snapshot that fits one chunk is a single message with {@code offset == 0} and {@code done ==
 * true}. The cluster config rides the final chunk (the receiver needs it only at install time), so
 * {@code clusterConfigData} is non-null only on the {@code done} chunk of a multi-chunk transfer.
 *
 * @param term              leader's current term
 * @param leaderId          leader sending the snapshot (so follower can redirect clients)
 * @param lastIncludedIndex the snapshot replaces all entries up through and including this index
 * @param lastIncludedTerm  term of {@code lastIncludedIndex}
 * @param offset            byte offset of this chunk's {@code data} within the whole snapshot
 * @param data              this chunk's raw snapshot bytes
 * @param done              true if this is the last (or only) chunk
 * @param clusterConfigData serialized cluster config at snapshot point, carried on the final
 *                          chunk (may be null)
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
