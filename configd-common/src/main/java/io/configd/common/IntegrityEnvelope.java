package io.configd.common;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.CRC32C;

/**
 * At-rest integrity codec for the Raft durability artifacts.
 * <p>
 * A self-describing envelope, mirroring the {@code FrameCodec} and
 * {@code SigningKeyStore} magic/version precedents, applied as a pure
 * encode/decode transform over each artifact's existing payload:
 *
 * <pre>
 *   [ MAGIC: 4 ][ formatVersion: 2 = 2 ][ algId: 1 ][ reserved: 1 ][ payload ][ MAC: 0|32 ][ CRC32C: 4 ]
 * </pre>
 *
 * <ul>
 *   <li><b>Layer A (keyless):</b> versioned format + CRC32C - corruption,
 *       downgrade, and format-evolution hardening. Not the security control.</li>
 *   <li><b>Layer B (keyed):</b> HMAC-SHA-256 over
 *       {@code MAGIC || formatVersion || algId || reserved || payload} - the
 *       tamper/forgery control. Every header field is inside the MAC input so an
 *       attacker cannot downgrade {@code algId} to NONE, roll {@code formatVersion}
 *       back, or mutate {@code reserved} without invalidating the MAC.</li>
 * </ul>
 *
 * <p><b>Posture.</b> An instance carries an optional {@link SecretKey}:
 * <ul>
 *   <li><b>keyed</b> (key != null): writes {@code algId=HMAC_SHA256} with a MAC,
 *       and runs <em>fail-closed</em> on read - it REFUSES an envelope with
 *       {@code algId=NONE}/absent MAC (downgrade) as well as any CRC32C or MAC
 *       mismatch.</li>
 *   <li><b>keyless</b> (key == null): writes {@code algId=NONE} (Layer A only),
 *       verifies CRC32C, and additionally accepts legacy non-enveloped bytes via
 *       the {@link #unwrapOrNull} null-return path (back-compat migration /
 *       pre-production authentication-off mode).</li>
 * </ul>
 *
 * <p>The MAC comparison is constant-time ({@link MessageDigest#isEqual}). The CRC
 * is computed with {@link CRC32C} (Castagnoli), matching the codebase convention
 * ({@code FrameCodec}).
 *
 * <p>This class is immutable and stateless beyond its (immutable) key; instances
 * are safe to share across the single Raft I/O thread that uses them.
 */
public final class IntegrityEnvelope {

    /** Fixed format version. Bumping this is a controlled, MAC-covered action. */
    public static final short FORMAT_VERSION = 2;

    /** Header before the payload: magic(4) + formatVersion(2) + algId(1) + reserved(1). */
    public static final int HEADER_SIZE = 8;
    /** CRC32C trailer size. */
    public static final int CRC_SIZE = 4;
    /** HMAC-SHA-256 output size. */
    public static final int MAC_SIZE = 32;

    /** algId: no authentication - Layer A only (keyless). */
    public static final byte ALG_NONE = 0;
    /** algId: HMAC-SHA-256 authentication (Layer B, keyed). */
    public static final byte ALG_HMAC_SHA256 = 1;

    private static final String HMAC = "HmacSHA256";

    /** The integrity key, or {@code null} for keyless mode. */
    private final SecretKey key;

    /**
     * Creates an envelope codec.
     *
     * @param key the HMAC-SHA-256 key for keyed (fail-closed) mode, or {@code null}
     *            for keyless (Layer A only) mode
     */
    public IntegrityEnvelope(SecretKey key) {
        this.key = key;
    }

    /** A keyless envelope (Layer A only). */
    public static IntegrityEnvelope keyless() {
        return new IntegrityEnvelope(null);
    }

    /** Whether this envelope authenticates (keyed, fail-closed). */
    public boolean isKeyed() {
        return key != null;
    }

