package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;

import java.io.Closeable;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Objects;

import static io.configd.raft.RaftArtifactMagic.NODE_ANCHOR_MAGIC;

/**
 * Node-level node-anchor: dual-slot crash-atomic file. Binds topology (epoch, shardCount),
 * audit chain head, and per-shard liveness (shardAnchorDigest) for boot cross-check (fail-closed).
 * Same frozen dual-slot mechanics as per-shard anchor; only one slot mutated per update,
 * recovery takes highest valid seq. Single-threaded (boot + scheduler, never overlap).
 */
public final class NodeAnchorFile implements Closeable {

    /** The node-anchor file name, in {@code dataDir}. */
    public static final String NODE_ANCHOR_FILE_NAME = "node-anchor";

    private final AnchorIO io;
    private final IntegrityEnvelope integrity;

    /** The highest-valid record read at open (or the bootstrapped record); null until bootstrapped. */
    private NodeAnchorRecord current;
    /** The slot (0/1) holding {@link #current}; the OTHER slot is the write target. -1 when none. */
    private int liveSlot = -1;
    /** Whether the artifact was present at open time (mint-vs-cross-check gate). */
    private final boolean existedAtOpen;

    // Fail-closed test seam: mirror of AnchorFile.armSyncFailure for the node-anchor sync.
    private int armedSyncFailures;
    private long syncFaultsFired;

    private NodeAnchorFile(AnchorIO io, IntegrityEnvelope integrity) {
        this.io = io;
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        this.existedAtOpen = io.exists();
        if (existedAtOpen) {
            parseExisting();
        }
    }

    /** Opens the production node-anchor: a real {@code node-anchor} file in {@code dataDir}. */
    public static NodeAnchorFile openInDirectory(Path dataDir, IntegrityEnvelope integrity) {
        return new NodeAnchorFile(new FileAnchorIO(dataDir, NODE_ANCHOR_FILE_NAME), integrity);
    }

    /** Opens a node-anchor over an explicit {@link AnchorIO} (test seam for the sync-fault backend). */
    static NodeAnchorFile openOverIO(AnchorIO io, IntegrityEnvelope integrity) {
        return new NodeAnchorFile(io, integrity);
    }

    /** Whether the node-anchor artifact was present at open time (false ⇒ first boot ⇒ mint). */
    public boolean existedAtOpen() {
        return existedAtOpen;
    }

    /**
     * Whether open found at least one valid slot. When {@link #existedAtOpen()} is true but this is
     * false, the file is present with both slots invalid - a tamper the caller must REFUSE (distinct
     * from a first boot, which has no file at all).
     */
    public boolean hasValidRecord() {
        return current != null;
    }

    /** The current durable node-anchor record (highest valid slot, or the bootstrapped record). */
    public NodeAnchorRecord current() {
        return current;
    }

    /**
     * Lays down a fresh preallocated node-anchor (header + both 512-B slots + one durable sync) with
     * {@code fresh} in slot 0 (its {@code nodeAnchorSeq} is set to 1) and a zero (invalid) slot 1.
     * Called only on the first-boot path, after {@link #existedAtOpen()} confirmed no file.
     *
     * @param fresh the mint record (topology + shard digest); its seq is forced to 1
     */
    public void bootstrap(NodeAnchorRecord fresh) {
        if (existedAtOpen) {
            throw new IllegalStateException("refusing to bootstrap over an existing node-anchor");
        }
        NodeAnchorRecord seeded = fresh.withSeq(1L);
        byte[] image = new byte[AnchorFile.FILE_SIZE];
        ByteBuffer.wrap(image, 0, AnchorFile.CONTAINER_HEADER_SIZE).put(containerHeader());
        encodeSlotInto(image, AnchorFile.SLOT0_OFFSET, seeded);
        // slot 1 stays zero-filled => recordLen 0 => invalid, the correct fallback for a fresh file.
        io.createPreallocated(image);
        this.current = seeded;
        this.liveSlot = 0;
    }

    /**
     * Writes {@code next} into the stale slot with a bumped {@code nodeAnchorSeq}, then {@code fsync}s.
     * The periodic refresh (updated audit head + shard digest) and the boot re-anchor (accept-forward)
     * both go through here. Caller-supplied seq is ignored; the writer assigns {@code current.seq + 1}.
     */
    public void write(NodeAnchorRecord next) {
        if (current == null || liveSlot < 0) {
            throw new IllegalStateException("node-anchor write before bootstrap/open");
        }
        NodeAnchorRecord toWrite = next.withSeq(current.nodeAnchorSeq() + 1);
        int targetSlot = 1 - liveSlot;
        int targetOffset = (targetSlot == 0) ? AnchorFile.SLOT0_OFFSET : AnchorFile.SLOT1_OFFSET;

        byte[] slotBytes = new byte[AnchorFile.SLOT_STRIDE];
        encodeSlotInto(slotBytes, 0, toWrite);
        io.writeAt(targetOffset, slotBytes);

        // Fail-closed: a throwing sync aborts BEFORE the durable barrier, so nothing is durable and
        // current/liveSlot are not advanced. The node-anchor is off the ack path, so a failed refresh
        // is not fatal - it simply leaves the previous durable record and retries next tick.
        if (armedSyncFailures > 0) {
            armedSyncFailures--;
            syncFaultsFired++;
            throw new UncheckedIOException(new java.io.IOException("injected node-anchor fdatasync failure"));
        }
        io.sync();

        this.current = toWrite;
        this.liveSlot = targetSlot;
    }

