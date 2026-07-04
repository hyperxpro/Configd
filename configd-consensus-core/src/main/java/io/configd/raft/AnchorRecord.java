package io.configd.raft;

import java.nio.ByteBuffer;

/**
 * The authenticated payload carried by a {@link AnchorFile} slot: the merged Raft
 * durability anchor for one group. It subsumes the two artifacts the frozen format
 * removes - {@code raft.persistent_state} ({@code currentTerm}/{@code votedFor}) and
 * the bare {@code raft-log.snapshot-meta} ({@code snapshotIndex}/{@code snapshotTerm}) -
 * plus the durable-head high-water mark that makes anti-rollback recovery possible.
 *
 * <p>Fixed 52-byte wire payload (big-endian), wrapped in the group's
 * {@link io.configd.common.IntegrityEnvelope} under {@code ANCHOR_MAGIC}:
 *
 * <pre>
 *   [anchorSeq:8]         strictly monotonic across every write - the anti-rollback index
 *   [currentTerm:8]       merged from raft.persistent_state (Election Safety)
 *   [votedFor:4]          -1 = null (merged)
 *   [lastDurableIndex:8]  the WAL high-water mark that recovery reconciles against
 *   [lastDurableTerm:8]   term at lastDurableIndex (binds the durable tip to a term)
 *   [snapshotIndex:8]     the authenticated snapshot boundary (bare snapshot-meta removed)
 *   [snapshotTerm:8]
 * </pre>
 *
 * <p>Immutable value type. The {@code with*} copy methods produce the next record for a
 * write; {@link AnchorFile} assigns the {@code anchorSeq} (it owns the monotonic counter),
 * so callers pass whatever seq they hold and let the writer bump it.
 */
record AnchorRecord(long anchorSeq, long currentTerm, int votedFor,
                    long lastDurableIndex, long lastDurableTerm,
                    long snapshotIndex, long snapshotTerm) {

    /** {@code votedFor} sentinel for "no vote in the current term". */
    static final int VOTED_FOR_NULL = -1;

    /** Fixed on-wire payload size: six 8-byte longs plus the 4-byte votedFor. */
    static final int PAYLOAD_LEN = 8 + 8 + 4 + 8 + 8 + 8 + 8; // 52

    /** The bootstrap record a fresh node lays down: seq=1, everything else zero/null. */
    static AnchorRecord fresh() {
        return new AnchorRecord(1L, 0L, VOTED_FOR_NULL, 0L, 0L, 0L, 0L);
    }

    /** Serializes exactly {@link #PAYLOAD_LEN} big-endian bytes. */
    byte[] encodePayload() {
        ByteBuffer buf = ByteBuffer.allocate(PAYLOAD_LEN);
        buf.putLong(anchorSeq);
        buf.putLong(currentTerm);
        buf.putInt(votedFor);
        buf.putLong(lastDurableIndex);
        buf.putLong(lastDurableTerm);
        buf.putLong(snapshotIndex);
        buf.putLong(snapshotTerm);
        return buf.array();
    }

    /**
     * Parses a {@link #PAYLOAD_LEN}-byte payload. The bytes have already been
     * envelope-verified (MAC/GCM tag + CRC32C + {@code scopeId==gid}) by the caller, so a
     * wrong length here is a structural bug, not an attack surface.
     */
    static AnchorRecord decode(byte[] payload) {
        if (payload.length != PAYLOAD_LEN) {
            throw new IllegalArgumentException(
                    "anchor payload must be " + PAYLOAD_LEN + " bytes, got " + payload.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        long seq = buf.getLong();
        long term = buf.getLong();
        int voted = buf.getInt();
        long durIndex = buf.getLong();
        long durTerm = buf.getLong();
        long snapIndex = buf.getLong();
        long snapTerm = buf.getLong();
        return new AnchorRecord(seq, term, voted, durIndex, durTerm, snapIndex, snapTerm);
    }

    /** Copy with a new {@code anchorSeq} (assigned by the writer just before a slot write). */
    AnchorRecord withSeq(long seq) {
        return new AnchorRecord(seq, currentTerm, votedFor,
                lastDurableIndex, lastDurableTerm, snapshotIndex, snapshotTerm);
    }

    /** Copy advancing (or lowering) the durable head; term/vote/snapshot unchanged. */
    AnchorRecord withDurable(long newDurableIndex, long newDurableTerm) {
        return new AnchorRecord(anchorSeq, currentTerm, votedFor,
                newDurableIndex, newDurableTerm, snapshotIndex, snapshotTerm);
    }

    /** Copy updating term/vote; durable head and snapshot unchanged (persist-before-memory path). */
    AnchorRecord withTermVote(long newTerm, int newVotedFor) {
        return new AnchorRecord(anchorSeq, newTerm, newVotedFor,
                lastDurableIndex, lastDurableTerm, snapshotIndex, snapshotTerm);
    }

    /** Copy advancing the snapshot boundary and durable head together (compaction path). */
    AnchorRecord withSnapshot(long newSnapshotIndex, long newSnapshotTerm,
                              long newDurableIndex, long newDurableTerm) {
        return new AnchorRecord(anchorSeq, currentTerm, votedFor,
                newDurableIndex, newDurableTerm, newSnapshotIndex, newSnapshotTerm);
    }
}
