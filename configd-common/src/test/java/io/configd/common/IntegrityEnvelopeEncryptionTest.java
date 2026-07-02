package io.configd.common;

import io.configd.common.kms.KeyId;
import io.configd.common.kms.RootKey;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
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
 */
class IntegrityEnvelopeEncryptionTest {

    private static final int MAGIC = 0x5257_414C;       // "RWAL"
    private static final int OTHER_MAGIC = 0x5253_4E50; // "RSNP"

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

    // ---- round trip ----

    @Test
    void encryptDecryptRoundTrip() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        assertTrue(env.isEncrypting());
        byte[] wrapped = env.wrap(MAGIC, payload());
        assertEquals(IntegrityEnvelope.ALG_AES256_GCM, wrapped[6]); // algId byte
        assertArrayEquals(payload(), env.unwrap(MAGIC, wrapped));
    }

    @Test
    void emptyPayloadRoundTrips() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        byte[] wrapped = env.wrap(MAGIC, new byte[0]);
        assertArrayEquals(new byte[0], env.unwrap(MAGIC, wrapped));
    }

    // ---- confidentiality: the plaintext is NOT on disk ----

    @Test
    void ciphertextContainsNoPlaintext() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        byte[] wrapped = env.wrap(MAGIC, payload());
        String asLatin1 = new String(wrapped, StandardCharsets.ISO_8859_1);
        assertFalse(asLatin1.contains(SECRET_VALUE), "the secret value must not appear in the ciphertext");
        assertFalse(asLatin1.contains("config-value="), "no plaintext fragment may appear");
        // sanity: the same bytes under a NON-encrypting keyed envelope DO contain the plaintext
        byte[] hmacWrapped = new IntegrityEnvelope(hmacKey()).wrap(MAGIC, payload());
        assertTrue(new String(hmacWrapped, StandardCharsets.ISO_8859_1).contains(SECRET_VALUE),
                "control: the integrity-only path leaves plaintext on disk");
    }

    @Test
    void twoEncryptionsOfTheSamePayloadDifferAndUseDistinctNonces() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        byte[] a = env.wrap(MAGIC, payload());
        byte[] b = env.wrap(MAGIC, payload());
        assertFalse(Arrays.equals(a, b), "distinct nonces -> distinct ciphertext for identical plaintext");
        // nonce lives at bytes [8+4+16, 8+4+16+12) = [28,40); must differ
        byte[] nonceA = Arrays.copyOfRange(a, 28, 40);
        byte[] nonceB = Arrays.copyOfRange(b, 28, 40);
        assertFalse(Arrays.equals(nonceA, nonceB));
    }

    // ---- authenticity: any tampered byte fails closed ----

    @Test
    void tamperingCiphertextFailsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        byte[] wrapped = env.wrap(MAGIC, payload());
        // flip a byte inside the ciphertext (after the 40-byte prefix, before the 4-byte CRC)
        int ctPos = 45;
        wrapped[ctPos] ^= 0x01;
        // NOTE: the CRC will now also mismatch; fix the CRC to prove the GCM tag alone catches it.
        recomputeCrc(wrapped);
        assertThrows(IntegrityException.class, () -> env.unwrap(MAGIC, wrapped));
    }

    @Test
    void tamperingAadFieldsFailsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        // flip one byte in each AAD-covered field (segmentId at 8+4=12, nonce at 28), fixing CRC each time
        for (int pos : new int[]{12, 28, 8 /*keyTerm*/, 7 /*reserved*/}) {
            byte[] wrapped = env.wrap(MAGIC, payload());
            wrapped[pos] ^= 0x01;
            recomputeCrc(wrapped);
            assertThrows(IntegrityException.class, () -> env.unwrap(MAGIC, wrapped),
                    "flipping byte " + pos + " must fail the GCM tag");
        }
    }

    @Test
    void crcMismatchFailsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        byte[] wrapped = env.wrap(MAGIC, payload());
        wrapped[wrapped.length - 1] ^= 0x01; // corrupt the CRC trailer
        assertThrows(IntegrityException.class, () -> env.unwrap(MAGIC, wrapped));
    }

    @Test
    void wrongMagicFailsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        byte[] wrapped = env.wrap(MAGIC, payload());
        // reading with a different expected magic: the AAD binds MAGIC, so the tag fails
        assertThrows(IntegrityException.class, () -> env.unwrap(OTHER_MAGIC, wrapped));
    }

    @Test
    void truncatedEncryptedEnvelopeFailsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        byte[] wrapped = env.wrap(MAGIC, payload());
        byte[] truncated = Arrays.copyOf(wrapped, wrapped.length - 10);
        assertThrows(IntegrityException.class, () -> env.unwrap(MAGIC, truncated));
    }

    // ---- key mismatch: a different root cannot decrypt ----

    @Test
    void differentRootKeyCannotDecrypt() {
        byte[] wrapped = IntegrityEnvelope.encrypting(keys(), null).wrap(MAGIC, payload());
        byte[] otherMaterial = new byte[32];
        Arrays.fill(otherMaterial, (byte) 0x01);
        SegmentKeyManager otherKeys =
                new SegmentKeyManager(new RootKey(otherMaterial, new KeyId("local", "kid", 1)));
        IntegrityEnvelope wrongReader = IntegrityEnvelope.encrypting(otherKeys, null);
        assertThrows(IntegrityException.class, () -> wrongReader.unwrap(MAGIC, wrapped));
    }

    // ---- downgrade / posture refusals ----

    @Test
    void encryptingReaderRefusesAlgNoneDowngrade() {
        // a NONE (keyless) artifact must be refused by an encrypting reader (fail-closed)
        byte[] none = IntegrityEnvelope.keyless().wrap(MAGIC, payload());
        IntegrityEnvelope enc = IntegrityEnvelope.encrypting(keys(), null);
        assertThrows(IntegrityException.class, () -> enc.unwrap(MAGIC, none));
    }

    @Test
    void nonEncryptingReaderRefusesEncryptedRecord() {
        byte[] wrapped = IntegrityEnvelope.encrypting(keys(), null).wrap(MAGIC, payload());
        // keyed-but-not-encrypting reader cannot decrypt algId=2 -> refuse loudly
        assertThrows(IntegrityException.class, () -> new IntegrityEnvelope(hmacKey()).unwrap(MAGIC, wrapped));
        // keyless reader likewise
        assertThrows(IntegrityException.class, () -> IntegrityEnvelope.keyless().unwrap(MAGIC, wrapped));
    }

    // ---- migration: an encrypting reader still verifies legacy HMAC records ----

    @Test
    void encryptingReaderReadsLegacyHmacRecords() {
        // records written under the old integrity-only posture (algId=1)
        byte[] legacy = new IntegrityEnvelope(hmacKey()).wrap(MAGIC, payload());
        assertEquals(IntegrityEnvelope.ALG_HMAC_SHA256, legacy[6]);
        // an encrypting envelope that ALSO carries the HMAC key reads them (the upgrade path)
        IntegrityEnvelope enc = IntegrityEnvelope.encrypting(keys(), hmacKey());
        assertArrayEquals(payload(), enc.unwrap(MAGIC, legacy));
        // but new writes are encrypted (algId=2)
        assertEquals(IntegrityEnvelope.ALG_AES256_GCM, enc.wrap(MAGIC, payload())[6]);
    }

    @Test
    void encryptingReaderWithoutHmacKeyRefusesLegacyHmacRecord() {
        byte[] legacy = new IntegrityEnvelope(hmacKey()).wrap(MAGIC, payload());
        IntegrityEnvelope enc = IntegrityEnvelope.encrypting(keys(), null); // no HMAC read key
        assertThrows(IntegrityException.class, () -> enc.unwrap(MAGIC, legacy));
    }

    // ---- per-artifact isolation ----

    @Test
    void differentMagicsProduceDifferentSegments() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        byte[] wal = env.wrap(MAGIC, payload());
        byte[] snap = env.wrap(OTHER_MAGIC, payload());
        byte[] walSeg = Arrays.copyOfRange(wal, 12, 28);
        byte[] snapSeg = Arrays.copyOfRange(snap, 12, 28);
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