    /** Arms the next {@code n} node-anchor data-syncs to throw (fail-closed cell; mirrors AnchorFile). */
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

    // --- internals (mirror AnchorFile's dual-slot codec, node payload) ---

    /** Reads both slots at open, taking the higher valid {@code nodeAnchorSeq}; a bad header REFUSEs. */
    private void parseExisting() {
        byte[] image = io.readImage();
        if (image == null || image.length < AnchorFile.CONTAINER_HEADER_SIZE) {
            throw new IntegrityException("node-anchor file is present but shorter than the container"
                    + " header - refusing (tamper/torn)");
        }
        validateContainerHeader(image);

        NodeAnchorRecord slot0 = parseSlot(image, AnchorFile.SLOT0_OFFSET);
        NodeAnchorRecord slot1 = parseSlot(image, AnchorFile.SLOT1_OFFSET);
        if (slot0 == null && slot1 == null) {
            this.current = null;   // both invalid: caller REFUSEs (present-but-untrustworthy)
            this.liveSlot = -1;
        } else if (slot1 == null || (slot0 != null && slot0.nodeAnchorSeq() >= slot1.nodeAnchorSeq())) {
            this.current = slot0;
            this.liveSlot = 0;
        } else {
            this.current = slot1;
            this.liveSlot = 1;
        }
    }

    /** Parses one slot to a {@link NodeAnchorRecord}, or null if torn / tampered / zeroed. */
    private NodeAnchorRecord parseSlot(byte[] image, int slotOffset) {
        if (image.length < slotOffset + AnchorFile.SLOT_STRIDE) {
            return null; // truncated file - this slot is not fully present
        }
        ByteBuffer buf = ByteBuffer.wrap(image, slotOffset, AnchorFile.SLOT_STRIDE);
        int recordLen = buf.getInt();
        if (recordLen <= 0 || recordLen > AnchorFile.MAX_RECORD_LEN) {
            return null; // zero-padded (fresh) / garbage length => invalid slot
        }
        byte[] env = new byte[recordLen];
        buf.get(env);
        try {
            byte[] payload = integrity.unwrapOrNull(NODE_ANCHOR_MAGIC, IntegrityEnvelope.NODE_SCOPE, env);
            if (payload == null || payload.length != NodeAnchorRecord.PAYLOAD_LEN) {
                return null;
            }
            return NodeAnchorRecord.decode(payload);
        } catch (IntegrityException e) {
            // A torn or tampered slot: treat it as invalid so the OTHER slot wins. If BOTH slots fail
            // this way the file is present-but-invalid and the caller REFUSEs (tamper).
            return null;
        }
    }

    private void encodeSlotInto(byte[] dst, int offset, NodeAnchorRecord record) {
        byte[] env = integrity.wrap(NODE_ANCHOR_MAGIC, IntegrityEnvelope.NODE_SCOPE, record.encodePayload());
        if (env.length > AnchorFile.MAX_RECORD_LEN) {
            throw new IllegalStateException("node-anchor envelope " + env.length
                    + " B exceeds the slot capacity " + AnchorFile.MAX_RECORD_LEN);
        }
        ByteBuffer buf = ByteBuffer.wrap(dst, offset, AnchorFile.SLOT_STRIDE);
        buf.putInt(env.length);
        buf.put(env);
        // the remainder of the 512-B slot stays zero (the dst region was zero-initialized)
    }

    private static byte[] containerHeader() {
        ByteBuffer buf = ByteBuffer.allocate(AnchorFile.CONTAINER_HEADER_SIZE);
        buf.putInt(NODE_ANCHOR_MAGIC);
        buf.put(AnchorFile.FILE_VERSION);
        buf.put((byte) 0);       // flags MBZ
        buf.putShort((short) 0); // reserved MBZ
        return buf.array();
    }

    private void validateContainerHeader(byte[] image) {
        ByteBuffer buf = ByteBuffer.wrap(image, 0, AnchorFile.CONTAINER_HEADER_SIZE);
        int magic = buf.getInt();
        if (magic != NODE_ANCHOR_MAGIC) {
            throw new IntegrityException("node-anchor file has bad container magic 0x"
                    + Integer.toHexString(magic) + " (expected RNAN 0x"
                    + Integer.toHexString(NODE_ANCHOR_MAGIC) + ") - foreign or corrupt file, refusing to load");
        }
        int fileVersion = buf.get() & 0xFF;
        if (fileVersion != AnchorFile.FILE_VERSION) {
            throw new IntegrityException("node-anchor file has unsupported container fileVersion "
                    + fileVersion + " (expected " + AnchorFile.FILE_VERSION + ") - refusing a newer/unknown format");
        }
        int flags = buf.get() & 0xFF;
        int reserved = buf.getShort() & 0xFFFF;
        if (flags != 0 || reserved != 0) {
            throw new IntegrityException("node-anchor file has non-zero MBZ container bytes (flags="
                    + flags + ", reserved=" + reserved + ") - refusing to load");
        }
    }
}
