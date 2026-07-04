package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.common.Storage;

import java.io.Closeable;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Objects;

import static io.configd.raft.RaftArtifactMagic.ANCHOR_MAGIC;

/**
 * The per-shard {@code raft-anchor}: a dual-slot, authenticated, anti-rollback anchor that
 * carries the merged Raft durability state ({@link AnchorRecord}). It replaces two removed
 * artifacts - {@code raft.persistent_state} and the bare {@code raft-log.snapshot-meta} - and
 * adds the durable-head high-water mark ({@code lastDurableIndex}) that lets recovery detect a
 * committed-and-acked entry vanishing (the {@code W < A} refuse).
 *
 * <p><b>File layout (frozen §2.4).</b>
 * <pre>
 *   [ container header @ 0, 8 B ]  [ANCHOR_MAGIC:4][fileVersion:u8=1][flags:u8=0][reserved:u16=0]
 *   Slot 0 @ offset 8 ; Slot 1 @ offset 520.   File size = 8 + 2*512 = 1032 B (preallocated).
 *   Each slot: [recordLen:4][ envelopedAnchorRecord : recordLen ][ zero-pad to 512 ]
 *   envelopedAnchorRecord = integrity.wrap(ANCHOR_MAGIC, gid, AnchorRecord.encodePayload())
 * </pre>
 *
 * <p><b>Write protocol.</b> To update, the writer overwrites the slot holding the LOWER valid
 * {@code anchorSeq} (the stale one) with {@code anchorSeq = maxValid+1}, then {@code fdatasync}s.
 * Only one slot is ever mutated per update, so a torn/un-synced write damages only the stale slot
 * and the untouched live slot (still valid, one seq behind) survives. Recovery parses both slots
 * and takes the highest valid {@code anchorSeq}; atomicity is CRC/MAC detection + write-one-slot,
 * not sector-atomic hardware.
 *
 * <p><b>Fail-closed.</b> {@link #armSyncFailure} makes the next data-sync throw <em>before</em>
 * the barrier, and the in-memory {@link #current()} is advanced only <em>after</em> a clean sync,
 * so a throwing anchor sync never advances the durable record. The owning {@code RaftNode} turns
 * that throw into a process panic (the fsyncgate policy) - this class only guarantees no durable
 * advance on failure.
 *
 * <p>Not thread-safe: the anchor is written only by the group's single owner thread (the same
 * thread that owns the WAL it sits beside).
 */
final class AnchorFile implements Closeable {

    static final byte FILE_VERSION = 1;
    static final int CONTAINER_HEADER_SIZE = 8;
    static final int SLOT_STRIDE = 512;
    static final int RECORD_LEN_PREFIX = 4;
    static final int SLOT0_OFFSET = CONTAINER_HEADER_SIZE;               // 8
    static final int SLOT1_OFFSET = CONTAINER_HEADER_SIZE + SLOT_STRIDE; // 520
    static final int FILE_SIZE = CONTAINER_HEADER_SIZE + 2 * SLOT_STRIDE; // 1032
    /** The largest envelope a slot can hold (leaves room for the 4-byte length prefix). */
    static final int MAX_RECORD_LEN = SLOT_STRIDE - RECORD_LEN_PREFIX;   // 508

    private final AnchorIO io;
    private final int gid;
    private final IntegrityEnvelope integrity;

    /** The highest-valid record read at open (or the bootstrapped record); null until bootstrapped. */
    private AnchorRecord current;
    /** The slot (0/1) holding {@link #current}; the OTHER slot is the write target. -1 when none. */
    private int liveSlot = -1;
    /** Whether the artifact was present at open time (drives the FRESH-vs-REFUSE presence gate). */
    private final boolean existedAtOpen;

    // Fail-closed test seam: mirror of FaultInjectingStorage.failNextSyncs for the anchor sync.
    private int armedSyncFailures;
    private long syncFaultsFired;

    private AnchorFile(AnchorIO io, int gid, IntegrityEnvelope integrity) {
        this.io = io;
        this.gid = gid;
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        if (gid == IntegrityEnvelope.NODE_SCOPE) {
            throw new IllegalArgumentException("gid " + Integer.toHexString(gid)
                    + " collides with the reserved NODE_SCOPE sentinel");
        }
        this.existedAtOpen = io.exists();
        if (existedAtOpen) {
            parseExisting();
        }
    }

    /** Opens the production anchor: a real {@code raft-anchor} file in the WAL's directory. */
    static AnchorFile openInDirectory(Path walDir, int gid, IntegrityEnvelope integrity) {
        return new AnchorFile(new FileAnchorIO(walDir), gid, integrity);
    }

