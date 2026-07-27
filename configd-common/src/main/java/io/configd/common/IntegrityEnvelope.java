package io.configd.common;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * At-rest integrity codec for the Raft durability artifacts.
 * <p>
 * A self-describing envelope, mirroring the {@code FrameCodec} and {@code SigningKeyStore}
 * magic/version precedents, applied as a pure encode/decode transform over each artifact's payload:
 *
 * <pre>
 *   header (8 B):   [ MAGIC: 4 ][ formatVersion: 2 = 3 ][ algId: 1 ][ reserved: 1 ]
 *   algId=0 NONE:   header [ scopeId: 4 ][ payload ][ CRC32C: 4 ]
 *   algId=1 HMAC:   header [ scopeId: 4 ][ keyTerm: 4 ][ payload ][ MAC: 32 ][ CRC32C: 4 ]
 *   algId=2 GCM:    header [ scopeId: 4 ][ keyTerm: 4 ][ segmentId: 16 ][ nonce: 12 ][ ct+tag ][ CRC32C: 4 ]
 * </pre>
 *
 * <p><b>The {@code keyTerm} (term-versioned integrity).</b> Both keyed
 * postures carry a 4-byte {@code keyTerm} right after the {@code scopeId}, authenticated by the MAC
 * (HMAC input) / GCM AAD. It selects the keyring root that derives this record's key, so an old-term
 * record still verifies after a rotation (non-destructive), and a forged/rolled {@code keyTerm}
 * changes the key and/or the MAC input and FAILS verification. The {@code keyTerm} domain:
 * <ul>
 *   <li>{@code keyTerm >= 1} - a real keyring term; every at-rest artifact except the keyring itself.
 *       The key is {@code K_integrity[keyTerm]} (HMAC) / {@code DEK[keyTerm,segmentId]} (GCM),
 *       supplied by the term-versioned {@link AtRestKeys} the envelope was built with.</li>
 *   <li>{@code keyTerm == 0} - the signing-key domain, legal ONLY for {@code KEYRING_MAGIC} (the
 *       keyring's own outer envelope, which cannot reference a term it defines). It is MAC'd under a
 *       single signing-key-derived {@code K_keyringMac} ({@link #keyringMac}); {@code keyTerm == 0}
 *       under any OTHER magic FAILS CLOSED.</li>
 * </ul>
 *
 * <p><b>The {@code scopeId} (cross-shard/cross-scope splice control).</b> A 4-byte id, authenticated
 * in every posture, binding the record to the shard ({@code scopeId = gid}) or node scope
 * ({@link #NODE_SCOPE}) that wrote it. Every read path asserts {@code scopeId == expected} and refuses
 * a mismatch - the sole cross-shard-splice defense (the record's own authenticated scopeId announces
 * its true shard; the MAC/tag makes it unforgeable in place).
 *
 * <p><b>Postures.</b>
 * <ul>
 *   <li><b>keyless</b> ({@link #keyless()}): writes {@code algId=NONE} (no {@code keyTerm}), verifies
 *       CRC32C only. Byte-identical to the pre-term-versioning layout - the encryption-off AND
 *       auth-off posture.</li>
 *   <li><b>single-key HMAC</b> ({@code new IntegrityEnvelope(key)}): writes {@code algId=HMAC} with a
 *       fixed {@code keyTerm=1} under a single key. A simple non-term-versioned HMAC for tests and
 *       fixed-key call sites; refuses {@code KEYRING_MAGIC} (use {@link #keyringMac}).</li>
 *   <li><b>keyring-mac HMAC</b> ({@link #keyringMac(SecretKey)}): writes {@code algId=HMAC} with
 *       {@code keyTerm=0} under {@code K_keyringMac}; the keyring's own outer envelope,
 *       {@code KEYRING_MAGIC} only.</li>
 *   <li><b>term-versioned HMAC</b> ({@link #hmac(AtRestKeys)}): writes {@code algId=HMAC} with
 *       {@code keyTerm=activeTerm} under {@code K_integrity[keyTerm]} - the production at-rest
 *       integrity posture (encryption off, auth on). Non-destructive rotation.</li>
 *   <li><b>encrypting</b> ({@link #encrypting(AtRestKeys)}): writes {@code algId=GCM} ciphertext;
 *       reads GCM plus (unless {@code requireEncrypted}) term-versioned HMAC records via the same
 *       keyring (the enable-encryption migration path). Refuses {@code algId=NONE} (downgrade).</li>
 * </ul>
 *
 * <p>The MAC comparison is constant-time ({@link MessageDigest#isEqual}); the CRC is {@link CRC32C}
 * (Castagnoli), matching the {@code FrameCodec} convention. Instances are immutable beyond their
 * (immutable) keys and safe to share across a group's single Raft I/O thread.
 */
public final class IntegrityEnvelope {

    /** Fixed format version. Bumping this is a controlled, MAC-covered action. */
    public static final short FORMAT_VERSION = 3;

    public static final int HEADER_SIZE = 8;
    public static final int SCOPE_ID_SIZE = 4;
    public static final int KEY_TERM_SIZE = 4;
    public static final int CRC_SIZE = 4;
    public static final int MAC_SIZE = 32;

    /**
     * The scope stamped on node-level (not per-shard) artifacts. Per-shard artifacts stamp {@code gid},
     * frozen to {@code [0, NODE_SCOPE)} so a per-shard reader can never be fooled by a node-level
     * artifact colliding on scope. {@code 0xFFFFFFFF} is reserved and illegal as a {@code gid}.
     */
    public static final int NODE_SCOPE = 0xFFFFFFFF;

    /** The signing-key {@code keyTerm} domain: legal only for {@code KEYRING_MAGIC} (keyring outer env). */
    public static final int KEYRING_KEY_TERM = 0;
    static final int SINGLE_KEY_TERM = 1;

    static final int KEYRING_MAGIC = 0x524B_5952; // "RKYR"

    /** Smallest structurally-valid envelope: header + scopeId + CRC (empty NONE payload). */
    private static final int MIN_ENVELOPE_SIZE = HEADER_SIZE + SCOPE_ID_SIZE + CRC_SIZE;

    public static final byte ALG_NONE = 0;
    public static final byte ALG_HMAC_SHA256 = 1;
    public static final byte ALG_AES256_GCM = 2;

    private static final String HMAC = "HmacSHA256";

    // AES-256-GCM (algId=2) layout: [ header:8 ][ scopeId:4 ][ keyTerm:4 ][ segmentId:16 ][ nonce:12 ]
    //   [ ciphertext+tag ][ CRC32C:4 ]. AAD = the whole ENC_PREFIX (header..nonce).
    private static final String GCM_TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_TAG_SIZE = GCM_TAG_BITS / 8;
    private static final int KEYED_PREFIX_SIZE = HEADER_SIZE + SCOPE_ID_SIZE + KEY_TERM_SIZE;
    /** header(8) + scopeId(4) + keyTerm(4) + segmentId(16) + nonce(12) = the AAD-covered prefix. */
    private static final int ENC_PREFIX_SIZE =
            KEYED_PREFIX_SIZE + AtRestKeys.SEGMENT_ID_LEN + AtRestKeys.NONCE_LEN;
    private static final int ENC_MIN_SIZE = ENC_PREFIX_SIZE + GCM_TAG_SIZE + CRC_SIZE;

    /** One {@link Cipher} per thread - GCM is stateful per op; each group's owner thread reuses its own. */
    private static final ThreadLocal<Cipher> GCM_CIPHER = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance(GCM_TRANSFORM);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/GCM/NoPadding unavailable", e);
        }
    });

    private enum Mode { KEYLESS, HMAC_SINGLE, HMAC_KEYRING, HMAC_TERMED, GCM }

    private final Mode mode;
    private final SecretKey singleKey;
    private final AtRestKeys keys;
    /** GCM only: when true, refuse to read a legacy {@code algId=HMAC} record (post-migration lockdown). */
    private final boolean requireEncrypted;

    private IntegrityEnvelope(Mode mode, SecretKey singleKey, AtRestKeys keys, boolean requireEncrypted) {
        this.mode = mode;
        this.singleKey = singleKey;
        this.keys = keys;
        this.requireEncrypted = requireEncrypted;
    }

    public IntegrityEnvelope(SecretKey key) {
        this(key == null ? Mode.KEYLESS : Mode.HMAC_SINGLE, key, null, false);
    }

    /** A keyless envelope (Layer A only): {@code algId=NONE}, CRC32C, no keyTerm. Byte-identical layout. */
    public static IntegrityEnvelope keyless() {
        return new IntegrityEnvelope(Mode.KEYLESS, null, null, false);
    }

    /**
     * The keyring's own outer envelope: single-key HMAC under {@code K_keyringMac} with
     * {@code keyTerm=0}, legal only for {@code KEYRING_MAGIC}. Distinct from a term-versioned
     * at-rest envelope so the keyring can authenticate itself before any keyring term exists.
     */
    public static IntegrityEnvelope keyringMac(SecretKey keyringMacKey) {
        return new IntegrityEnvelope(Mode.HMAC_KEYRING,
                Objects.requireNonNull(keyringMacKey, "keyringMacKey"), null, false);
    }

    /**
     * A term-versioned HMAC envelope (production at-rest integrity, encryption off): writes
     * {@code algId=HMAC} at {@code keyTerm=activeTerm} under {@code K_integrity[keyTerm]} from the
     * keyring, and verifies each record under the root for ITS own term (non-destructive rotation).
     */
    public static IntegrityEnvelope hmac(AtRestKeys keys) {
        return new IntegrityEnvelope(Mode.HMAC_TERMED, null, Objects.requireNonNull(keys, "keys"), false);
    }

    public static IntegrityEnvelope encrypting(AtRestKeys keys) {
        return encrypting(keys, false);
    }

    /**
     * An encrypting envelope. When {@code requireEncrypted}, the reader REFUSES a legacy
     * {@code algId=HMAC} record (post-migration lockdown against a rollback to a pre-encryption WAL
     * segment); otherwise it reads them via the same keyring (the enable-encryption migration path).
     */
    public static IntegrityEnvelope encrypting(AtRestKeys keys, boolean requireEncrypted) {
        return new IntegrityEnvelope(Mode.GCM, null, Objects.requireNonNull(keys, "keys"), requireEncrypted);
    }

    public boolean isKeyed() {
        return mode == Mode.HMAC_SINGLE || mode == Mode.HMAC_KEYRING || mode == Mode.HMAC_TERMED;
    }

    public boolean isEncrypting() {
        return mode == Mode.GCM;
    }

    private boolean isAuthenticated() {
        return mode != Mode.KEYLESS;
    }

    public byte[] wrap(int magic, int scopeId, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        switch (mode) {
            case KEYLESS:
                return wrapPlain(magic, scopeId, payload);
            case HMAC_SINGLE:
                requireNonKeyring(magic);
                return wrapHmac(magic, scopeId, SINGLE_KEY_TERM, singleKey, payload);
            case HMAC_KEYRING:
                if (magic != KEYRING_MAGIC) {
                    throw new IntegrityException("keyringMac envelope may only wrap KEYRING_MAGIC (magic 0x"
                            + Integer.toHexString(magic) + " refused)");
                }
                return wrapHmac(magic, scopeId, KEYRING_KEY_TERM, singleKey, payload);
            case HMAC_TERMED: {
                requireNonKeyring(magic);
                int kt = keys.activeTerm();
                requirePositiveTerm(kt);
                return wrapHmac(magic, scopeId, kt, keys.macKey(kt), payload);
            }
            case GCM:
                requireNonKeyring(magic);
                return wrapEncrypted(magic, scopeId, payload);
            default:
                throw new IllegalStateException("unreachable posture " + mode);
        }
    }

    private static void requireNonKeyring(int magic) {
        if (magic == KEYRING_MAGIC) {
            throw new IntegrityException("KEYRING_MAGIC must be sealed with keyringMac() (keyTerm=0),"
                    + " not a term-versioned/single-key envelope");
        }
    }

    private static void requirePositiveTerm(int keyTerm) {
        if (keyTerm < 1) {
            throw new IntegrityException("at-rest keyTerm must be >= 1 for a non-keyring artifact, was "
                    + keyTerm);
        }
    }

    private byte[] wrapPlain(int magic, int scopeId, byte[] payload) {
        int total = HEADER_SIZE + SCOPE_ID_SIZE + payload.length + CRC_SIZE;
        byte[] out = new byte[total];
        ByteBuffer buf = ByteBuffer.wrap(out);
        putHeader(buf, magic, ALG_NONE);
        buf.putInt(scopeId);
        buf.put(payload);
        return finishCrc(out);
    }

    private byte[] wrapHmac(int magic, int scopeId, int keyTerm, SecretKey macKey, byte[] payload) {
        int total = KEYED_PREFIX_SIZE + payload.length + MAC_SIZE + CRC_SIZE;
        byte[] out = new byte[total];
        ByteBuffer buf = ByteBuffer.wrap(out);
        putHeader(buf, magic, ALG_HMAC_SHA256);
        buf.putInt(scopeId);
        buf.putInt(keyTerm);
        buf.put(payload);
        // MAC over [magic || fmtVer || algId || reserved || scopeId || keyTerm || payload].
        byte[] mac = hmac(macKey, out, KEYED_PREFIX_SIZE, payload);
        buf.put(mac);
        return finishCrc(out);
    }

    private byte[] wrapEncrypted(int magic, int scopeId, byte[] payload) {
        AtRestKeys.Seal seal = keys.nextSeal(magic);
        requirePositiveTerm(seal.keyTerm());
        byte[] segmentId = seal.segmentId();
        byte[] nonce = seal.nonce();
        int total = ENC_PREFIX_SIZE + payload.length + GCM_TAG_SIZE + CRC_SIZE;
        byte[] out = new byte[total];
        ByteBuffer buf = ByteBuffer.wrap(out);
        putHeader(buf, magic, ALG_AES256_GCM);
        buf.putInt(scopeId);
        buf.putInt(seal.keyTerm());
        buf.put(segmentId);
        buf.put(nonce);
        byte[] aad = Arrays.copyOfRange(out, 0, ENC_PREFIX_SIZE);
        byte[] cipherText = gcmEncrypt(seal.dek(), nonce, aad, payload);
        buf.put(cipherText);
        return finishCrc(out);
    }

    private static void putHeader(ByteBuffer buf, int magic, byte algId) {
        buf.putInt(magic);
        buf.putShort(FORMAT_VERSION);
        buf.put(algId);
        buf.put((byte) 0); // reserved MBZ
    }

    private static byte[] finishCrc(byte[] out) {
        CRC32C crc = new CRC32C();
        crc.update(out, 0, out.length - CRC_SIZE);
        ByteBuffer.wrap(out, out.length - CRC_SIZE, CRC_SIZE).putInt((int) crc.getValue());
        return out;
    }

    public byte[] unwrap(int expectedMagic, int expectedScopeId, byte[] enveloped) {
        byte[] payload = unwrapOrNull(expectedMagic, expectedScopeId, enveloped);
        if (payload == null) {
            throw new IntegrityException(
                    "not an integrity envelope (absent/too short) for magic 0x"
                            + Integer.toHexString(expectedMagic));
        }
        return payload;
    }

    /**
     * Unwraps an envelope, returning the payload, or {@code null} ONLY when the bytes are structurally
     * absent (too short, or missing the expected magic entirely - first boot / torn-short / foreign
     * bytes a keyless reader tolerates). A structurally-present but failing envelope THROWS.
     */
    public byte[] unwrapOrNull(int expectedMagic, int expectedScopeId, byte[] enveloped) {
        if (enveloped == null || enveloped.length < Integer.BYTES) {
            return null;
        }

        ByteBuffer buf = ByteBuffer.wrap(enveloped);
        int magic = buf.getInt();
        if (magic != expectedMagic) {
            if (isAuthenticated() && enveloped.length >= HEADER_SIZE + CRC_SIZE) {
                throw new IntegrityException(
                        "expected integrity envelope magic 0x" + Integer.toHexString(expectedMagic)
                                + " but found 0x" + Integer.toHexString(magic)
                                + " (unauthenticated/foreign bytes refused under a configured key)");
            }
            return null;
        }
        if (enveloped.length < MIN_ENVELOPE_SIZE) {
            if (isAuthenticated()) {
                throw new IntegrityException("integrity envelope truncated (magic present, length "
                        + enveloped.length + ") for magic 0x" + Integer.toHexString(expectedMagic));
            }
            return null;
        }

        // CRC32C FIRST (version-independent) so a bit-flip reports corruption, not a version error.
        int crcOffset = enveloped.length - CRC_SIZE;
        verifyCrc32c(expectedMagic, enveloped, crcOffset);

        short formatVersion = buf.getShort();
        if (formatVersion != FORMAT_VERSION) {
            throw new IntegrityException(
                    "unsupported integrity envelope formatVersion " + formatVersion
                            + " (expected " + FORMAT_VERSION + ") for magic 0x"
                            + Integer.toHexString(expectedMagic));
        }
        byte algId = buf.get();
        byte reserved = buf.get();
        if (reserved != 0) {
            throw new IntegrityException("integrity envelope reserved byte must be zero (found "
                    + (reserved & 0xFF) + ") for magic 0x" + Integer.toHexString(expectedMagic));
        }
        int scopeId = buf.getInt();
        if (scopeId != expectedScopeId) {
            throw new IntegrityException("integrity envelope scope mismatch for magic 0x"
                    + Integer.toHexString(expectedMagic) + ": record scopeId=0x"
                    + Integer.toHexString(scopeId) + " but reader expected 0x"
                    + Integer.toHexString(expectedScopeId) + " (cross-shard/scope artifact refused)");
        }

        if (algId == ALG_AES256_GCM) {
            return unwrapEncrypted(expectedMagic, enveloped);
        }
        if (algId == ALG_NONE) {
            if (isAuthenticated()) {
                throw new IntegrityException(
                        "integrity downgrade refused: algId=NONE under a configured key for magic 0x"
                                + Integer.toHexString(expectedMagic));
            }
            int start = HEADER_SIZE + SCOPE_ID_SIZE;
            return Arrays.copyOfRange(enveloped, start, crcOffset);
        }
        if (algId == ALG_HMAC_SHA256) {
            return unwrapHmac(expectedMagic, enveloped, crcOffset);
        }
        throw new IntegrityException("unknown integrity algId " + (algId & 0xFF)
                + " for magic 0x" + Integer.toHexString(expectedMagic));
    }

    private byte[] unwrapHmac(int expectedMagic, byte[] enveloped, int crcOffset) {
        if (mode == Mode.KEYLESS) {
            throw new IntegrityException(
                    "keyed integrity envelope encountered by a keyless reader for magic 0x"
                            + Integer.toHexString(expectedMagic));
        }
        if (mode == Mode.GCM && requireEncrypted) {
            throw new IntegrityException(
                    "requireEncrypted: legacy algId=HMAC record refused for magic 0x"
                            + Integer.toHexString(expectedMagic) + " (post-migration lockdown)");
        }
        int keyTerm = ByteBuffer.wrap(enveloped, HEADER_SIZE + SCOPE_ID_SIZE, KEY_TERM_SIZE).getInt();
        SecretKey macKey = macKeyForRead(expectedMagic, keyTerm);

        int payloadStart = KEYED_PREFIX_SIZE;
        int payloadLen = crcOffset - MAC_SIZE - payloadStart;
        if (payloadLen < 0) {
            throw new IntegrityException("integrity envelope truncated for magic 0x"
                    + Integer.toHexString(expectedMagic) + " (length " + enveloped.length + ")");
        }
        byte[] payload = Arrays.copyOfRange(enveloped, payloadStart, payloadStart + payloadLen);
        byte[] storedMac = Arrays.copyOfRange(enveloped, payloadStart + payloadLen,
                payloadStart + payloadLen + MAC_SIZE);
        byte[] computedMac = hmac(macKey, enveloped, KEYED_PREFIX_SIZE, payload);
        if (!MessageDigest.isEqual(computedMac, storedMac)) {
            throw new IntegrityException("integrity MAC mismatch for magic 0x"
                    + Integer.toHexString(expectedMagic) + " (tamper detected)");
        }
        return payload;
    }

    /**
     * Selects the HMAC key for a record's {@code (magic, keyTerm)}, enforcing the keyTerm domain:
     * {@code KEYRING_MAGIC} MUST be keyTerm 0 under the keyring reader; every other magic MUST be
     * keyTerm >= 1; a keyTerm with no retained root FAILS CLOSED.
     */
    private SecretKey macKeyForRead(int magic, int keyTerm) {
        if (magic == KEYRING_MAGIC) {
            if (mode != Mode.HMAC_KEYRING) {
                throw new IntegrityException("KEYRING_MAGIC record encountered by a non-keyring reader");
            }
            if (keyTerm != KEYRING_KEY_TERM) {
                throw new IntegrityException("keyring envelope must carry keyTerm=0, was " + keyTerm);
            }
            return singleKey;
        }
        if (keyTerm == KEYRING_KEY_TERM) {
            throw new IntegrityException("keyTerm=0 is reserved for the keyring; illegal for magic 0x"
                    + Integer.toHexString(magic) + " (fail closed)");
        }
        switch (mode) {
            case HMAC_SINGLE:
                return singleKey;
            case HMAC_TERMED:
            case GCM:
                return keys.macKey(keyTerm); // unknown term -> IntegrityException (fail closed)
            case HMAC_KEYRING:
                throw new IntegrityException("keyringMac reader cannot verify a non-keyring artifact"
                        + " (magic 0x" + Integer.toHexString(magic) + ")");
            default:
                throw new IntegrityException("keyed record under a keyless reader");
        }
    }

    private byte[] unwrapEncrypted(int expectedMagic, byte[] enveloped) {
        if (mode != Mode.GCM) {
            throw new IntegrityException(
                    "AES-256-GCM envelope encountered by a non-encrypting reader for magic 0x"
                            + Integer.toHexString(expectedMagic));
        }
        if (enveloped.length < ENC_MIN_SIZE) {
            throw new IntegrityException("AES-256-GCM envelope truncated (length " + enveloped.length
                    + ", min " + ENC_MIN_SIZE + ") for magic 0x" + Integer.toHexString(expectedMagic));
        }
        int crcOffset = enveloped.length - CRC_SIZE;
        ByteBuffer buf = ByteBuffer.wrap(enveloped);
        buf.position(HEADER_SIZE + SCOPE_ID_SIZE);
        int keyTerm = buf.getInt();
        if (keyTerm < 1) {
            throw new IntegrityException("AES-256-GCM keyTerm must be >= 1, was " + keyTerm
                    + " for magic 0x" + Integer.toHexString(expectedMagic));
        }
        byte[] segmentId = new byte[AtRestKeys.SEGMENT_ID_LEN];
        buf.get(segmentId);
        byte[] nonce = new byte[AtRestKeys.NONCE_LEN];
        buf.get(nonce);
        int cipherLen = crcOffset - ENC_PREFIX_SIZE;
        byte[] cipherText = new byte[cipherLen];
        buf.get(cipherText);

        SecretKey dek = keys.resolveDek(keyTerm, segmentId); // unknown term -> fail closed
        byte[] aad = Arrays.copyOfRange(enveloped, 0, ENC_PREFIX_SIZE);
        try {
            return gcmDecrypt(dek, nonce, aad, cipherText);
        } catch (AEADBadTagException e) {
            throw new IntegrityException("AES-256-GCM authentication failed (tamper detected) for magic 0x"
                    + Integer.toHexString(expectedMagic), e);
        } catch (GeneralSecurityException e) {
            throw new IntegrityException("AES-256-GCM decrypt error for magic 0x"
                    + Integer.toHexString(expectedMagic), e);
        }
    }

    private static void verifyCrc32c(int expectedMagic, byte[] enveloped, int crcOffset) {
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
    }

    private static byte[] hmac(SecretKey key, byte[] prefixSource, int prefixLen, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            mac.update(prefixSource, 0, prefixLen);
            mac.update(payload);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable or bad key", e);
        }
    }

    private static byte[] gcmEncrypt(SecretKey dek, byte[] nonce, byte[] aad, byte[] plaintext) {
        try {
            Cipher cipher = GCM_CIPHER.get();
            cipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-256-GCM encrypt failed", e);
        }
    }

    private static byte[] gcmDecrypt(SecretKey dek, byte[] nonce, byte[] aad, byte[] cipherText)
            throws GeneralSecurityException {
        Cipher cipher = GCM_CIPHER.get();
        cipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(cipherText);
    }
}
