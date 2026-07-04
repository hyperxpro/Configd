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

    // ---- round trip ----

    @Test
    void encryptDecryptRoundTrip() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        assertTrue(env.isEncrypting());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        assertEquals(IntegrityEnvelope.ALG_AES256_GCM, wrapped[6]); // algId byte
        assertArrayEquals(payload(), env.unwrap(MAGIC, SCOPE, wrapped));
    }

    @Test
    void emptyPayloadRoundTrips() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        byte[] wrapped = env.wrap(MAGIC, SCOPE, new byte[0]);
        assertArrayEquals(new byte[0], env.unwrap(MAGIC, SCOPE, wrapped));
    }

    // ---- confidentiality: the plaintext is NOT on disk ----

    @Test
    void ciphertextContainsNoPlaintext() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        String asLatin1 = new String(wrapped, StandardCharsets.ISO_8859_1);
        assertFalse(asLatin1.contains(SECRET_VALUE), "the secret value must not appear in the ciphertext");
        assertFalse(asLatin1.contains("config-value="), "no plaintext fragment may appear");
        // sanity: the same bytes under a NON-encrypting keyed envelope DO contain the plaintext
        byte[] hmacWrapped = new IntegrityEnvelope(hmacKey()).wrap(MAGIC, SCOPE, payload());
        assertTrue(new String(hmacWrapped, StandardCharsets.ISO_8859_1).contains(SECRET_VALUE),
                "control: the integrity-only path leaves plaintext on disk");
    }

    @Test
    void twoEncryptionsOfTheSamePayloadDifferAndUseDistinctNonces() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        byte[] a = env.wrap(MAGIC, SCOPE, payload());
        byte[] b = env.wrap(MAGIC, SCOPE, payload());
        assertFalse(Arrays.equals(a, b), "distinct nonces -> distinct ciphertext for identical plaintext");
        // nonce lives at bytes [8+4+4+16, +12) = [32,44); must differ
        byte[] nonceA = Arrays.copyOfRange(a, 32, 44);
        byte[] nonceB = Arrays.copyOfRange(b, 32, 44);
        assertFalse(Arrays.equals(nonceA, nonceB));
    }

    // ---- authenticity: any tampered byte fails closed ----

    @Test
    void tamperingCiphertextFailsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        // flip a byte inside the ciphertext (after the 44-byte prefix, before the 4-byte CRC)
        int ctPos = 49;
        wrapped[ctPos] ^= 0x01;
        // NOTE: the CRC will now also mismatch; fix the CRC to prove the GCM tag alone catches it.
        recomputeCrc(wrapped);
        assertThrows(IntegrityException.class, () -> env.unwrap(MAGIC, SCOPE, wrapped));
    }

    @Test
    void tamperingAadFieldsFailsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        // flip one byte in each AAD-covered field, fixing CRC each time so the GCM tag is the control.
        // scopeId@8 is caught earlier by the scope assert (a re-stamp to a DIFFERENT scope), so it is
        // covered by inPlaceScopeForgeFailsGcmTag; here we hit keyTerm/segmentId/nonce/reserved.
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
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        wrapped[wrapped.length - 1] ^= 0x01; // corrupt the CRC trailer
        assertThrows(IntegrityException.class, () -> env.unwrap(MAGIC, SCOPE, wrapped));
    }

    @Test
    void wrongMagicFailsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        // reading with a different expected magic: the AAD binds MAGIC, so the tag fails
        assertThrows(IntegrityException.class, () -> env.unwrap(OTHER_MAGIC, SCOPE, wrapped));
    }

    // ---- scope: cross-shard splice control on the encrypting path ----

    @Test
    void scopeMismatchRefusedEncrypting() {
        // A GCM record wrapped under scope 3, read by a reader expecting scope 7, is refused
        // by the scope assert (before decryption even runs).
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, OTHER_SCOPE, wrapped));
        assertTrue(ex.getMessage().contains("scope mismatch"), ex.getMessage());
    }

    @Test
    void inPlaceScopeForgeFailsGcmTag() {
        // Re-stamp the scopeId to the reader's expected value and repair the CRC so the scope
        // assert PASSES - but the tag was computed with scopeId=3 in the AAD, so the GCM tag
        // fails. scopeId is AAD-bound, hence unforgeable in place.
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        ByteBuffer.wrap(wrapped).putInt(IntegrityEnvelope.HEADER_SIZE, OTHER_SCOPE);
        recomputeCrc(wrapped);
        assertThrows(IntegrityException.class, () -> env.unwrap(MAGIC, OTHER_SCOPE, wrapped));
    }

    @Test
    void truncatedEncryptedEnvelopeFailsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        byte[] truncated = Arrays.copyOf(wrapped, wrapped.length - 10);
        assertThrows(IntegrityException.class, () -> env.unwrap(MAGIC, SCOPE, truncated));
    }

    // ---- key mismatch: a different root cannot decrypt ----

    @Test
    void differentRootKeyCannotDecrypt() {
        byte[] wrapped = IntegrityEnvelope.encrypting(keys(), null).wrap(MAGIC, SCOPE, payload());
        byte[] otherMaterial = new byte[32];
        Arrays.fill(otherMaterial, (byte) 0x01);
        SegmentKeyManager otherKeys =
                new SegmentKeyManager(new RootKey(otherMaterial, new KeyId("local", "kid", 1)));
        IntegrityEnvelope wrongReader = IntegrityEnvelope.encrypting(otherKeys, null);
        assertThrows(IntegrityException.class, () -> wrongReader.unwrap(MAGIC, SCOPE, wrapped));
    }

    // ---- downgrade / posture refusals ----

    @Test
    void encryptingReaderRefusesAlgNoneDowngrade() {
        // a NONE (keyless) artifact must be refused by an encrypting reader (fail-closed)
        byte[] none = IntegrityEnvelope.keyless().wrap(MAGIC, SCOPE, payload());
        IntegrityEnvelope enc = IntegrityEnvelope.encrypting(keys(), null);
        assertThrows(IntegrityException.class, () -> enc.unwrap(MAGIC, SCOPE, none));
    }

    @Test
    void nonEncryptingReaderRefusesEncryptedRecord() {
        byte[] wrapped = IntegrityEnvelope.encrypting(keys(), null).wrap(MAGIC, SCOPE, payload());
        // keyed-but-not-encrypting reader cannot decrypt algId=2 -> refuse loudly
        assertThrows(IntegrityException.class, () -> new IntegrityEnvelope(hmacKey()).unwrap(MAGIC, SCOPE, wrapped));
        // keyless reader likewise
        assertThrows(IntegrityException.class, () -> IntegrityEnvelope.keyless().unwrap(MAGIC, SCOPE, wrapped));
    }

    // ---- migration: an encrypting reader still verifies legacy HMAC records ----

    @Test
    void encryptingReaderReadsLegacyHmacRecords() {
        // records written under the old integrity-only posture (algId=1)
        byte[] legacy = new IntegrityEnvelope(hmacKey()).wrap(MAGIC, SCOPE, payload());
        assertEquals(IntegrityEnvelope.ALG_HMAC_SHA256, legacy[6]);
        // an encrypting envelope that ALSO carries the HMAC key reads them (the upgrade path)
        IntegrityEnvelope enc = IntegrityEnvelope.encrypting(keys(), hmacKey());
        assertArrayEquals(payload(), enc.unwrap(MAGIC, SCOPE, legacy));
        // but new writes are encrypted (algId=2)
        assertEquals(IntegrityEnvelope.ALG_AES256_GCM, enc.wrap(MAGIC, SCOPE, payload())[6]);
    }

    @Test
    void encryptingReaderWithoutHmacKeyRefusesLegacyHmacRecord() {
        byte[] legacy = new IntegrityEnvelope(hmacKey()).wrap(MAGIC, SCOPE, payload());
        IntegrityEnvelope enc = IntegrityEnvelope.encrypting(keys(), null); // no HMAC read key
        assertThrows(IntegrityException.class, () -> enc.unwrap(MAGIC, SCOPE, legacy));
    }

    // ---- per-artifact isolation ----

    @Test
    void differentMagicsProduceDifferentSegments() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(keys(), null);
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