    /** Opens a crash-model anchor carried as a self-durable {@link Storage} value (durability tests). */
    static AnchorFile openOverStorage(Storage storage, int gid, IntegrityEnvelope integrity) {
        return new AnchorFile(new StorageAnchorIO(storage), gid, integrity);
    }

    /** Opens an anchor over an explicit {@link AnchorIO} (test seam for the sync-fault backend). */
    static AnchorFile openOverIO(AnchorIO io, int gid, IntegrityEnvelope integrity) {
        return new AnchorFile(io, gid, integrity);
    }

    /** Whether the anchor artifact was present at open time. */
    boolean existedAtOpen() {
        return existedAtOpen;
    }

    /**
     * Whether open found at least one valid slot. When {@link #existedAtOpen()} is true but this is
     * false, the file is present with both slots invalid - a tamper the caller must REFUSE (distinct
     * from a FRESH node, which has no file at all).
     */
    boolean hasValidRecord() {
        return current != null;
    }

    /** The current durable anchor record (highest valid slot, or the bootstrapped record). */
    AnchorRecord current() {
        return current;
    }

    /**
     * Lays down a fresh preallocated anchor (header + both 512-B slots + one durable sync) with the
     * bootstrap record in slot 0 and a zero (invalid) slot 1. Called only on the FRESH-node path,
     * after the presence gate has confirmed no file + an empty shard dir.
     */
    void bootstrapFresh() {
        if (existedAtOpen) {
            throw new IllegalStateException("refusing to bootstrap over an existing anchor for gid " + gid);
        }
        AnchorRecord fresh = AnchorRecord.fresh();
        byte[] image = new byte[FILE_SIZE];
        ByteBuffer.wrap(image, 0, CONTAINER_HEADER_SIZE).put(containerHeader());
        encodeSlotInto(image, SLOT0_OFFSET, fresh);
        // slot 1 stays zero-filled => recordLen 0 => invalid, the correct fallback for a fresh file.
        io.createPreallocated(image);
        this.current = fresh;
        this.liveSlot = 0;
    }

    // --- typed writes (each is one dual-slot write + one data-sync barrier) ---

    /** Term/vote persist-before-memory write: new term/vote, durable head unchanged. */
    void writeTermVote(long term, int votedForId) {
        write(current.withTermVote(term, votedForId));
    }

    /**
     * Advance (or lower) the durable head to {@code (index, term)}. A no-op when the head is already
     * there (avoids seq churn on an empty flush); a genuine move - including a re-append that lands
     * the same index at a NEW term - writes and syncs.
     */
    void writeDurableHead(long index, long term) {
        if (current.lastDurableIndex() == index && current.lastDurableTerm() == term) {
            return; // head unchanged - already anchored, nothing to advance
        }
        write(current.withDurable(index, term));
    }

    /** Advance the snapshot boundary and durable head together (compaction, anchor LAST). */
    void writeSnapshot(long snapshotIndex, long snapshotTerm, long durableIndex, long durableTerm) {
        write(current.withSnapshot(snapshotIndex, snapshotTerm, durableIndex, durableTerm));
    }

    /** Arms the next {@code n} anchor data-syncs to throw (fail-closed cell; mirrors failNextSyncs). */
    void armSyncFailure(int n) {
        this.armedSyncFailures = n;
    }

    long syncFaultsFired() {
        return syncFaultsFired;
    }

    @Override
    public void close() {
        io.close();
    }

    // --- internals ---

    /**
     * The core dual-slot write: bump the seq, encode into the STALE slot, {@code writeAt} + sync,
     * then (only after a clean sync) advance {@link #current}/{@link #liveSlot}. On a sync fault the
     * exception propagates with no in-memory advance and the un-synced stale slot lost on crash.
     */
    private void write(AnchorRecord next) {
        if (current == null || liveSlot < 0) {
            throw new IllegalStateException("anchor write before bootstrap/open for gid " + gid);
        }
        AnchorRecord toWrite = next.withSeq(current.anchorSeq() + 1);
        int targetSlot = 1 - liveSlot;
        int targetOffset = (targetSlot == 0) ? SLOT0_OFFSET : SLOT1_OFFSET;

        byte[] slotBytes = new byte[SLOT_STRIDE];
        encodeSlotInto(slotBytes, 0, toWrite);
        io.writeAt(targetOffset, slotBytes);

        // Fail-closed: a throwing sync must abort BEFORE the durable barrier, so nothing is durable
        // and current/liveSlot are not advanced (RaftNode turns the throw into a process panic).
        if (armedSyncFailures > 0) {
            armedSyncFailures--;
            syncFaultsFired++;
            throw new UncheckedIOException(new java.io.IOException(
                    "injected anchor fdatasync failure for gid " + gid));
        }
        io.sync();

        this.current = toWrite;
        this.liveSlot = targetSlot;
    }

