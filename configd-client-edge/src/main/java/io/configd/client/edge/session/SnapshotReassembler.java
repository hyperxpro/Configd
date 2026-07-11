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
 * Reassembles a {@code SNAPSHOT_BEGIN → SNAPSHOT_CHUNK* → SNAPSHOT_END} transfer into a {@link ConfigSnapshot},
 * hardened against a hostile server (the client mirror of {@code EdgeClientCore}'s snapshot machinery). Two
 * failure classes:
 *
 * <ul>
 *   <li><b>Caps breach ⇒ {@link ProtocolViolationException} (fail-closed).</b> A {@code SNAPSHOT_BEGIN}
 *       declaring more than the client's hard ceilings ({@code maxSnapshotChunks} / {@code maxSnapshotTotalBytes}),
 *       a {@code (chunkCount+1)}-th chunk, accumulated bytes past the declared {@code totalBytes} or the hard
 *       ceiling, or a chunk with no preceding {@code BEGIN} — all rejected <b>before</b> unbounded
 *       accumulation. An honest server never declares over the frozen ceilings; this is a hostile/buggy peer.</li>
 *   <li><b>Truncation / mismatch ⇒ {@link GapUnrecoverableException} (re-bootstrap).</b> At {@code END}, fewer
 *       chunks than declared, a reassembled length ≠ {@code totalBytes}, or a body that fails to deserialize —
 *       the snapshot is incomplete, so it is <b>discarded and re-subscribed</b>, never applied partially.</li>
 * </ul>
 *
 * <p>Not thread-safe: driven from the single reader thread.
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

    /** The seq the in-progress (or just-ended) snapshot encodes. */
    public long snapshotSeq() {
        return snapshotSeq;
    }

    /** Begins a transfer; rejects a header that already declares over the hard ceilings (fail-closed). */
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

    /** Accepts one chunk; rejects a chunk outside a transfer, an over-count chunk, or over-accumulation. */
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

    /**
     * Ends a transfer and returns the reassembled snapshot. Verifies it received <b>exactly</b> the declared
     * chunk count and byte length; a shortfall discards and re-bootstraps rather than applying a partial
     * snapshot.
     *
     * @throws GapUnrecoverableException on a truncated / mismatched / undecodable snapshot (re-bootstrap)
     * @throws ProtocolViolationException on an END outside a transfer
     */
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

    /** Discards any in-progress transfer state (on error, cutover, or re-subscribe). */
    public void reset() {
        inProgress = false;
        chunks.clear();
        snapshotSeq = -1L;
        declaredChunkCount = 0;
        declaredTotalBytes = 0L;
        accumulatedBytes = 0L;
    }
}
