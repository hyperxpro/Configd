package io.configd.common;

import io.configd.common.kms.KeyId;
import io.configd.common.kms.RootKey;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent red-team pass over {@link IntegrityEnvelope}'s frozen, fail-closed contract.
 *
 * <p>Each test performs a byte-level attack on a real envelope and asserts that the reader refuses
 * (throws {@link IntegrityException}) rather than best-effort-parsing. These go beyond the builder's
 * tests: they cover version 0 and the {@code 0xFFFF} reserved escape (the builder only rolled the
 * version down), unknown or dispatch-confused {@code algId}s, the reserved-byte MBZ check under the
 * encrypting posture (the builder covered keyless and keyed only), and the sharpest case, cross-artifact
 * confusion under AES-256-GCM, where a repaired outer CRC is not enough because the per-artifact magic
 * is bound into the GCM AAD.
 *
 * <p>All crafted attacks repair the version-independent CRC32C so the reader is forced past the
 * corruption check and onto the version, algId, reserved, or MAC control actually under test - a stale
 * CRC would mask the real behavior behind a corruption error. The header offsets attacked here
 * (version, algId, reserved) sit inside the 8-byte header and are unaffected by the v3 scopeId, which
 * begins at offset 8.
 */
class IntegrityEnvelopeRedteamTest {

    // Real registry magics (see RaftArtifactMagic). Cross-artifact tests wrap under one and read
    // under another to prove the magic is a genuine anti-confusion discriminator, not decoration.
    private static final int SNAP_MAGIC = 0x5253_4E50; // "RSNP"
    private static final int WALE_MAGIC = 0x5257_414C; // "RWAL"
    private static final int SCOPE = 5;                // a per-shard scope; same on wrap+read here

    private static final int OFF_VERSION = 4;  // formatVersion: u16 at offset 4
    private static final int OFF_ALGID = 6;    // algId: u8 at offset 6
    private static final int OFF_RESERVED = 7; // reserved MBZ: u8 at offset 7