    /**
     * Wraps {@code payload} in an integrity envelope for {@code magic}.
     * <p>
     * Layout: {@code [magic][formatVersion][algId][reserved][payload][MAC?][CRC32C]}.
     * The MAC (present iff keyed) covers {@code magic||formatVersion||algId||payload}.
     * The CRC32C covers everything preceding it (header + payload + MAC).
     *
     * @param magic   the artifact-specific magic (distinct per artifact)
     * @param payload the bytes to protect (non-null, may be empty)
     * @return the enveloped bytes
     */
    public byte[] wrap(int magic, byte[] payload) {
        byte algId = isKeyed() ? ALG_HMAC_SHA256 : ALG_NONE;
        int macLen = isKeyed() ? MAC_SIZE : 0;
        int total = HEADER_SIZE + payload.length + macLen + CRC_SIZE;
        byte[] out = new byte[total];
        ByteBuffer buf = ByteBuffer.wrap(out);
        buf.putInt(magic);
        buf.putShort(FORMAT_VERSION);
        buf.put(algId);
        buf.put((byte) 0); // reserved
        buf.put(payload);

        if (isKeyed()) {
            // MAC over [magic][formatVersion][algId][reserved][payload] - every
            // header field plus the payload. The reserved byte is authenticated
            // (not merely CRC-covered) so it carries no malleability if a future
            // version assigns it meaning.
            byte[] mac = computeMac(magic, algId, (byte) 0, payload);
            buf.put(mac);
        }

        // CRC32C over everything preceding the trailer (header + payload + MAC).
        CRC32C crc = new CRC32C();
        crc.update(out, 0, total - CRC_SIZE);
        buf.putInt((int) crc.getValue());
        return out;
    }

    /**
     * Unwraps a structurally-present envelope, returning the payload, or throws
     * {@link IntegrityException} on any verification failure.
     * <p>
     * Use this for call sites that always expect a verifiable envelope. For
     * absent-tolerant call sites (snapshot read, raft-state load, WAL replay) that
     * must distinguish "legit absent / torn / legacy" from "tampered", use
     * {@link #unwrapOrNull}.
     *
     * @param expectedMagic the artifact magic the caller expects
     * @param enveloped     the bytes produced by {@link #wrap}
     * @return the original payload
     * @throws IntegrityException on too-short input, wrong magic, unknown/rolled
     *                            formatVersion, CRC32C mismatch, MAC mismatch, or
     *                            (when keyed) algId=NONE/missing MAC (downgrade)
     */
    public byte[] unwrap(int expectedMagic, byte[] enveloped) {
        byte[] payload = unwrapOrNull(expectedMagic, enveloped);
        if (payload == null) {
            throw new IntegrityException(
                    "not an integrity envelope (absent/too short) for magic 0x"
                            + Integer.toHexString(expectedMagic));
        }
        return payload;
    }

