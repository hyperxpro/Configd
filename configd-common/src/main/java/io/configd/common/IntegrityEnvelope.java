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
 * A self-describing envelope, mirroring the {@code FrameCodec} and
 * {@code SigningKeyStore} magic/version precedents, applied as a pure
 * encode/decode transform over each artifact's existing payload:
 *
 * <pre>
 *   header (8 B):   [ MAGIC: 4 ][ formatVersion: 2 = 3 ][ algId: 1 ][ reserved: 1 ]
 *   algId=0 NONE:   header [ scopeId: 4 ][ payload ][ CRC32C: 4 ]
 *   algId=1 HMAC:   header [ scopeId: 4 ][ payload ][ MAC: 32 ][ CRC32C: 4 ]
 *   algId=2 GCM:    header [ scopeId: 4 ][ keyTerm: 4 ][ segmentId: 16 ][ nonce: 12 ][ ciphertext+tag ][ CRC32C: 4 ]
 * </pre>
 *
 * <ul>
 *   <li><b>Layer A (keyless):</b> versioned format + CRC32C - corruption,
 *       downgrade, and format-evolution hardening. Not the security control.</li>
 *   <li><b>Layer B (keyed):</b> HMAC-SHA-256 over
 *       {@code MAGIC || formatVersion || algId || reserved || scopeId || payload} -
 *       the tamper/forgery control. Every header field (including the {@code scopeId})
 *       is inside the MAC input so an attacker cannot downgrade {@code algId} to NONE,
 *       roll {@code formatVersion} back, mutate {@code reserved}, or re-stamp the
 *       {@code scopeId} without invalidating the MAC.</li>
 *   <li><b>Layer C (encrypting):</b> AES-256-GCM ({@code algId=AES256_GCM}) -
 *       authenticated encryption that provides confidentiality AND authenticity in
 *       one pass, so the GCM tag <em>replaces</em> the HMAC (there is no separate
 *       MAC on an encrypted record). Layer A CRC32C stays for corruption detection.
 *       The layout carries the keyring {@code keyTerm} + per-segment {@code segmentId}
 *       + {@code nonce} so any reader re-derives the key and decrypts with zero
 *       coordination, and rotation runs forward while old-term data stays readable.
 *       The header ({@code MAGIC || formatVersion || algId || reserved}) plus
 *       {@code scopeId || keyTerm || segmentId || nonce} is bound into the GCM AAD, so
 *       those fields are authenticated exactly as the HMAC input authenticates them,
 *       and the per-artifact MAGIC still prevents cross-artifact confusion.</li>
 * </ul>
 *
 * <p><b>The {@code scopeId} (cross-shard/cross-scope splice control).</b> A 4-byte id,
 * authenticated in every posture, that binds the record to the shard (or node-level
 * scope) that wrote it. Per-shard artifacts (WAL entry, snapshot blob, per-shard Raft
 * state) stamp {@code scopeId = gid}; node-level artifacts stamp {@link #NODE_SCOPE}.
 * Every read path passes its <em>expected</em> scope and this codec asserts
 * {@code scopeId == expected}, refusing a mismatch. That assert is the whole
 * cross-shard-splice defense: a record copied verbatim from shard 1 into shard 0 still
 * authenticates as bytes (the integrity/encryption keys are node-wide), so the byte
 * check alone cannot catch it - the record's own authenticated {@code scopeId}
 * announces its true shard, and the assert refuses it. The MAC/GCM tag makes the
 * {@code scopeId} unforgeable <em>in place</em> (an attacker who re-stamps it to match
 * the reader invalidates the tag). Reader-assert + unforgeable-scope together close the
 * splice; neither alone suffices. (In the keyless posture the {@code scopeId} is only
 * CRC-covered, so the assert catches an honest cross-shard artifact but a determined
 * attacker can re-stamp it - keyless carries no adversarial guarantee by design.)
 *
 * <p><b>Posture.</b> An instance carries an optional HMAC {@link SecretKey} and an
 * optional {@link AtRestKeys} encryption source:
 * <ul>
 *   <li><b>encrypting</b> (atRest != null): writes {@code algId=AES256_GCM} -
 *       ciphertext at rest. On read it decrypts {@code algId=AES256_GCM} records
 *       fail-closed (a bad tag or an unknown key term is refused), and, if an HMAC
 *       key was also supplied, still verifies legacy {@code algId=HMAC_SHA256}
 *       records (the enable-encryption-on-an-existing-WAL upgrade path). It REFUSES
 *       {@code algId=NONE} (downgrade).</li>
 *   <li><b>keyed</b> (key != null, atRest == null): writes {@code algId=HMAC_SHA256}
 *       with a MAC, and runs <em>fail-closed</em> on read - it REFUSES an envelope
 *       with {@code algId=NONE}/absent MAC (downgrade) as well as any CRC32C or MAC
 *       mismatch.</li>
 *   <li><b>keyless</b> (key == null, atRest == null): writes {@code algId=NONE}
 *       (Layer A only) and verifies CRC32C. Structurally-absent bytes (torn / first
 *       boot / a foreign magic) still return {@code null} from {@link #unwrapOrNull};
 *       callers that require every record to be enveloped treat that null as a refusal.</li>
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
    public static final short FORMAT_VERSION = 3;

    /** Header before the scopeId: magic(4) + formatVersion(2) + algId(1) + reserved(1). */
    public static final int HEADER_SIZE = 8;
    /** The authenticated scope marker (shard/node id) that sits immediately after the header. */
    public static final int SCOPE_ID_SIZE = 4;
    /** CRC32C trailer size. */
    public static final int CRC_SIZE = 4;
    /** HMAC-SHA-256 output size. */
    public static final int MAC_SIZE = 32;

    /**
     * The scope stamped on node-level (not per-shard) artifacts. Per-shard artifacts
     * stamp {@code gid}, which is frozen to the range {@code [0, NODE_SCOPE)} - so a
     * per-shard reader can never be fooled by a node-level artifact colliding on scope.
     * {@code 0xFFFFFFFF} is deliberately reserved and illegal as a {@code gid}.
     */
    public static final int NODE_SCOPE = 0xFFFFFFFF;

    /** Smallest structurally-valid envelope: header + scopeId + CRC (empty payload, NONE posture). */
    private static final int MIN_ENVELOPE_SIZE = HEADER_SIZE + SCOPE_ID_SIZE + CRC_SIZE; // 16

    /** algId: no authentication - Layer A only (keyless). */
    public static final byte ALG_NONE = 0;
    /** algId: HMAC-SHA-256 authentication (Layer B, keyed). */
    public static final byte ALG_HMAC_SHA256 = 1;
    /** algId: AES-256-GCM authenticated encryption (Layer C, encrypting). */
    public static final byte ALG_AES256_GCM = 2;

    private static final String HMAC = "HmacSHA256";

    // AES-256-GCM (algId=2) layout constants. The encrypted envelope is
    //   [ header:8 ][ scopeId:4 ][ keyTerm:4 ][ segmentId:16 ][ nonce:12 ][ ciphertext+tag ][ CRC32C:4 ]
    // and the AAD is the whole ENC_PREFIX (header + scopeId + keyTerm + segmentId + nonce), so every
    // header/routing field is authenticated by the GCM tag just as the HMAC input is.
    private static final String GCM_TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_TAG_SIZE = GCM_TAG_BITS / 8;   // 16
    private static final int KEY_TERM_SIZE = 4;
    /** header(8) + scopeId(4) + keyTerm(4) + segmentId(16) + nonce(12) = the AAD-covered prefix. */
    private static final int ENC_PREFIX_SIZE =
            HEADER_SIZE + SCOPE_ID_SIZE + KEY_TERM_SIZE + AtRestKeys.SEGMENT_ID_LEN + AtRestKeys.NONCE_LEN; // 44
    private static final int ENC_MIN_SIZE = ENC_PREFIX_SIZE + GCM_TAG_SIZE + CRC_SIZE;                      // 64

    /** One {@link Cipher} per thread - GCM is stateful per operation; each group's owner thread reuses its own. */
    private static final ThreadLocal<Cipher> GCM_CIPHER = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance(GCM_TRANSFORM);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/GCM/NoPadding unavailable", e);
        }
    });

    /** The HMAC key (Layer B), or {@code null}. Also used to READ legacy HMAC records under encryption. */
    private final SecretKey key;
    /** The at-rest encryption key source (Layer C), or {@code null} when not encrypting. */
    private final AtRestKeys atRest;

    /**
     * Creates an envelope codec.
     *
     * @param key the HMAC-SHA-256 key for keyed (fail-closed) mode, or {@code null}
     *            for keyless (Layer A only) mode
     */
    public IntegrityEnvelope(SecretKey key) {
        this(key, null);
    }

    /**
     * Creates an envelope codec with an optional at-rest encryption source.
     *
     * @param key    the HMAC-SHA-256 key (used to READ legacy {@code algId=HMAC_SHA256}
     *               records during an enable-encryption upgrade), or {@code null}
     * @param atRest the AES-256-GCM key source; when non-null this envelope ENCRYPTS on
     *               write ({@code algId=AES256_GCM}) and decrypts on read
     */
    public IntegrityEnvelope(SecretKey key, AtRestKeys atRest) {
        this.key = key;
        this.atRest = atRest;
    }

    /** A keyless envelope (Layer A only). */
    public static IntegrityEnvelope keyless() {
        return new IntegrityEnvelope(null, null);
    }

    /**
     * An encrypting envelope (Layer C): writes AES-256-GCM ciphertext.
     *
     * @param atRest       the encryption key source (non-null)
     * @param legacyReadKey the HMAC key for reading legacy {@code algId=HMAC_SHA256}
     *                      records written before encryption was enabled, or {@code null}
     */
    public static IntegrityEnvelope encrypting(AtRestKeys atRest, SecretKey legacyReadKey) {
        return new IntegrityEnvelope(legacyReadKey, Objects.requireNonNull(atRest, "atRest"));
    }

    /** Whether this envelope carries an HMAC key (Layer B). */
    public boolean isKeyed() {
        return key != null;
    }

    /** Whether this envelope encrypts on write (Layer C, AES-256-GCM). */
    public boolean isEncrypting() {
        return atRest != null;
    }

    /** Whether this reader refuses unauthenticated / absent-under-key bytes (keyed or encrypting). */
    private boolean isAuthenticated() {
        return isKeyed() || isEncrypting();
    }

    /**
     * Wraps {@code payload} in an integrity envelope for {@code (magic, scopeId)}.
     * <p>
     * Layout: {@code [magic][formatVersion][algId][reserved][scopeId][payload][MAC?][CRC32C]}.
     * The MAC (present iff keyed) covers {@code magic||formatVersion||algId||reserved||scopeId||payload}.
     * The CRC32C covers everything preceding it (header + scopeId + payload + MAC).
     *
     * @param magic   the artifact-specific magic (distinct per artifact)
     * @param scopeId the scope this record belongs to ({@code gid} for a per-shard artifact,
     *                {@link #NODE_SCOPE} for a node-level one) - authenticated in every posture
     * @param payload the bytes to protect (non-null, may be empty)
     * @return the enveloped bytes
     */
    public byte[] wrap(int magic, int scopeId, byte[] payload) {
        if (isEncrypting()) {
            return wrapEncrypted(magic, scopeId, payload);
        }
        byte algId = isKeyed() ? ALG_HMAC_SHA256 : ALG_NONE;
        int macLen = isKeyed() ? MAC_SIZE : 0;
        int total = HEADER_SIZE + SCOPE_ID_SIZE + payload.length + macLen + CRC_SIZE;
        byte[] out = new byte[total];
        ByteBuffer buf = ByteBuffer.wrap(out);
        buf.putInt(magic);
        buf.putShort(FORMAT_VERSION);
        buf.put(algId);
        buf.put((byte) 0); // reserved
        buf.putInt(scopeId);
        buf.put(payload);

        if (isKeyed()) {
            // MAC over [magic][formatVersion][algId][reserved][scopeId][payload] - every
            // header field (including the scopeId) plus the payload. The reserved byte is
            // authenticated (not merely CRC-covered) so it carries no malleability if a
            // future version assigns it meaning; the scopeId is authenticated so an
            // attacker cannot re-stamp a record's shard in place.
            byte[] mac = computeMac(magic, algId, (byte) 0, scopeId, payload);
            buf.put(mac);
        }

        // CRC32C over everything preceding the trailer (header + scopeId + payload + MAC).
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
     * must distinguish "legit absent / torn" from "tampered", use {@link #unwrapOrNull}.
     *
     * @param expectedMagic   the artifact magic the caller expects
     * @param expectedScopeId the scope the caller expects ({@code gid} / {@link #NODE_SCOPE})
     * @param enveloped       the bytes produced by {@link #wrap}
     * @return the original payload
     * @throws IntegrityException on too-short input, wrong magic, unknown/rolled
     *                            formatVersion, scope mismatch, CRC32C mismatch, MAC
     *                            mismatch, or (when keyed) algId=NONE/missing MAC (downgrade)
     */
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
     * Unwraps an envelope, returning the payload, or {@code null} ONLY when the
     * bytes are structurally absent - too short to carry the header+scopeId+trailer, or
     * lacking the expected magic entirely (first boot / a torn-short artifact / foreign
     * bytes a keyless reader tolerates).
     * <p>
     * Once the bytes ARE structurally an envelope for {@code expectedMagic} but
     * fail verification (CRC32C mismatch, scope mismatch, MAC mismatch, rolled
     * formatVersion, or, when keyed, a downgrade to algId=NONE), this THROWS
     * {@link IntegrityException}. It never silently returns {@code null} for a
     * tampered-but-present envelope. A torn/absent artifact is tolerated; a tampered
     * one fails loud.
     *
     * @param expectedMagic   the artifact magic the caller expects
     * @param expectedScopeId the scope the caller expects ({@code gid} / {@link #NODE_SCOPE})
     * @param enveloped       the candidate bytes (may be null)
     * @return the payload, or {@code null} if structurally absent
     * @throws IntegrityException if structurally present but verification fails
     */
    public byte[] unwrapOrNull(int expectedMagic, int expectedScopeId, byte[] enveloped) {
        // Need at least the 4-byte magic to decide whether these bytes even claim to
        // be our envelope. Below that they are structurally absent (torn, first boot,
        // or a short foreign artifact) for every posture.
        if (enveloped == null || enveloped.length < Integer.BYTES) {
            return null;
        }

        ByteBuffer buf = ByteBuffer.wrap(enveloped);
        int magic = buf.getInt();
        if (magic != expectedMagic) {
            // A full-length buffer that does not carry our magic is unauthenticated
            // input - a keyed OR encrypting reader refuses it (fail-closed). A buffer
            // below the envelope floor is treated as structurally absent (torn, first
            // boot, or a short foreign artifact) for every posture.
            if (isAuthenticated() && enveloped.length >= HEADER_SIZE + CRC_SIZE) {
                throw new IntegrityException(
                        "expected integrity envelope magic 0x" + Integer.toHexString(expectedMagic)
                                + " but found 0x" + Integer.toHexString(magic)
                                + " (unauthenticated/foreign bytes refused under a configured key)");
            }
            // Keyless (foreign bytes tolerated as absent) or a sub-floor buffer of any
            // posture (structurally absent).
            return null;
        }

        // The magic matches: these bytes ARE meant to be our envelope, so any further
        // structural/verification failure FAILS LOUD (never null). Below the v3 floor
        // (header + scopeId + CRC) we cannot even read the scopeId + CRC without
        // underflowing, so a buffer that claims our magic but is that short is a
        // truncation/tamper under a key - a deliberate refusal. Keyless keeps absent
        // semantics.
        if (enveloped.length < MIN_ENVELOPE_SIZE) {
            if (isAuthenticated()) {
                throw new IntegrityException("integrity envelope truncated (magic present, length "
                        + enveloped.length + ") for magic 0x" + Integer.toHexString(expectedMagic));
            }
            return null;
        }

        // Verify CRC32C FIRST, before any version-dependent read. The CRC is a fixed
        // trailer over a fixed range, so it needs no version to locate or compute; running
        // it first means a bit-flip anywhere in the header (magic already matched, but the
        // version/algId/reserved/scopeId bytes, payload, or MAC) surfaces as a clear
        // corruption error rather than a misleading "unsupported version". The version and
        // scopeId are then read from CRC-validated bytes. This is the FrameCodec discipline
        // applied uniformly to every posture, GCM included.
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
        byte reserved = buf.get(); // folded into the MAC input below

        // The reserved byte is MUST-be-zero: it is the pre-agreed forward-compat escape
        // slot. Rejecting a non-zero value fails closed in EVERY posture (keyless too,
        // where the MAC does not cover it), so a future writer that assigns it meaning can
        // never be silently mis-parsed by a v3 reader.
        if (reserved != 0) {
            throw new IntegrityException("integrity envelope reserved byte must be zero (found "
                    + (reserved & 0xFF) + ") for magic 0x" + Integer.toHexString(expectedMagic));
        }

        // scopeId: the sole cross-shard/cross-scope splice control. It is read from
        // CRC-validated bytes and MUST match the reader's expected scope in EVERY posture.
        // The assert catches an honest cross-shard artifact (the record's authenticated
        // scopeId announces its true shard); the MAC (keyed) / GCM tag (encrypting) below
        // then makes the scopeId unforgeable in place, so an attacker cannot re-stamp it to
        // pass this assert without failing authentication. Both together close the splice.
        int scopeId = buf.getInt();
        if (scopeId != expectedScopeId) {
            throw new IntegrityException("integrity envelope scope mismatch for magic 0x"
                    + Integer.toHexString(expectedMagic) + ": record scopeId=0x"
                    + Integer.toHexString(scopeId) + " but reader expected 0x"
                    + Integer.toHexString(expectedScopeId) + " (cross-shard/scope artifact refused)");
        }

        // Layer C: an AES-256-GCM record has a different body layout (keyTerm/segmentId/
        // nonce/ciphertext instead of payload/MAC), so it is handled by its own reader,
        // which fails closed on a bad tag, an unknown key term, or truncation. The CRC and
        // scopeId are already verified above, so that reader trusts the prefix bytes.
        if (algId == ALG_AES256_GCM) {
            return unwrapEncrypted(expectedMagic, enveloped);
        }

        int macLen;
        if (algId == ALG_NONE) {
            macLen = 0;
            if (isAuthenticated()) {
                // Downgrade attempt: a keyed OR encrypting reader refuses an
                // unauthenticated artifact. Posture, not just bytes, defeats strip-the-MAC
                // (and, under encryption, strip-the-ciphertext-to-plaintext).
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

        // Length must exactly account for header + scopeId + payload + MAC + CRC.
        int payloadStart = HEADER_SIZE + SCOPE_ID_SIZE;
        int payloadLen = enveloped.length - payloadStart - macLen - CRC_SIZE;
        if (payloadLen < 0) {
            throw new IntegrityException("integrity envelope truncated for magic 0x"
                    + Integer.toHexString(expectedMagic) + " (length " + enveloped.length + ")");
        }

        byte[] payload = Arrays.copyOfRange(enveloped, payloadStart, payloadStart + payloadLen);

        if (macLen > 0) {
            byte[] storedMac = Arrays.copyOfRange(
                    enveloped, payloadStart + payloadLen, payloadStart + payloadLen + MAC_SIZE);
            if (!isKeyed()) {
                // A keyless reader cannot verify a keyed artifact - refuse loudly
                // rather than silently trusting an unverifiable MAC.
                throw new IntegrityException(
                        "keyed integrity envelope encountered by a keyless reader for magic 0x"
                                + Integer.toHexString(expectedMagic));
            }
            byte[] computedMac = computeMac(magic, algId, reserved, scopeId, payload);
            // Constant-time compare (MessageDigest.isEqual).
            if (!MessageDigest.isEqual(computedMac, storedMac)) {
                throw new IntegrityException("integrity MAC mismatch for magic 0x"
                        + Integer.toHexString(expectedMagic) + " (tamper detected)");
            }
        }

        return payload;
    }

    /**
     * Verifies the CRC32C trailer over {@code [0, crcOffset)} before any version-dependent
     * read. Throws {@link IntegrityException} on mismatch (corruption / bit-flip).
     */
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

    /** HMAC-SHA-256 over: magic, formatVersion, algId, reserved, scopeId, payload (in that order). */
    private byte[] computeMac(int magic, byte algId, byte reserved, int scopeId, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            // magic(4) + version(2) + algId(1) + reserved(1) + scopeId(4)
            ByteBuffer hdr = ByteBuffer.allocate(HEADER_SIZE + SCOPE_ID_SIZE);
            hdr.putInt(magic);
            hdr.putShort(FORMAT_VERSION);
            hdr.put(algId);
            hdr.put(reserved);
            hdr.putInt(scopeId);
            mac.update(hdr.array());
            mac.update(payload);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable or bad key", e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Layer C: AES-256-GCM authenticated encryption (algId=2). The GCM tag subsumes the HMAC.
    // ---------------------------------------------------------------------------------------------

    /**
     * Wraps {@code payload} as an AES-256-GCM encrypted envelope. Layout:
     * {@code [magic][formatVersion][algId=2][reserved][scopeId][keyTerm][segmentId][nonce][ciphertext+tag][CRC32C]}.
     * The AAD is the whole prefix {@code [header][scopeId][keyTerm][segmentId][nonce]}, so all those
     * fields are authenticated by the tag. A fresh, never-reused {@code (segmentId, nonce)} for this DEK
     * is issued by {@link AtRestKeys#nextSeal(int)} - the sole guarantor of GCM's no-(key,nonce)-reuse
     * invariant. The {@code scopeId} is AAD-only and MUST NOT key the segment or split the nonce counter
     * per shard (segments are keyed by MAGIC and node-global; a per-shard counter split would break GCM's
     * (key,nonce) uniqueness).
     */
    private byte[] wrapEncrypted(int magic, int scopeId, byte[] payload) {
        AtRestKeys.Seal seal = atRest.nextSeal(magic);
        byte[] segmentId = seal.segmentId();
        byte[] nonce = seal.nonce();

        int total = ENC_PREFIX_SIZE + payload.length + GCM_TAG_SIZE + CRC_SIZE;
        byte[] out = new byte[total];
        ByteBuffer buf = ByteBuffer.wrap(out);
        buf.putInt(magic);
        buf.putShort(FORMAT_VERSION);
        buf.put(ALG_AES256_GCM);
        buf.put((byte) 0); // reserved
        buf.putInt(scopeId);        // authenticated (AAD) between header and keyTerm
        buf.putInt(seal.keyTerm());
        buf.put(segmentId);
        buf.put(nonce);

        // AAD = the ENC_PREFIX bytes just written (header + scopeId + keyTerm + segmentId + nonce).
        // Binding the per-artifact MAGIC here is what stops a snapshot ciphertext being replayed as a
        // WAL record; binding the scopeId is what makes a cross-shard splice fail the tag if re-stamped.
        byte[] aad = Arrays.copyOfRange(out, 0, ENC_PREFIX_SIZE);
        byte[] cipherText = gcmEncrypt(seal.dek(), nonce, aad, payload);
        buf.put(cipherText); // ciphertext + 16-byte tag

        CRC32C crc = new CRC32C();
        crc.update(out, 0, total - CRC_SIZE);
        buf.putInt((int) crc.getValue());
        return out;
    }

    /**
     * Reads an AES-256-GCM envelope, verifying CRC32C (corruption) then the GCM tag (tamper/auth).
     * FAILS CLOSED: a truncated body, a CRC mismatch, an unknown key term, or a bad GCM tag all throw
     * {@link IntegrityException} - a record that cannot be authentically decrypted is refused, never
     * returned as {@code null} or silently skipped. The CRC and scopeId are already validated by
     * {@link #unwrapOrNull} before it dispatches here.
     */
    private byte[] unwrapEncrypted(int expectedMagic, byte[] enveloped) {
        if (!isEncrypting()) {
            // A non-encrypting reader cannot decrypt - refuse loudly rather than mis-parse ciphertext.
            throw new IntegrityException(
                    "AES-256-GCM envelope encountered by a non-encrypting reader for magic 0x"
                            + Integer.toHexString(expectedMagic));
        }
        if (enveloped.length < ENC_MIN_SIZE) {
            throw new IntegrityException("AES-256-GCM envelope truncated (length " + enveloped.length
                    + ", min " + ENC_MIN_SIZE + ") for magic 0x" + Integer.toHexString(expectedMagic));
        }

        // CRC32C (corruption / bit-flip) and the scopeId assert have already run in unwrapOrNull, so the
        // prefix fields below are read from CRC-validated, scope-checked bytes.
        int crcOffset = enveloped.length - CRC_SIZE;
        ByteBuffer buf = ByteBuffer.wrap(enveloped);
        buf.position(HEADER_SIZE + SCOPE_ID_SIZE); // skip magic/formatVersion/algId/reserved/scopeId (validated)
        int keyTerm = buf.getInt();
        byte[] segmentId = new byte[AtRestKeys.SEGMENT_ID_LEN];
        buf.get(segmentId);
        byte[] nonce = new byte[AtRestKeys.NONCE_LEN];
        buf.get(nonce);
        int cipherLen = crcOffset - ENC_PREFIX_SIZE;
        byte[] cipherText = new byte[cipherLen];
        buf.get(cipherText);

        // Fail-closed: an unknown key term throws IntegrityException (no key -> no decrypt).
        SecretKey dek = atRest.resolveDek(keyTerm, segmentId);
        // AAD = the same ENC_PREFIX bytes the writer bound; a flipped header/scopeId/keyTerm/segmentId/
        // nonce changes the AAD and makes the tag fail.
        byte[] aad = Arrays.copyOfRange(enveloped, 0, ENC_PREFIX_SIZE);
        try {
            return gcmDecrypt(dek, nonce, aad, cipherText);
        } catch (AEADBadTagException e) {
            // The authenticity failure (tampered header/scopeId/keyTerm/segmentId/nonce/ciphertext, or
            // the wrong key) surfaces as a refusal - the encryption analogue of the HMAC-mismatch throw.
            throw new IntegrityException("AES-256-GCM authentication failed (tamper detected) for magic 0x"
                    + Integer.toHexString(expectedMagic), e);
        } catch (GeneralSecurityException e) {
            throw new IntegrityException("AES-256-GCM decrypt error for magic 0x"
                    + Integer.toHexString(expectedMagic), e);
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
