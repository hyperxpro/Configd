package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.raft.KeyringCodec.Keyring;

import java.io.Closeable;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Objects;

import static io.configd.raft.RaftArtifactMagic.KEYRING_MAGIC;

/**
 * Node-level raft-keyring: dual-slot crash-atomic file (layout frozen in docs/architecture/frozen-format-v1.md).
 * Slot stride 64 KiB (holds ~900 retained terms). Only one slot mutated per update; recovery takes highest valid seq.
 * Presence gate: no file = first boot; file + both slots invalid = tamper OR half-finished signing-key rotation (REFUSE).
 * Single-threaded (boot thread + serialized admin rotations).
 */
final class KeyringFile implements Closeable {

    /** The keyring file name, in {@code dataDir}. */
    static final String KEYRING_FILE_NAME = "raft-keyring";

    /** Slot stride FROZEN at 64 KiB (bounds retained terms; overflow REFUSES loudly). */
    static final int SLOT_STRIDE = 65536;
    static final int RECORD_LEN_PREFIX = 4;
    static final int SLOT0_OFFSET = AnchorFile.CONTAINER_HEADER_SIZE;               // 8
    static final int SLOT1_OFFSET = AnchorFile.CONTAINER_HEADER_SIZE + SLOT_STRIDE; // 65544
    static final int FILE_SIZE = AnchorFile.CONTAINER_HEADER_SIZE + 2 * SLOT_STRIDE; // 131080
    /** The largest sealed keyring an update may write; a bigger one REFUSES (slot overflow). */
    static final int MAX_RECORD_LEN = SLOT_STRIDE - RECORD_LEN_PREFIX;              // 65532

    private final AnchorIO io;
    /** The outer-MAC envelope (K_keyringMac) used to seal/open the body. */
    private final IntegrityEnvelope integrity;

    private Keyring current;
    /** The slot (0/1) holding {@link #current}; the OTHER slot is the write target. -1 when none. */
    private int liveSlot = -1;
    private final boolean existedAtOpen;

    // Fail-closed test seam: mirror of AnchorFile.armSyncFailure for crash-during-rotation modeling.
    private int armedSyncFailures;
    private long syncFaultsFired;

    private KeyringFile(AnchorIO io, IntegrityEnvelope integrity) {
        this.io = io;
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        this.existedAtOpen = io.exists();
        if (existedAtOpen) {
            parseExisting();
        }
    }

    /** Opens the production keyring: a real {@code raft-keyring} file in {@code dataDir}. */
    static KeyringFile openInDirectory(Path dataDir, IntegrityEnvelope integrity) {
        return new KeyringFile(new FileAnchorIO(dataDir, KEYRING_FILE_NAME), integrity);
    }

    /** Opens a keyring over an explicit {@link AnchorIO} (test seam for the sync-fault backend). */
    static KeyringFile openOverIO(AnchorIO io, IntegrityEnvelope integrity) {
        return new KeyringFile(io, integrity);
    }

    /** Whether the keyring artifact was present at open time (false ⇒ first boot / migration ⇒ mint). */
    boolean existedAtOpen() {
        return existedAtOpen;
    }

    /**
     * Whether open found at least one valid slot. When {@link #existedAtOpen()} is true but this is
     * false, the file is present with both slots invalid - a tamper OR a keyring under a prior signing
     * key; the caller REFUSES (distinct from a first boot, which has no file at all).
     */
    boolean hasValidRecord() {
        return current != null;
    }

    /** The current durable keyring body (highest valid slot, or the just-bootstrapped body). */
    Keyring current() {
        return current;
    }

    /**
     * Lays down a fresh preallocated keyring (header + both 64 KiB slots + one durable sync) with
     * {@code fresh} in slot 0 and a zero (invalid) slot 1. Called only on the first-boot / migration
     * path, after {@link #existedAtOpen()} confirmed no file. {@code fresh.keyringSeq()} must be 1.
     */
    void bootstrap(Keyring fresh) {
        if (existedAtOpen) {
            throw new IllegalStateException("refusing to bootstrap over an existing keyring");
        }
        if (fresh.keyringSeq() != 1L) {
            throw new IllegalStateException("bootstrap keyring must have keyringSeq=1, was " + fresh.keyringSeq());
        }
        byte[] image = new byte[FILE_SIZE];
        ByteBuffer.wrap(image, 0, AnchorFile.CONTAINER_HEADER_SIZE).put(containerHeader());
        encodeSlotInto(image, SLOT0_OFFSET, fresh, integrity);
        // slot 1 stays zero-filled => recordLen 0 => invalid, the correct fallback for a fresh file.
        io.createPreallocated(image);
        this.current = fresh;
        this.liveSlot = 0;
    }

    /**
     * Writes {@code next} (an already-seq-bumped keyring: term rotation or refresh) into the stale slot
     * sealed under this file's own {@code K_keyringMac}, then {@code fdatasync}s. Caller must supply
     * {@code next.keyringSeq() == current.keyringSeq() + 1}.
     */
    void write(Keyring next) {
        writeSlot(next, integrity);
    }

    /**
     * Signing-key rotation slot write (rewrap-before-swap): writes the {@code rewrapped} keyring
     * (roots unchanged, re-wrapped under the NEW KEK) into the stale slot sealed under the NEW
     * {@code newOuterMac} (K_keyringMac of the new signing key), then {@code fdatasync}s - BEFORE the
     * caller swaps {@code signing-key.bin}. After this the two slots are each valid under their own
     * signing key; a reboot opens under whichever signing key is active and the matching slot wins.
     */
    void writeRewrapSlot(Keyring rewrapped, IntegrityEnvelope newOuterMac) {
        writeSlot(rewrapped, Objects.requireNonNull(newOuterMac, "newOuterMac"));
    }

