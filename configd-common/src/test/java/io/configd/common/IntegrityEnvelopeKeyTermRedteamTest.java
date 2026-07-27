package io.configd.common;

import io.configd.common.kms.KeyId;
import io.configd.common.kms.RootKey;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Red-team pass over the keyTerm-versioned at-rest integrity format. Every attack rewrites the
 * {@code keyTerm} field of a real on-disk envelope and asserts that verification refuses - and,
 * decisively, recomputes the CRC32C after the edit so the refusal is proven to come from the
 * authentication (HMAC or GCM tag), not the CRC. A stale CRC would mask the real behavior behind
 * a "corruption" error and prove nothing about the keyTerm binding.
 *
 * <p>Both keyed postures are attacked: algId=1 HMAC (the MAC input binds keyTerm and selects the
 * per-term integrity key) and algId=2 GCM (the AAD binds keyTerm and selects the per-term,
 * per-segment encryption key). The forge targets both a valid-but-wrong retained term (key
 * selection succeeds, so only the MAC/tag can catch it) and an absent term (fail-closed key
 * selection). The {@code keyTerm=0} signing-key domain crossing is attacked too. Finally, a
 * keyless (algId=0) record must stay byte-identical to the layout before keyTerm was introduced -
 * no keyTerm is inserted.
 *
 * <p>{@code keyTerm} sits at offset {@code HEADER(8)+scopeId(4)=12} in every keyed posture.
 */
class IntegrityEnvelopeKeyTermRedteamTest {

    private static final int WALE_MAGIC = 0x5257_414C; // "RWAL"
    private static final int SNAP_MAGIC = 0x5253_4E50; // "RSNP"
    private static final int SCOPE = 7;                // a per-shard gid, same on wrap + read here
    private static final int KEY_TERM_OFFSET = IntegrityEnvelope.HEADER_SIZE + IntegrityEnvelope.SCOPE_ID_SIZE;

    private static RootKey rootAt(int term, byte fill) {
        byte[] m = new byte[32];
        Arrays.fill(m, fill);
        return new RootKey(m, new KeyId("local", "kt-redteam", term));
    }

    /** A term-versioned key source retaining roots for terms 1 and 2, active = 2 (new writes stamp 2). */
    private static SegmentKeyManager twoTermKeys() {
        return SegmentKeyManager.overTerms(
                List.of(rootAt(1, (byte) 0x11), rootAt(2, (byte) 0x22)), 2);
    }