    private static SecretKey hmacKey() {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) 0x42);
        return new SecretKeySpec(k, "HmacSHA256");
    }

    private static IntegrityEnvelope encryptingEnvelope() {
        byte[] rootBytes = new byte[32];
        Arrays.fill(rootBytes, (byte) 0x6B);
        RootKey root = new RootKey(rootBytes, new KeyId("local", "test", 1));
        // legacyReadKey=null: a pure AES-256-GCM writer/reader (algId=2).
        return IntegrityEnvelope.encrypting(new SegmentKeyManager(root));
    }

    private static byte[] payload() {
        return "frozen-redteam-payload".getBytes();
    }

    private static void repairCrc(byte[] b) {
        CRC32C crc = new CRC32C();
        crc.update(b, 0, b.length - IntegrityEnvelope.CRC_SIZE);
        ByteBuffer.wrap(b).putInt(b.length - IntegrityEnvelope.CRC_SIZE, (int) crc.getValue());
    }


    @Test
    void versionZeroRejected_keyed() {
        // Attack: roll formatVersion to the reserved-illegal 0 ("unset/torn"), repair CRC.
        IntegrityEnvelope env = new IntegrityEnvelope(hmacKey());
        byte[] w = env.wrap(SNAP_MAGIC, SCOPE, payload());
        w[OFF_VERSION] = 0;
        w[OFF_VERSION + 1] = 0;
        repairCrc(w);
        IntegrityException ex = assertThrows(IntegrityException.class, () -> env.unwrap(SNAP_MAGIC, SCOPE, w));
        assertTrue(ex.getMessage().contains("formatVersion"),
                "version 0 must be refused as unsupported, got: " + ex.getMessage());
    }

    @Test
    void versionZeroRejected_keyless() {
        IntegrityEnvelope env = IntegrityEnvelope.keyless();
        byte[] w = env.wrap(SNAP_MAGIC, SCOPE, payload());
        w[OFF_VERSION] = 0;
        w[OFF_VERSION + 1] = 0;
        repairCrc(w);
        assertThrows(IntegrityException.class, () -> env.unwrap(SNAP_MAGIC, SCOPE, w),
                "version 0 must be refused even keyless (structural, not MAC-dependent)");
    }

    @Test
    void higherVersionRejected_keyed() {
        // Attack: present a newer format (version 4, one past the frozen v3) with a valid CRC. An old
        // grammar must never parse a newer format; the builder only rolled the version down, so this
        // covers rolling it up.
        IntegrityEnvelope env = new IntegrityEnvelope(hmacKey());
        byte[] w = env.wrap(SNAP_MAGIC, SCOPE, payload());
        w[OFF_VERSION] = 0;
        w[OFF_VERSION + 1] = 4;
        repairCrc(w);
        IntegrityException ex = assertThrows(IntegrityException.class, () -> env.unwrap(SNAP_MAGIC, SCOPE, w));
        assertTrue(ex.getMessage().contains("formatVersion"), ex.getMessage());
    }

    @Test
    void reservedEscapeVersionMaxRejected_keyed() {
        // Attack: the u16 "extended version" escape 0xFFFF is reserved-unallocated and must be
        // refused (a future reader knows the slot; a v3 reader fails closed).
        IntegrityEnvelope env = new IntegrityEnvelope(hmacKey());
        byte[] w = env.wrap(SNAP_MAGIC, SCOPE, payload());
        w[OFF_VERSION] = (byte) 0xFF;
        w[OFF_VERSION + 1] = (byte) 0xFF;
        repairCrc(w);
        assertThrows(IntegrityException.class, () -> env.unwrap(SNAP_MAGIC, SCOPE, w),
                "the 0xFFFF reserved-escape version must fail closed");
    }


    @Test
    void unknownAlgIdRejected_keyless() {
        // Attack: set algId to an unallocated code (3), neither NONE, HMAC, nor GCM - it must
        // throw, never fall through to a best-effort parse.
        IntegrityEnvelope env = IntegrityEnvelope.keyless();
        byte[] w = env.wrap(SNAP_MAGIC, SCOPE, payload());
        w[OFF_ALGID] = 3;
        repairCrc(w);
        IntegrityException ex = assertThrows(IntegrityException.class, () -> env.unwrap(SNAP_MAGIC, SCOPE, w));
        assertTrue(ex.getMessage().contains("algId"), ex.getMessage());
    }

    @Test
    void unknownAlgIdRejected_keyed() {
        IntegrityEnvelope env = new IntegrityEnvelope(hmacKey());
        byte[] w = env.wrap(SNAP_MAGIC, SCOPE, payload());
        w[OFF_ALGID] = 3;
        repairCrc(w);
        assertThrows(IntegrityException.class, () -> env.unwrap(SNAP_MAGIC, SCOPE, w),
                "an unknown algId must be refused under a key too");
    }

    @Test
    void gcmAlgIdUnderNonEncryptingReaderRejected() {
        // Attack: stamp algId=AES256_GCM(2) onto an otherwise-keyless envelope and hand it to a
        // reader that cannot decrypt. It must refuse loudly rather than mis-parse ciphertext.
        IntegrityEnvelope keyless = IntegrityEnvelope.keyless();
        byte[] w = keyless.wrap(SNAP_MAGIC, SCOPE, payload());
        w[OFF_ALGID] = IntegrityEnvelope.ALG_AES256_GCM;
        repairCrc(w);
        IntegrityException byKeyed = assertThrows(IntegrityException.class,
                () -> new IntegrityEnvelope(hmacKey()).unwrap(SNAP_MAGIC, SCOPE, w));
        assertTrue(byKeyed.getMessage().contains("non-encrypting"), byKeyed.getMessage());
        assertThrows(IntegrityException.class, () -> keyless.unwrap(SNAP_MAGIC, SCOPE, w),
                "a keyless reader must also refuse a GCM-tagged record");
    }

    @Test
    void algNoneUnderEncryptingReaderRejected_downgrade() {
        // Attack: strip authentication (algId=NONE) and present the plaintext to an encrypting
        // reader. Posture, not just bytes, must defeat the strip-to-plaintext downgrade.
        byte[] plain = IntegrityEnvelope.keyless().wrap(SNAP_MAGIC, SCOPE, payload());
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> encryptingEnvelope().unwrap(SNAP_MAGIC, SCOPE, plain));
        assertTrue(ex.getMessage().contains("downgrade"), ex.getMessage());
    }


    @Test
    void reservedNonZeroRejected_encrypting() {
        // Attack: on a real AES-256-GCM envelope, set the MBZ reserved byte non-zero and repair the
        // CRC. The explicit check that reserved is zero must fire before the GCM body is even
        // reached - the reserved slot stays a genuine forward-compatibility door under encryption,
        // not something only covered by the GCM AAD.
        IntegrityEnvelope env = encryptingEnvelope();
        byte[] w = env.wrap(WALE_MAGIC, SCOPE, payload());
        w[OFF_RESERVED] = 1;
        repairCrc(w);
        IntegrityException ex = assertThrows(IntegrityException.class, () -> env.unwrap(WALE_MAGIC, SCOPE, w));
        assertTrue(ex.getMessage().contains("reserved"),
                "a non-zero reserved byte on an encrypted record must fail closed, got: " + ex.getMessage());
    }


    @Test
    void crossArtifactConfusionRejected_keyed() {
        IntegrityEnvelope env = new IntegrityEnvelope(hmacKey());
        byte[] walRecord = env.wrap(WALE_MAGIC, SCOPE, payload());
        assertThrows(IntegrityException.class, () -> env.unwrap(SNAP_MAGIC, SCOPE, walRecord),
                "a WAL-record envelope must not unwrap where a snapshot is expected");
    }

    @Test
    void crossArtifactConfusionRejected_gcmAadBinding() {
        // Attack (the strongest one): a valid encrypted WAL record. Overwrite its leading magic to
        // the snapshot magic and repair the outer CRC, then read it as a snapshot. The magic bytes
        // now match what the reader asked for and the CRC is clean, so nothing structural catches
        // it. The only thing standing between this and a cross-artifact confusion is that the
        // original magic is bound into the GCM AAD: the tag was computed over WALE, the decrypt AAD
        // now says SNAP, so authentication fails. This proves the binding actually holds.
        IntegrityEnvelope env = encryptingEnvelope();
        byte[] walRecord = env.wrap(WALE_MAGIC, SCOPE, payload());

        ByteBuffer.wrap(walRecord).putInt(0, SNAP_MAGIC);
        repairCrc(walRecord);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(SNAP_MAGIC, SCOPE, walRecord),
                "a magic-swapped encrypted record must fail the GCM AAD authentication, not decrypt");
        assertTrue(ex.getMessage().contains("authentication failed"),
                "the magic-AAD binding must surface as an auth failure, got: " + ex.getMessage());
    }

    // Non-vacuity: the encrypting round-trip still works, so the attacks above prove refusal, not
    // a broken codec.

    @Test
    void encryptingRoundTripStillWorks() {
        IntegrityEnvelope env = encryptingEnvelope();
        byte[] w = env.wrap(WALE_MAGIC, SCOPE, payload());
        assertArrayEquals(payload(), env.unwrap(WALE_MAGIC, SCOPE, w),
                "a well-formed encrypted record must still round-trip");
    }
}