    private void writeSlot(Keyring next, IntegrityEnvelope sealEnv) {
        if (current == null || liveSlot < 0) {
            throw new IllegalStateException("keyring write before bootstrap/open");
        }
        if (next.keyringSeq() != current.keyringSeq() + 1) {
            throw new IllegalStateException("keyring write must bump keyringSeq by 1 (current="
                    + current.keyringSeq() + ", next=" + next.keyringSeq() + ")");
        }
        int targetSlot = 1 - liveSlot;
        int targetOffset = (targetSlot == 0) ? SLOT0_OFFSET : SLOT1_OFFSET;

        byte[] slotBytes = new byte[SLOT_STRIDE];
        encodeSlotInto(slotBytes, 0, next, sealEnv);
        io.writeAt(targetOffset, slotBytes);

        // Fail-closed / crash seam: a throwing sync aborts BEFORE the durable barrier, so nothing is
        // durable and current/liveSlot are NOT advanced. The stale slot is left torn/partial and the
        // intact prior slot wins on the next open - the crash-atomicity guarantee.
        if (armedSyncFailures > 0) {
            armedSyncFailures--;
            syncFaultsFired++;
            throw new UncheckedIOException(new java.io.IOException("injected keyring fdatasync failure"));
        }
        io.sync();

        this.current = next;
        this.liveSlot = targetSlot;
    }

    /** Arms the next {@code n} keyring data-syncs to throw (crash-during-rotation cell; mirrors AnchorFile). */
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

    // --- internals (mirror AnchorFile's dual-slot codec, keyring body) ---

    private void parseExisting() {
        byte[] image = io.readImage();
        if (image == null || image.length < AnchorFile.CONTAINER_HEADER_SIZE) {
            throw new IntegrityException("keyring file is present but shorter than the container header"
                    + " - refusing (tamper/torn)");
        }
        validateContainerHeader(image);

        Keyring slot0 = parseSlot(image, SLOT0_OFFSET);
        Keyring slot1 = parseSlot(image, SLOT1_OFFSET);
        if (slot0 == null && slot1 == null) {
            this.current = null;   // both invalid: caller REFUSEs (present-but-untrustworthy)
            this.liveSlot = -1;
        } else if (slot1 == null || (slot0 != null && slot0.keyringSeq() >= slot1.keyringSeq())) {
            this.current = slot0;
            this.liveSlot = 0;
        } else {
            this.current = slot1;
            this.liveSlot = 1;
        }
    }

    /** Parses one slot to a {@link Keyring}, or null if torn / tampered / zeroed / MAC-fails. */
    private Keyring parseSlot(byte[] image, int slotOffset) {
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
            return KeyringCodec.openSealed(integrity, env);
        } catch (IntegrityException e) {
            // A torn or tampered slot, or a slot sealed under a DIFFERENT signing key (the other side of
            // a signing-key rotation): treat it as invalid so the matching slot wins. If BOTH fail this
            // way the file is present-but-invalid and the caller REFUSEs.
            return null;
        }
    }

    private void encodeSlotInto(byte[] dst, int offset, Keyring keyring, IntegrityEnvelope sealEnv) {
        byte[] env = KeyringCodec.seal(sealEnv, keyring);
        if (env.length > MAX_RECORD_LEN) {
            // Slot overflow: too many retained terms to fit the frozen 64 KiB slot. REFUSE loudly -
            // this is an operator-escalation event (centuries away at sane rotation cadences), never a
            // silent drop of a term.
            throw new IllegalStateException("keyring envelope " + env.length + " B exceeds the frozen"
                    + " slot capacity " + MAX_RECORD_LEN + " B (too many retained terms) - refusing to"
                    + " write; operator escalation required");
        }
        ByteBuffer buf = ByteBuffer.wrap(dst, offset, SLOT_STRIDE);
        buf.putInt(env.length);
        buf.put(env);
        // the remainder of the 64 KiB slot stays zero (the dst region was zero-initialized)
    }

    private static byte[] containerHeader() {
        ByteBuffer buf = ByteBuffer.allocate(AnchorFile.CONTAINER_HEADER_SIZE);
        buf.putInt(KEYRING_MAGIC);
        buf.put(AnchorFile.FILE_VERSION);
        buf.put((byte) 0);       // flags MBZ
        buf.putShort((short) 0); // reserved MBZ
        return buf.array();
    }

    private void validateContainerHeader(byte[] image) {
        ByteBuffer buf = ByteBuffer.wrap(image, 0, AnchorFile.CONTAINER_HEADER_SIZE);
        int magic = buf.getInt();
        if (magic != KEYRING_MAGIC) {
            throw new IntegrityException("keyring file has bad container magic 0x"
                    + Integer.toHexString(magic) + " (expected RKYR 0x" + Integer.toHexString(KEYRING_MAGIC)
                    + ") - foreign or corrupt file, refusing to load");
        }
        int fileVersion = buf.get() & 0xFF;
        if (fileVersion != AnchorFile.FILE_VERSION) {
            throw new IntegrityException("keyring file has unsupported container fileVersion "
                    + fileVersion + " (expected " + AnchorFile.FILE_VERSION + ") - refusing a newer/unknown format");
        }
        int flags = buf.get() & 0xFF;
        int reserved = buf.getShort() & 0xFFFF;
        if (flags != 0 || reserved != 0) {
            throw new IntegrityException("keyring file has non-zero MBZ container bytes (flags="
                    + flags + ", reserved=" + reserved + ") - refusing to load");
        }
    }
}