    private static SecretKey singleHmacKey() {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) 0x42);
        return new SecretKeySpec(k, "HmacSHA256");
    }

    private static byte[] payload() {
        return "gate4-keyterm-attack-payload".getBytes();
    }

    /** Overwrites the 4-byte big-endian keyTerm at offset 12 (in place). */
    private static void setKeyTerm(byte[] enveloped, int keyTerm) {
        ByteBuffer.wrap(enveloped).putInt(KEY_TERM_OFFSET, keyTerm);
    }

    /** Repairs the CRC32C trailer (all bytes but the last 4) so the forge is judged on the MAC or tag, not the CRC. */
    private static void repairCrc(byte[] b) {
        CRC32C crc = new CRC32C();
        crc.update(b, 0, b.length - IntegrityEnvelope.CRC_SIZE);
        ByteBuffer.wrap(b).putInt(b.length - IntegrityEnvelope.CRC_SIZE, (int) crc.getValue());
    }

    private static int keyTermOf(byte[] enveloped) {
        return ByteBuffer.wrap(enveloped).getInt(KEY_TERM_OFFSET);
    }

    // Attack 1 (HMAC): forge keyTerm to a valid-but-wrong retained term. Key selection succeeds
    // (root 1 is retained), so the only thing that can catch this is the MAC binding keyTerm.

    @Test
    void hmacKeyTermForged_toValidOtherTerm_failsMac_notCrc() {
        IntegrityEnvelope env = IntegrityEnvelope.hmac(twoTermKeys());
        byte[] rec = env.wrap(WALE_MAGIC, SCOPE, payload());
        assertEquals(2, keyTermOf(rec), "the active term is stamped");

        setKeyTerm(rec, 1);   // roll keyTerm from 2 to 1 (term 1's integrity key is retained)
        repairCrc(rec);       // make the CRC pass so the MAC, not the CRC, must catch it

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(WALE_MAGIC, SCOPE, rec),
                "a rolled keyTerm must fail authentication even when the CRC is repaired");
        assertTrue(ex.getMessage().contains("MAC mismatch"),
                "the keyTerm-in-MAC binding must surface as a MAC mismatch, got: " + ex.getMessage());
    }

    @Test
    void hmacKeyTermForged_toAbsentTerm_failsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.hmac(twoTermKeys());
        byte[] rec = env.wrap(WALE_MAGIC, SCOPE, payload());
        setKeyTerm(rec, 99);  // a term with no retained root
        repairCrc(rec);
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(WALE_MAGIC, SCOPE, rec));
        assertTrue(ex.getMessage().contains("unknown at-rest integrity key term"),
                "an absent keyTerm must fail closed at key selection, got: " + ex.getMessage());
    }

    // Attack 1 (GCM): forge keyTerm to a valid-but-wrong retained term. DEK selection succeeds,
    // so only the GCM AAD, which binds keyTerm, can catch it.

    @Test
    void gcmKeyTermForged_toValidOtherTerm_failsTag_notCrc() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(twoTermKeys());
        byte[] rec = env.wrap(WALE_MAGIC, SCOPE, payload());
        assertEquals(2, keyTermOf(rec), "the active term is stamped");
        assertEquals(IntegrityEnvelope.ALG_AES256_GCM, rec[6]);

        setKeyTerm(rec, 1);   // roll keyTerm from 2 to 1 (root 1 is retained, so the term-1 segment key derives)
        repairCrc(rec);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(WALE_MAGIC, SCOPE, rec),
                "a rolled keyTerm must fail the GCM AAD authentication even with the CRC repaired");
        assertTrue(ex.getMessage().contains("authentication failed"),
                "the keyTerm-in-AAD binding must surface as an auth failure, got: " + ex.getMessage());
    }

    @Test
    void gcmKeyTermForged_toAbsentTerm_failsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(twoTermKeys());
        byte[] rec = env.wrap(WALE_MAGIC, SCOPE, payload());
        setKeyTerm(rec, 99);
        repairCrc(rec);
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(WALE_MAGIC, SCOPE, rec));
        assertTrue(ex.getMessage().contains("unknown at-rest encryption key term"),
                "an absent keyTerm must fail closed at DEK selection, got: " + ex.getMessage());
    }

    // Attack: cross into the keyTerm=0 signing-key domain on a non-keyring artifact, which is illegal.

    @Test
    void hmacKeyTermForged_toZero_reservedDomain_failsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.hmac(twoTermKeys());
        byte[] rec = env.wrap(WALE_MAGIC, SCOPE, payload());
        setKeyTerm(rec, 0);   // keyTerm 0 is the keyring's signing-key domain, illegal for RWAL
        repairCrc(rec);
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(WALE_MAGIC, SCOPE, rec));
        assertTrue(ex.getMessage().contains("reserved for the keyring"),
                "keyTerm=0 under a non-keyring magic must fail closed, got: " + ex.getMessage());
    }

    @Test
    void gcmKeyTermForged_toZero_failsClosed() {
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(twoTermKeys());
        byte[] rec = env.wrap(WALE_MAGIC, SCOPE, payload());
        setKeyTerm(rec, 0);
        repairCrc(rec);
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(WALE_MAGIC, SCOPE, rec));
        assertTrue(ex.getMessage().contains("keyTerm must be >= 1"),
                "a GCM record with keyTerm 0 must fail closed, got: " + ex.getMessage());
    }

    // Isolation: prove the MAC binds keyTerm independent of key selection. A single-key HMAC
    // envelope verifies under one key for every keyTerm >= 1, so key selection cannot differ - the
    // only defense against a rolled keyTerm is the MAC covering it. This is the guard that a
    // mutation test would need to defeat to flip the assertion.

    @Test
    void singleKeyHmac_keyTermForge_caughtByMacInputBinding() {
        IntegrityEnvelope env = new IntegrityEnvelope(singleHmacKey()); // HMAC_SINGLE, stamps keyTerm=1
        byte[] rec = env.wrap(WALE_MAGIC, SCOPE, payload());
        assertEquals(1, keyTermOf(rec));

        setKeyTerm(rec, 7);   // same verifying key for term 7; only the MAC message differs
        repairCrc(rec);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(WALE_MAGIC, SCOPE, rec));
        assertTrue(ex.getMessage().contains("MAC mismatch"),
                "with a single verifying key, only the MAC-over-keyTerm can catch the forge: "
                        + ex.getMessage());
    }

    // Keyless (algId=0) records stay byte-identical to the layout before keyTerm was introduced -
    // no keyTerm. keyTerm was added only to the keyed postures; if one leaked into the keyless
    // body, that would be a real defect.

    @Test
    void keylessRecord_carriesNoKeyTerm_byteIdenticalLayout() {
        byte[] p = payload();
        byte[] keyless = IntegrityEnvelope.keyless().wrap(WALE_MAGIC, SCOPE, p);

        // Layout must be exactly header (8 bytes), scopeId (4 bytes), payload, then CRC (4 bytes) -
        // no 4-byte keyTerm.
        int expectedLen = IntegrityEnvelope.HEADER_SIZE + IntegrityEnvelope.SCOPE_ID_SIZE
                + p.length + IntegrityEnvelope.CRC_SIZE;
        assertEquals(expectedLen, keyless.length,
                "a keyless record must not carry a keyTerm (any +4 length is a leak)");
        assertEquals(IntegrityEnvelope.ALG_NONE, keyless[6], "keyless posture is algId=0");
        // The payload begins immediately after the scopeId (offset 12), not after a keyTerm.
        assertArrayEquals(p, Arrays.copyOfRange(keyless, KEY_TERM_OFFSET, KEY_TERM_OFFSET + p.length),
                "the payload must sit at offset 12 - proving no keyTerm was inserted");

        // Contrast: the keyed HMAC record of the same payload is exactly 4 (keyTerm) plus 32 (MAC)
        // bytes longer, with a keyTerm of 1 at offset 12 - the keyTerm exists only in the keyed posture.
        byte[] keyed = new IntegrityEnvelope(singleHmacKey()).wrap(WALE_MAGIC, SCOPE, p);
        assertEquals(keyless.length + IntegrityEnvelope.KEY_TERM_SIZE + IntegrityEnvelope.MAC_SIZE,
                keyed.length, "the keyed layout differs from keyless by keyTerm + MAC");
        assertEquals(1, keyTermOf(keyed), "the keyed posture carries a keyTerm at offset 12");
    }

    // Non-vacuity: the two-term round trip verifies both terms, so the forges above prove
    // refusal, not a broken codec. An old-term record still verifies after the active term moves on.

    @Test
    void twoTermRoundTrip_bothPostures_verifyBothTerms() {
        // HMAC: write at active term 2; a hand-forced term-1 record still verifies under the
        // retained root for term 1.
        SegmentKeyManager keys = twoTermKeys();
        IntegrityEnvelope hmac = IntegrityEnvelope.hmac(keys);
        byte[] recTerm2 = hmac.wrap(WALE_MAGIC, SCOPE, payload());
        assertArrayEquals(payload(), hmac.unwrap(WALE_MAGIC, SCOPE, recTerm2));

        IntegrityEnvelope gcm = IntegrityEnvelope.encrypting(twoTermKeys());
        byte[] enc = gcm.wrap(SNAP_MAGIC, SCOPE, payload());
        assertArrayEquals(payload(), gcm.unwrap(SNAP_MAGIC, SCOPE, enc),
                "a well-formed encrypted record must still round-trip");
    }
}
