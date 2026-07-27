package io.configd.common;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * The 8-byte self-identifying container header stamped at offset 0 of every WAL
 * file {@link FileStorage} manages ({@code raft-log.wal}, the transient
 * {@code raft-log.tmp.wal}, and {@code security-audit.wal}).
 *
 * <pre>
 *   [ WAL_FILE_MAGIC : 4 ][ fileVersion : u8 = 1 ][ flags : u8 = 0 MBZ ][ reserved : u16 = 0 MBZ ]
 * </pre>
 *
 * <p>The header lets a WAL file be recognised (or a foreign/corrupt file be
 * refused) before any frame is parsed, and reserves a named {@code flags} slot
 * for a future forward-compatible container change. It is written once as the
 * leading bytes of a fresh file and validated on open.
 *
 * <p><b>Unauthenticated by design.</b> The header must be readable with no key,
 * so it is a corruption / foreign-file guard only - authentication is always the
 * inner {@link IntegrityEnvelope} on each record. A flipped header can therefore
 * only produce a clean refusal, never a security decision: neither {@code flags}
 * nor {@code fileVersion} gates anything but "load or refuse", and there are no
 * header-derived offsets (frames always start at {@link #HEADER_SIZE}).
 *
 * <p>The magic is mirrored by the durability-artifact registry
 * {@code io.configd.raft.RaftArtifactMagic}; a cross-module collision test pins
 * the two to the same value. This class is the authoritative definition, because
 * {@code configd-common} cannot depend on {@code configd-consensus-core}.
 */
public final class WalContainer {

    /**
     * ASCII "RWLF". The leading sigil of a Configd WAL container file - distinct
     * from every inner record magic so a hexdump names the file at a glance.
     */
    public static final int WAL_FILE_MAGIC = 0x5257_4C46;

    /** Container format version. Bumping it is a controlled, compatibility-breaking action. */
    static final byte FILE_VERSION = 1;

    /**
     * Header length: magic(4) + fileVersion(1) + flags(1) + reserved(2). Public so recovery /
     * verification tooling and tests can skip the header to reach the first frame at this offset.
     */
    public static final int HEADER_SIZE = 8;

    private WalContainer() {
    }

    /**
     * Builds a fresh container header, positioned for writing (a full 8-byte
     * buffer with {@code position == 0}). {@code flags} and {@code reserved} are
     * MBZ (zero) in v1.
     */
    static ByteBuffer header() {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
        buf.putInt(WAL_FILE_MAGIC);
        buf.put(FILE_VERSION);
        buf.put((byte) 0);        // flags - MBZ
        buf.putShort((short) 0);  // reserved - MBZ
        buf.flip();
        return buf;
    }

    /**
     * Validates the container header at the buffer's current position (offset 0),
     * consuming exactly {@link #HEADER_SIZE} bytes. The caller must have ensured at
     * least {@code HEADER_SIZE} bytes are readable.
     *
     * <p>Fails closed: a wrong magic (foreign/corrupt file), an unknown
     * {@code fileVersion} (a newer format this reader cannot parse), or a non-zero
     * MBZ {@code flags}/{@code reserved} (tamper or a newer writer) all throw. The
     * caller wraps the {@link IOException} the same way it wraps a frame-CRC
     * mismatch, so every WAL load failure surfaces uniformly.
     */
    static void validateHeader(String logName, ByteBuffer buf) throws IOException {
        int magic = buf.getInt();
        if (magic != WAL_FILE_MAGIC) {
            throw new IOException("WAL " + logName + " has bad container magic 0x"
                    + Integer.toHexString(magic) + " (expected RWLF 0x"
                    + Integer.toHexString(WAL_FILE_MAGIC)
                    + ") - foreign or corrupt file, refusing to load");
        }
        int fileVersion = buf.get() & 0xFF;
        if (fileVersion != FILE_VERSION) {
            throw new IOException("WAL " + logName + " has unsupported container fileVersion "
                    + fileVersion + " (expected " + FILE_VERSION
                    + ") - refusing to read a newer/unknown WAL format");
        }
        int flags = buf.get() & 0xFF;
        int reserved = buf.getShort() & 0xFFFF;
        if (flags != 0 || reserved != 0) {
            throw new IOException("WAL " + logName + " has non-zero MBZ container header bytes (flags="
                    + flags + ", reserved=" + reserved + ") - refusing to load");
        }
    }
}
