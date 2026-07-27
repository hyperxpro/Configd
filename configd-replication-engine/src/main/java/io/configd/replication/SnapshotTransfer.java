package io.configd.replication;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Manages chunked snapshot transfer to a follower that has fallen
 * too far behind for log-based catch-up.
 * <p>
 * The sender breaks the snapshot into fixed-size chunks and sends them
 * sequentially. The receiver reassembles and installs the snapshot
 * once the final chunk arrives.
 * <p>
 * Chunking is deterministic: the same snapshot data always produces
 * the same sequence of chunks, enabling idempotent retransmission.
 * <p>
 * Designed for single-threaded access from the Raft I/O thread.
 * No synchronization is used.
 *
 * @see io.configd.raft.StateMachine#snapshot()
 * @see io.configd.raft.StateMachine#restoreSnapshot(byte[])
 */
public final class SnapshotTransfer {

    public static final int CHUNK_SIZE = 64 * 1024;

    public record SnapshotChunk(
            int offset,
            byte[] data,
            boolean done,
            long lastIncludedIndex,
            long lastIncludedTerm
    ) {
        public SnapshotChunk {
            Objects.requireNonNull(data, "data");
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SnapshotChunk that
                    && this.offset == that.offset
                    && Arrays.equals(this.data, that.data)
                    && this.done == that.done
                    && this.lastIncludedIndex == that.lastIncludedIndex
                    && this.lastIncludedTerm == that.lastIncludedTerm;
        }

        @Override
        public int hashCode() {
            int h = Integer.hashCode(offset);
            h = 31 * h + Arrays.hashCode(data);
            h = 31 * h + Boolean.hashCode(done);
            h = 31 * h + Long.hashCode(lastIncludedIndex);
            h = 31 * h + Long.hashCode(lastIncludedTerm);
            return h;
        }

        @Override
        public String toString() {
            return "SnapshotChunk[offset=" + offset
                    + ", dataLen=" + data.length
                    + ", done=" + done
                    + ", lastIncludedIndex=" + lastIncludedIndex
                    + ", lastIncludedTerm=" + lastIncludedTerm + "]";
        }
    }

    public static final class SnapshotSendState {
        private final byte[] snapshotData;
        private final long lastIncludedIndex;
        private final long lastIncludedTerm;
        private int currentOffset;

        SnapshotSendState(byte[] snapshotData, long lastIncludedIndex, long lastIncludedTerm) {
            this.snapshotData = snapshotData;
            this.lastIncludedIndex = lastIncludedIndex;
            this.lastIncludedTerm = lastIncludedTerm;
            this.currentOffset = 0;
        }

        public int totalSize() {
            return snapshotData.length;
        }

        public int currentOffset() {
            return currentOffset;
        }

        public long lastIncludedIndex() {
            return lastIncludedIndex;
        }

        public long lastIncludedTerm() {
            return lastIncludedTerm;
        }

        public boolean isComplete() {
            return currentOffset >= snapshotData.length;
        }
    }

    public static final class SnapshotReceiveState {
        private final long lastIncludedIndex;
        private final long lastIncludedTerm;
        private final List<byte[]> chunks;
        private int expectedOffset;
        private boolean complete;

        SnapshotReceiveState(long lastIncludedIndex, long lastIncludedTerm) {
            this.lastIncludedIndex = lastIncludedIndex;
            this.lastIncludedTerm = lastIncludedTerm;
            this.chunks = new ArrayList<>();
            this.expectedOffset = 0;
            this.complete = false;
        }

        public long lastIncludedIndex() {
            return lastIncludedIndex;
        }

        public long lastIncludedTerm() {
            return lastIncludedTerm;
        }

        public boolean isComplete() {
            return complete;
        }
    }

    public SnapshotTransfer() {
    }

    public SnapshotSendState startSend(byte[] snapshotData, long lastIncludedIndex, long lastIncludedTerm) {
        Objects.requireNonNull(snapshotData, "snapshotData");
        if (lastIncludedIndex < 0) {
            throw new IllegalArgumentException("lastIncludedIndex must be >= 0: " + lastIncludedIndex);
        }
        if (lastIncludedTerm < 0) {
            throw new IllegalArgumentException("lastIncludedTerm must be >= 0: " + lastIncludedTerm);
        }
        return new SnapshotSendState(snapshotData, lastIncludedIndex, lastIncludedTerm);
    }

    public SnapshotChunk nextChunk(SnapshotSendState state) {
        Objects.requireNonNull(state, "state");
        if (state.isComplete()) {
            return null;
        }

        int offset = state.currentOffset;
        int remaining = state.snapshotData.length - offset;
        int chunkLen = Math.min(remaining, CHUNK_SIZE);
        byte[] chunkData = Arrays.copyOfRange(state.snapshotData, offset, offset + chunkLen);

        state.currentOffset = offset + chunkLen;
        boolean done = state.currentOffset >= state.snapshotData.length;

        return new SnapshotChunk(offset, chunkData, done, state.lastIncludedIndex, state.lastIncludedTerm);
    }

    public SnapshotReceiveState startReceive(long lastIncludedIndex, long lastIncludedTerm) {
        if (lastIncludedIndex < 0) {
            throw new IllegalArgumentException("lastIncludedIndex must be >= 0: " + lastIncludedIndex);
        }
        if (lastIncludedTerm < 0) {
            throw new IllegalArgumentException("lastIncludedTerm must be >= 0: " + lastIncludedTerm);
        }
        return new SnapshotReceiveState(lastIncludedIndex, lastIncludedTerm);
    }

    /**
     * Accepts a chunk into the receive state.
     * <p>
     * Chunks must arrive in order (by offset). If a chunk arrives
     * out of order or after the transfer is already complete, it is
     * rejected and this method returns {@code false}.
     *
     * @param state  the receive state
     * @param offset the byte offset of this chunk within the full snapshot
     * @param data   the chunk payload
     * @param done   {@code true} if this is the final chunk
     * @return {@code true} if the chunk was accepted
     * @throws NullPointerException if {@code state} or {@code data} is null
     */
    public boolean acceptChunk(SnapshotReceiveState state, int offset, byte[] data, boolean done) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(data, "data");

        if (state.complete) {
            return false;
        }
        if (offset != state.expectedOffset) {
            return false;
        }

        state.chunks.add(Arrays.copyOf(data, data.length));
        state.expectedOffset = offset + data.length;

        if (done) {
            state.complete = true;
        }
        return true;
    }

    public byte[] assemble(SnapshotReceiveState state) {
        Objects.requireNonNull(state, "state");
        if (!state.complete) {
            throw new IllegalStateException("Snapshot receive is not complete");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (byte[] chunk : state.chunks) {
                out.write(chunk);
            }
        } catch (IOException e) {
            // ByteArrayOutputStream.write(byte[]) does not throw IOException,
            // but the compiler requires this catch due to OutputStream contract.
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public boolean isComplete(SnapshotReceiveState state) {
        Objects.requireNonNull(state, "state");
        return state.complete;
    }
}