    /**
     * Unwraps an envelope, returning the payload, or {@code null} ONLY when the
     * bytes are structurally absent - too short to carry the header+trailer, or
     * (in keyless mode) lacking the expected magic entirely (legacy non-enveloped
     * bytes / first boot / a torn-short artifact).
     * <p>
     * Once the bytes ARE structurally an envelope for {@code expectedMagic} but
     * fail verification (CRC32C mismatch, MAC mismatch, rolled formatVersion, or,
     * when keyed, a downgrade to algId=NONE), this THROWS {@link IntegrityException}.
     * It never silently returns {@code null} for a tampered-but-present envelope.
     * A torn/absent artifact is tolerated; a tampered one fails loud.
     *
     * @param expectedMagic the artifact magic the caller expects
     * @param enveloped     the candidate bytes (may be null)
     * @return the payload, or {@code null} if structurally absent
     * @throws IntegrityException if structurally present but verification fails
     */
    public byte[] unwrapOrNull(int expectedMagic, byte[] enveloped) {
        // Need at least the 4-byte magic to decide whether these bytes even claim to
        // be our envelope. Below that they are structurally absent (torn, first boot,
        // or a short legacy artifact) for every posture.
        if (enveloped == null || enveloped.length < Integer.BYTES) {
            return null;
        }

        ByteBuffer buf = ByteBuffer.wrap(enveloped);
        int magic = buf.getInt();
        if (magic != expectedMagic) {
            // A full-length buffer that does not carry our magic is unauthenticated
            // input - a keyed reader refuses it (fail-closed). A buffer below the
            // envelope floor is treated as structurally absent (torn, first boot,
            // or a short legacy artifact) for every posture.
            if (isKeyed() && enveloped.length >= HEADER_SIZE + CRC_SIZE) {
                throw new IntegrityException(
                        "expected integrity envelope magic 0x" + Integer.toHexString(expectedMagic)
                                + " but found 0x" + Integer.toHexString(magic)
                                + " (unauthenticated/legacy bytes refused under a configured key)");
            }
            // Keyless back-compat (legacy non-enveloped bytes, caller parses raw) or
            // a sub-floor buffer of any posture (structurally absent).
            return null;
        }

        // The magic matches: these bytes ARE meant to be our envelope, so any further
        // structural/verification failure FAILS LOUD (never null). A buffer that
        // claims our magic but is too short to be a valid envelope is a truncation/
        // tamper under a key - a deliberate refusal, not an incidental downstream
        // underflow. Keyless keeps absent semantics.
        if (enveloped.length < HEADER_SIZE + CRC_SIZE) {
            if (isKeyed()) {
                throw new IntegrityException("integrity envelope truncated (magic present, length "
                        + enveloped.length + ") for magic 0x" + Integer.toHexString(expectedMagic));
            }
            return null;
        }

        short formatVersion = buf.getShort();
        if (formatVersion != FORMAT_VERSION) {
            throw new IntegrityException(
                    "unsupported integrity envelope formatVersion " + formatVersion
                            + " (expected " + FORMAT_VERSION + ") for magic 0x"
                            + Integer.toHexString(expectedMagic));
        }
        byte algId = buf.get();
        byte reserved = buf.get(); // folded into the MAC input below

        int macLen;
        if (algId == ALG_NONE) {
            macLen = 0;
            if (isKeyed()) {
                // Downgrade attempt: a keyed reader refuses an unauthenticated
                // artifact. Posture, not just bytes, defeats strip-the-MAC.
                throw new IntegrityException(
                        "integrity downgrade refused: algId=NONE under a configured key for magic 0x"
                                + Integer.toHexString(expectedMagic));
            }
        } else if (algId == ALG_HMAC_SHA256) {
            macLen = MAC_SIZE;
        } else {
            throw new IntegrityException("unknown integrity algId " + (algId & 0xFF)
                    + " for magic 0x" + Integer.toHexString(expectedMagic));
        }

        // Length must exactly account for header + payload + MAC + CRC.
        int payloadLen = enveloped.length - HEADER_SIZE - macLen - CRC_SIZE;
        if (payloadLen < 0) {
            throw new IntegrityException("integrity envelope truncated for magic 0x"
                    + Integer.toHexString(expectedMagic) + " (length " + enveloped.length + ")");
        }

        // Verify CRC32C FIRST (over everything preceding the trailer). A bit-flip in
        // any header field, payload, or MAC surfaces as a clear corruption error.
        int crcOffset = enveloped.length - CRC_SIZE;
        CRC32C crc = new CRC32C();
        crc.update(enveloped, 0, crcOffset);
        int computedCrc = (int) crc.getValue();
        int storedCrc = ByteBuffer.wrap(enveloped, crcOffset, CRC_SIZE).getInt();
        if (computedCrc != storedCrc) {
            throw new IntegrityException("integrity CRC32C mismatch for magic 0x"
                    + Integer.toHexString(expectedMagic)
                    + " (computed=0x" + Integer.toHexString(computedCrc)
                    + ", stored=0x" + Integer.toHexString(storedCrc) + ")");
        }

        byte[] payload = Arrays.copyOfRange(enveloped, HEADER_SIZE, HEADER_SIZE + payloadLen);

        if (macLen > 0) {
            byte[] storedMac = Arrays.copyOfRange(
                    enveloped, HEADER_SIZE + payloadLen, HEADER_SIZE + payloadLen + MAC_SIZE);
            if (!isKeyed()) {
                // A keyless reader cannot verify a keyed artifact - refuse loudly
                // rather than silently trusting an unverifiable MAC.
                throw new IntegrityException(
                        "keyed integrity envelope encountered by a keyless reader for magic 0x"
                                + Integer.toHexString(expectedMagic));
            }
            byte[] computedMac = computeMac(magic, algId, reserved, payload);
            // Constant-time compare (MessageDigest.isEqual).
            if (!MessageDigest.isEqual(computedMac, storedMac)) {
                throw new IntegrityException("integrity MAC mismatch for magic 0x"
                        + Integer.toHexString(expectedMagic) + " (tamper detected)");
            }
        }

        return payload;
    }

    /** HMAC-SHA-256 over: magic, formatVersion, algId, reserved, payload (in that order). */
    private byte[] computeMac(int magic, byte algId, byte reserved, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            ByteBuffer hdr = ByteBuffer.allocate(HEADER_SIZE); // magic(4) + version(2) + algId(1) + reserved(1)
            hdr.putInt(magic);
            hdr.putShort(FORMAT_VERSION);
            hdr.put(algId);
            hdr.put(reserved);
            mac.update(hdr.array());
            mac.update(payload);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable or bad key", e);
        }
    }
}
