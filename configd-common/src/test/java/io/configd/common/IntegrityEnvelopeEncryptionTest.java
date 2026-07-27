package io.configd.common;

import io.configd.common.kms.KeyId;
import io.configd.common.kms.RootKey;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codec-level tests for the AES-256-GCM (algId=2) encrypting path of {@link IntegrityEnvelope}.
 * <p>
 * v3 GCM layout offsets used below:
 * {@code header(8) | scopeId@8 | keyTerm@12 | segmentId@16..32 | nonce@32..44 | ciphertext@44 | CRC@len-4}.
 */
class IntegrityEnvelopeEncryptionTest {

    private static final int MAGIC = 0x5257_414C;       // "RWAL"
    private static final int OTHER_MAGIC = 0x5253_4E50; // "RSNP"
    private static final int SCOPE = 3;
    private static final int OTHER_SCOPE = 7;

    private static final String SECRET_VALUE = "super-secret-database-password-12345";

    private static RootKey root() {
        byte[] m = new byte[32];
        Arrays.fill(m, (byte) 0x7E);
        return new RootKey(m, new KeyId("local", "kid", 1));
    }

    private static SegmentKeyManager keys() {
        return new SegmentKeyManager(root());
    }

    private static SecretKey hmacKey() {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) 0x42);
        return new SecretKeySpec(k, "HmacSHA256");
    }

    private static byte[] payload() {
        return ("config-value=" + SECRET_VALUE).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void encryptDecryptRoundTrip() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys());
        assertTrue(env.isEncrypting());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        assertEquals(IntegrityEnvelope.ALG_AES256_GCM, wrapped[6]); // algId byte
        assertArrayEquals(payload(), env.unwrap(MAGIC, SCOPE, wrapped));
    }

    @Test
    void emptyPayloadRoundTrips() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, new byte[0]);
        assertArrayEquals(new byte[0], env.unwrap(MAGIC, SCOPE, wrapped));
    }


    @Test
    void ciphertextContainsNoPlaintext() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        String asLatin1 = new String(wrapped, StandardCharsets.ISO_8859_1);
        assertFalse(asLatin1.contains(SECRET_VALUE), "the secret value must not appear in the ciphertext");
        assertFalse(asLatin1.contains("config-value="), "no plaintext fragment may appear");
        // Control: the same bytes under a non-encrypting keyed envelope do contain the plaintext.
        byte[] hmacWrapped = new IntegrityEnvelope(hmacKey()).wrap(MAGIC, SCOPE, payload());
        assertTrue(new String(hmacWrapped, StandardCharsets.ISO_8859_1).contains(SECRET_VALUE),
                "control: the integrity-only path leaves plaintext on disk");
    }

    @Test
    void twoEncryptionsOfTheSamePayloadDifferAndUseDistinctNonces() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys());
        byte[] a = env.wrap(MAGIC, SCOPE, payload());
        byte[] b = env.wrap(MAGIC, SCOPE, payload());
        assertFalse(Arrays.equals(a, b), "distinct nonces -> distinct ciphertext for identical plaintext");
        // The nonce occupies bytes 32 to 44; it must differ between the two ciphertexts.
        byte[] nonceA = Arrays.copyOfRange(a, 32, 44);
        byte[] nonceB = Arrays.copyOfRange(b, 32, 44);
        assertFalse(Arrays.equals(nonceA, nonceB));
    }


    @Test
    void tamperingCiphertextFailsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        // Flip a byte inside the ciphertext (after the 44-byte prefix, before the 4-byte CRC).
        int ctPos = 49;
        wrapped[ctPos] ^= 0x01;
        // The CRC will now also mismatch; repair it so the GCM tag alone is what catches this.
        recomputeCrc(wrapped);
        assertThrows(IntegrityException.class, () -> env.unwrap(MAGIC, SCOPE, wrapped));
    }

    @Test
    void tamperingAadFieldsFailsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys());
        // Flip one byte in each AAD-covered field, repairing the CRC each time so the GCM tag is
        // the only thing being tested. scopeId at offset 8 is exercised separately (re-stamped to
        // a different scope) by inPlaceScopeForgeFailsGcmTag, so this covers keyTerm, segmentId,
        // nonce, and the reserved byte.
        for (int pos : new int[]{16 /*segmentId*/, 32 /*nonce*/, 12 /*keyTerm*/, 7 /*reserved*/}) {
            byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
            wrapped[pos] ^= 0x01;
            recomputeCrc(wrapped);
            assertThrows(IntegrityException.class, () -> env.unwrap(MAGIC, SCOPE, wrapped),
                    "flipping byte " + pos + " must fail closed");
        }
    }

    @Test
    void crcMismatchFailsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        wrapped[wrapped.length - 1] ^= 0x01; // corrupt the CRC trailer
        assertThrows(IntegrityException.class, () -> env.unwrap(MAGIC, SCOPE, wrapped));
    }

    @Test
    void wrongMagicFailsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        // Reading with a different expected magic: the AAD binds the magic, so the tag fails.
        assertThrows(IntegrityException.class, () -> env.unwrap(OTHER_MAGIC, SCOPE, wrapped));
    }


    @Test
    void scopeMismatchRefusedEncrypting() {
        // A GCM record wrapped under scope 3, read by a reader expecting scope 7, is refused
        // by the scope assert before decryption even runs.
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, OTHER_SCOPE, wrapped));
        assertTrue(ex.getMessage().contains("scope mismatch"), ex.getMessage());
    }

    @Test
    void inPlaceScopeForgeFailsGcmTag() {
        // Re-stamp the scopeId to the reader's expected value and repair the CRC so the scope
        // assert passes - but the tag was computed with scopeId=3 in the AAD, so the GCM tag
        // fails. scopeId is AAD-bound, hence unforgeable in place.
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        ByteBuffer.wrap(wrapped).putInt(IntegrityEnvelope.HEADER_SIZE, OTHER_SCOPE);
        recomputeCrc(wrapped);
        assertThrows(IntegrityException.class, () -> env.unwrap(MAGIC, OTHER_SCOPE, wrapped));
    }

    @Test
    void truncatedEncryptedEnvelopeFailsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        byte[] truncated = Arrays.copyOf(wrapped, wrapped.length - 10);
        assertThrows(IntegrityException.class, () -> env.unwrap(MAGIC, SCOPE, truncated));
    }


    @Test
    void differentRootKeyCannotDecrypt() {
        byte[] wrapped = IntegrityEnvelope.encrypting(keys()).wrap(MAGIC, SCOPE, payload());
        byte[] otherMaterial = new byte[32];
        Arrays.fill(otherMaterial, (byte) 0x01);
        SegmentKeyManager otherKeys =
                new SegmentKeyManager(new RootKey(otherMaterial, new KeyId("local", "kid", 1)));
        IntegrityEnvelope wrongReader = IntegrityEnvelope.encrypting(otherKeys);
        assertThrows(IntegrityException.class, () -> wrongReader.unwrap(MAGIC, SCOPE, wrapped));
    }


    @Test
    void encryptingReaderRefusesAlgNoneDowngrade() {
        byte[] none = IntegrityEnvelope.keyless().wrap(MAGIC, SCOPE, payload());
        IntegrityEnvelope enc = IntegrityEnvelope.encrypting(keys());
        assertThrows(IntegrityException.class, () -> enc.unwrap(MAGIC, SCOPE, none));
    }

    @Test
    void nonEncryptingReaderRefusesEncryptedRecord() {
        byte[] wrapped = IntegrityEnvelope.encrypting(keys()).wrap(MAGIC, SCOPE, payload());
        // A keyed-but-not-encrypting reader cannot decrypt algId=2 and must refuse loudly.
        assertThrows(IntegrityException.class, () -> new IntegrityEnvelope(hmacKey()).unwrap(MAGIC, SCOPE, wrapped));
        // A keyless reader must refuse it too.
        assertThrows(IntegrityException.class, () -> IntegrityEnvelope.keyless().unwrap(MAGIC, SCOPE, wrapped));
    }


    @Test
    void encryptingReaderReadsPreEncryptionHmacRecords() {
        // A record written under the term-versioned HMAC posture (algId=1) before encryption was
        // enabled, keyed by the per-term integrity key from the same keyring the encrypting reader uses.
        SegmentKeyManager km = keys();
        byte[] preEncryption = IntegrityEnvelope.hmac(km).wrap(MAGIC, SCOPE, payload());
        assertEquals(IntegrityEnvelope.ALG_HMAC_SHA256, preEncryption[6]);
        // The encrypting envelope over the same keyring reads them via macKey(keyTerm), the migration path.
        IntegrityEnvelope enc = IntegrityEnvelope.encrypting(km);
        assertArrayEquals(payload(), enc.unwrap(MAGIC, SCOPE, preEncryption));
        // New writes, however, are encrypted (algId=2).
        assertEquals(IntegrityEnvelope.ALG_AES256_GCM, enc.wrap(MAGIC, SCOPE, payload())[6]);
    }

    @Test
    void requireEncryptedRefusesPreEncryptionHmacRecord() {
        SegmentKeyManager km = keys();
        byte[] preEncryption = IntegrityEnvelope.hmac(km).wrap(MAGIC, SCOPE, payload());
        // requireEncrypted enforces a lockdown that refuses a legacy algId=1 record.
        IntegrityEnvelope strict = IntegrityEnvelope.encrypting(km, true);
        assertThrows(IntegrityException.class, () -> strict.unwrap(MAGIC, SCOPE, preEncryption));
    }


    @Test
    void differentMagicsProduceDifferentSegments() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys());
        byte[] wal = env.wrap(MAGIC, SCOPE, payload());
        byte[] snap = env.wrap(OTHER_MAGIC, SCOPE, payload());
        byte[] walSeg = Arrays.copyOfRange(wal, 16, 32);
        byte[] snapSeg = Arrays.copyOfRange(snap, 16, 32);
        assertNotEquals(Arrays.toString(walSeg), Arrays.toString(snapSeg));
    }

    /** Recompute the CRC32C trailer so a test proves the GCM tag (not just the CRC) catches a tamper. */
    private static void recomputeCrc(byte[] enveloped) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(enveloped, 0, enveloped.length - 4);
        int v = (int) crc.getValue();
        enveloped[enveloped.length - 4] = (byte) (v >>> 24);
        enveloped[enveloped.length - 3] = (byte) (v >>> 16);
        enveloped[enveloped.length - 2] = (byte) (v >>> 8);
        enveloped[enveloped.length - 1] = (byte) v;
    }
}
