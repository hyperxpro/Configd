package io.configd.client.edge.session;

import io.configd.client.GapUnrecoverableException;
import io.configd.client.HostileServerLimits;
import io.configd.client.ProtocolViolationException;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeSnapshotCodec;
import io.configd.store.ConfigSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Reassembles SNAPSHOT_BEGIN → SNAPSHOT_CHUNK* → SNAPSHOT_END. Hardened against hostile server: caps breach
 * (fail-closed) or truncation/mismatch (re-bootstrap). Single-threaded: reader thread only.
 */
public final class SnapshotReassembler {

    private final int maxChunks;
    private final long maxTotalBytes;

    private final List<EdgeFrame.SnapshotChunk> chunks = new ArrayList<>();
    private boolean inProgress;
    private long snapshotSeq = -1L;
    private int declaredChunkCount;
    private long declaredTotalBytes;
    private long accumulatedBytes;

    public SnapshotReassembler(HostileServerLimits limits) {
        this.maxChunks = limits.maxSnapshotChunks();
        this.maxTotalBytes = limits.maxSnapshotTotalBytes();
    }

    public boolean inProgress() {
        return inProgress;
    }

    public long snapshotSeq() {
        return snapshotSeq;
    }

    public void begin(EdgeFrame.SnapshotBegin b) {
        if (b.chunkCount() > maxChunks) {
            throw new ProtocolViolationException("SNAPSHOT_BEGIN chunkCount " + b.chunkCount()
                    + " exceeds the client cap " + maxChunks);
        }
        if (b.totalBytes() > maxTotalBytes) {
            throw new ProtocolViolationException("SNAPSHOT_BEGIN totalBytes " + b.totalBytes()
                    + " exceeds the client cap " + maxTotalBytes);
        }
        inProgress = true;
        chunks.clear();
        snapshotSeq = b.snapshotSeq();
        declaredChunkCount = b.chunkCount();
        declaredTotalBytes = b.totalBytes();
        accumulatedBytes = 0L;
    }

    public void chunk(EdgeFrame.SnapshotChunk c) {
        if (!inProgress) {
            throw new ProtocolViolationException("SNAPSHOT_CHUNK received outside a snapshot transfer");
        }
        if (chunks.size() >= declaredChunkCount) {
            reset();
            throw new ProtocolViolationException(
                    "SNAPSHOT_CHUNK count exceeds the declared chunkCount " + declaredChunkCount);
        }
        accumulatedBytes += c.length();
        if (accumulatedBytes > declaredTotalBytes || accumulatedBytes > maxTotalBytes) {
            reset();
            throw new ProtocolViolationException("SNAPSHOT_CHUNK accumulated bytes " + accumulatedBytes
                    + " exceeds declared totalBytes " + declaredTotalBytes + " / cap " + maxTotalBytes);
        }
        chunks.add(c);
    }

    public ConfigSnapshot end(EdgeFrame.SnapshotEnd e) {
        if (!inProgress) {
            throw new ProtocolViolationException("SNAPSHOT_END received outside a snapshot transfer");
        }
        try {
            if (chunks.size() != declaredChunkCount) {
                throw new GapUnrecoverableException("truncated snapshot: received " + chunks.size()
                        + " chunks, declared " + declaredChunkCount + " — discarding, will re-bootstrap");
            }
            if (accumulatedBytes != declaredTotalBytes) {
                throw new GapUnrecoverableException("snapshot length mismatch: reassembled " + accumulatedBytes
                        + " bytes, declared " + declaredTotalBytes + " — discarding, will re-bootstrap");
            }
            byte[] body;
            ConfigSnapshot snapshot;
            try {
                body = EdgeSnapshotCodec.reassemble(chunks);        // verifies contiguous indices 0..n-1
                snapshot = EdgeSnapshotCodec.deserialize(body);     // bounds-checked entry decode
            } catch (RuntimeException decodeFailure) {
                throw new GapUnrecoverableException(
                        "snapshot body failed to decode — discarding, will re-bootstrap: "
                                + decodeFailure.getMessage());
            }
            return snapshot;
        } finally {
            reset();
        }
    }

    public void reset() {
        inProgress = false;
        chunks.clear();
        snapshotSeq = -1L;
        declaredChunkCount = 0;
        declaredTotalBytes = 0L;
        accumulatedBytes = 0L;
    }
}