    /** Reads both slots at open, taking the higher valid {@code anchorSeq}; a bad container header REFUSEs. */
    private void parseExisting() {
        byte[] image = io.readImage();
        if (image == null || image.length < CONTAINER_HEADER_SIZE) {
            throw new IntegrityException("anchor file for gid " + gid
                    + " is present but shorter than the container header - refusing (tamper/torn)");
        }
        validateContainerHeader(image);

        AnchorRecord slot0 = parseSlot(image, SLOT0_OFFSET);
        AnchorRecord slot1 = parseSlot(image, SLOT1_OFFSET);
        if (slot0 == null && slot1 == null) {
            this.current = null;   // both invalid: caller REFUSEs (present-but-untrustworthy)
            this.liveSlot = -1;
        } else if (slot1 == null || (slot0 != null && slot0.anchorSeq() >= slot1.anchorSeq())) {
            this.current = slot0;
            this.liveSlot = 0;
        } else {
            this.current = slot1;
            this.liveSlot = 1;
        }
    }

    /** Parses one slot to an {@link AnchorRecord}, or null if the slot is torn / tampered / zeroed. */
    private AnchorRecord parseSlot(byte[] image, int slotOffset) {
        if (image.length < slotOffset + SLOT_STRIDE) {
            return null; // truncated file - this slot is not fully present
        }
        ByteBuffer buf = ByteBuffer.wrap(image, slotOffset, SLOT_STRIDE);
        int recordLen = buf.getInt();
        if (recordLen <= 0 || recordLen > MAX_RECORD_LEN) {
            return null; // zero-padded (fresh) / garbage length => invalid slot
        }
        byte[] env = new byte[recordLen];
        buf.get(env);
        try {
            byte[] payload = integrity.unwrapOrNull(ANCHOR_MAGIC, gid, env);
            if (payload == null || payload.length != AnchorRecord.PAYLOAD_LEN) {
                return null;
            }
            return AnchorRecord.decode(payload);
        } catch (IntegrityException e) {
            // A torn or tampered slot: treat it as invalid so the OTHER slot wins. If BOTH slots
            // fail this way the file is present-but-invalid and the caller REFUSEs (tamper).
            return null;
        }
    }

    private void encodeSlotInto(byte[] dst, int offset, AnchorRecord record) {
        byte[] env = integrity.wrap(ANCHOR_MAGIC, gid, record.encodePayload());
        if (env.length > MAX_RECORD_LEN) {
            throw new IllegalStateException("anchor envelope " + env.length
                    + " B exceeds the slot capacity " + MAX_RECORD_LEN + " for gid " + gid);
        }
        ByteBuffer buf = ByteBuffer.wrap(dst, offset, SLOT_STRIDE);
        buf.putInt(env.length);
        buf.put(env);
        // the remainder of the 512-B slot stays zero (the dst region was zero-initialized)
    }

    private static byte[] containerHeader() {
        ByteBuffer buf = ByteBuffer.allocate(CONTAINER_HEADER_SIZE);
        buf.putInt(ANCHOR_MAGIC);
        buf.put(FILE_VERSION);
        buf.put((byte) 0);       // flags MBZ
        buf.putShort((short) 0); // reserved MBZ
        return buf.array();
    }

    private void validateContainerHeader(byte[] image) {
        ByteBuffer buf = ByteBuffer.wrap(image, 0, CONTAINER_HEADER_SIZE);
        int magic = buf.getInt();
        if (magic != ANCHOR_MAGIC) {
            throw new IntegrityException("anchor file for gid " + gid + " has bad container magic 0x"
                    + Integer.toHexString(magic) + " (expected RANC 0x" + Integer.toHexString(ANCHOR_MAGIC)
                    + ") - foreign or corrupt file, refusing to load");
        }
        int fileVersion = buf.get() & 0xFF;
        if (fileVersion != FILE_VERSION) {
            throw new IntegrityException("anchor file for gid " + gid + " has unsupported container fileVersion "
                    + fileVersion + " (expected " + FILE_VERSION + ") - refusing a newer/unknown format");
        }
        int flags = buf.get() & 0xFF;
        int reserved = buf.getShort() & 0xFFFF;
        if (flags != 0 || reserved != 0) {
            throw new IntegrityException("anchor file for gid " + gid + " has non-zero MBZ container bytes (flags="
                    + flags + ", reserved=" + reserved + ") - refusing to load");
        }
    }
}
