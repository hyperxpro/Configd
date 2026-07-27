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
 * Per-shard dual-slot, authenticated, anti-rollback anchor ({@link AnchorRecord}).
 * Do not reorder: anchor must fsync before ack; fdatasync-failed writes must not advance
 * in-memory state (panic required). Single slot mutated per update: torn write damages only
 * stale slot; live slot survives via seq.
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

    static AnchorFile openInDirectory(Path walDir, int gid, IntegrityEnvelope integrity) {
        return new AnchorFile(new FileAnchorIO(walDir), gid, integrity);
    }

    static AnchorFile openOverStorage(Storage storage, int gid, IntegrityEnvelope integrity) {
        return new AnchorFile(new StorageAnchorIO(storage), gid, integrity);
    }

    static AnchorFile openOverIO(AnchorIO io, int gid, IntegrityEnvelope integrity) {
        return new AnchorFile(io, gid, integrity);
    }

    boolean existedAtOpen() {
        return existedAtOpen;
    }

    boolean hasValidRecord() {
        return current != null;
    }

    AnchorRecord current() {
        return current;
    }

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

    void writeTermVote(long term, int votedForId) {
        write(current.withTermVote(term, votedForId));
    }

    void writeDurableHead(long index, long term) {
        if (current.lastDurableIndex() == index && current.lastDurableTerm() == term) {
            return; // head unchanged - already anchored, nothing to advance
        }
        write(current.withDurable(index, term));
    }

    void writeSnapshot(long snapshotIndex, long snapshotTerm, long durableIndex, long durableTerm) {
        write(current.withSnapshot(snapshotIndex, snapshotTerm, durableIndex, durableTerm));
    }

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
    }

    private static byte[] containerHeader() {
        ByteBuffer buf = ByteBuffer.allocate(CONTAINER_HEADER_SIZE);
        buf.putInt(ANCHOR_MAGIC);
        buf.put(FILE_VERSION);
        buf.put((byte) 0);
        buf.putShort((short) 0);
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
