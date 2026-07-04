package io.configd.common;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codec-level tests for {@link IntegrityEnvelope}.
 */
class IntegrityEnvelopeTest {

    private static final int MAGIC = 0x5253_4E50; // "RSNP"
    private static final int OTHER_MAGIC = 0x5246_5354; // "RFST"
    // A per-shard scope (gid 3) and a different one, so the tests exercise the scope-assert
    // rather than the gid-0/scope-0 special case where a mismatch would be masked by the default.
    private static final int SCOPE = 3;
    private static final int OTHER_SCOPE = 7;

    // Payload begins right after header(8) + scopeId(4) in the NONE/HMAC postures.
    private static final int PAYLOAD_START = IntegrityEnvelope.HEADER_SIZE + IntegrityEnvelope.SCOPE_ID_SIZE;

    private static SecretKey key() {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) 0x42);
        return new SecretKeySpec(k, "HmacSHA256");
    }

    private static byte[] payload() {
        return "hello-raft-payload".getBytes();
    }

    // round-trip

    @Test
    void keyedRoundTrip() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        assertTrue(env.isKeyed());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        assertArrayEquals(payload(), env.unwrap(MAGIC, SCOPE, wrapped));
        assertArrayEquals(payload(), env.unwrapOrNull(MAGIC, SCOPE, wrapped));
    }

    @Test
    void keylessRoundTrip() {
        IntegrityEnvelope env = IntegrityEnvelope.keyless();
        assertEquals(false, env.isKeyed());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        assertArrayEquals(payload(), env.unwrap(MAGIC, SCOPE, wrapped));
    }

    @Test
    void keyedWrapIsLongerByKeyTermAndMacThanKeyless() {
        byte[] keyed = new IntegrityEnvelope(key()).wrap(MAGIC, SCOPE, payload());
        byte[] keyless = IntegrityEnvelope.keyless().wrap(MAGIC, SCOPE, payload());
        // v3 keyed HMAC adds the 4-byte keyTerm (after scopeId) plus the 32-byte MAC over keyless.
        assertEquals(keyless.length + IntegrityEnvelope.KEY_TERM_SIZE + IntegrityEnvelope.MAC_SIZE,
                keyed.length);
    }

    @Test
    void emptyPayloadRoundTrips() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        assertArrayEquals(new byte[0], env.unwrap(MAGIC, SCOPE, env.wrap(MAGIC, SCOPE, new byte[0])));
    }

    // scopeId: the cross-shard/cross-scope splice control

    @Test
    void scopeMismatchRefusedKeyed() {
        // A record authentically wrapped under one scope, fed to a reader expecting a
        // different scope, is refused - the cross-shard-splice defense. The record still
        // authenticates as bytes (same key); the scope assert is what catches it.
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, OTHER_SCOPE, wrapped));
        assertTrue(ex.getMessage().contains("scope mismatch"), ex.getMessage());
    }

    @Test
    void scopeMismatchRefusedKeyless() {
        // The assert fires in EVERY posture, keyless included: an honestly cross-scope
        // artifact is refused even without a key (operational safety, not adversarial).
        IntegrityEnvelope env = IntegrityEnvelope.keyless();
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, OTHER_SCOPE, wrapped));
        assertTrue(ex.getMessage().contains("scope mismatch"), ex.getMessage());
    }

    @Test
    void inPlaceScopeForgeFailsMac() {
        // An attacker re-stamps a shard-3 record's scopeId to the reader's expected 7 and
        // repairs the CRC so the scope assert would PASS - but the MAC was computed over the
        // original scopeId, so the keyed reader still refuses. Assert + unforgeable-scope
        // together close the splice; the assert alone (as in keyless) would be bypassable.
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        ByteBuffer.wrap(wrapped).putInt(IntegrityEnvelope.HEADER_SIZE, OTHER_SCOPE); // re-stamp scopeId
        recomputeCrc(wrapped);
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, OTHER_SCOPE, wrapped));
        assertTrue(ex.getMessage().contains("MAC mismatch"), ex.getMessage());
    }

    @Test
    void nodeScopeRoundTripsAndRefusesShardReader() {
        // A node-level artifact stamps NODE_SCOPE; a per-shard reader (gid 3) refuses it, so a
        // node-level artifact can never be replayed where a per-shard one is expected.
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, IntegrityEnvelope.NODE_SCOPE, payload());
        assertArrayEquals(payload(), env.unwrap(MAGIC, IntegrityEnvelope.NODE_SCOPE, wrapped));
        assertThrows(IntegrityException.class, () -> env.unwrap(MAGIC, SCOPE, wrapped));
    }

    // tamper / forgery detection (keyed)

    @Test
    void tamperedPayloadByteThrows() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        // Flip a payload byte AND recompute the CRC32C so only the MAC catches it -
        // proves the MAC, not the CRC, is the tamper control.
        wrapped[PAYLOAD_START] ^= 0x01;
        recomputeCrc(wrapped);
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, SCOPE, wrapped));
        assertTrue(ex.getMessage().contains("MAC mismatch"), ex.getMessage());
    }

    @Test
    void tamperedPayloadCaughtByCrcWhenCrcNotRecomputed() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        wrapped[PAYLOAD_START] ^= 0x01; // do not fix CRC
        assertThrows(IntegrityException.class, () -> env.unwrap(MAGIC, SCOPE, wrapped));
    }

    @Test
    void flippedAlgIdToNoneUnderKeyedThrows_downgrade() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        // algId is at offset 6 (after magic:4 + version:2). Force it to NONE and
        // recompute the CRC; the keyed reader must still refuse (downgrade), even
        // though the bytes are now CRC-consistent.
        wrapped[6] = IntegrityEnvelope.ALG_NONE;
        recomputeCrc(wrapped);
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, SCOPE, wrapped));
        assertTrue(ex.getMessage().contains("downgrade"), ex.getMessage());
    }

    @Test
    void rolledFormatVersionThrows() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        // formatVersion is the short at offset 4. Roll it back to 2 (the built-but-unshipped
        // predecessor); a v3 reader must refuse it.
        wrapped[4] = 0;
        wrapped[5] = 2;
        recomputeCrc(wrapped);
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, SCOPE, wrapped));
        assertTrue(ex.getMessage().contains("formatVersion"), ex.getMessage());
    }

    @Test
    void wrongMagicUnderKeyedThrows() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        assertThrows(IntegrityException.class, () -> env.unwrap(OTHER_MAGIC, SCOPE, wrapped));
        assertThrows(IntegrityException.class, () -> env.unwrapOrNull(OTHER_MAGIC, SCOPE, wrapped));
    }

    @Test
    void crossKeyMacThrows() {
        byte[] wrapped = new IntegrityEnvelope(key()).wrap(MAGIC, SCOPE, payload());
        byte[] other = new byte[32];
        Arrays.fill(other, (byte) 0x7e);
        IntegrityEnvelope wrongKey = new IntegrityEnvelope(new SecretKeySpec(other, "HmacSHA256"));
        assertThrows(IntegrityException.class, () -> wrongKey.unwrap(MAGIC, SCOPE, wrapped));
    }

    // truncation / absence rules

    @Test
    void structurallyShortReturnsNullNotThrow() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        assertNull(env.unwrapOrNull(MAGIC, SCOPE, null));
        assertNull(env.unwrapOrNull(MAGIC, SCOPE, new byte[]{1, 2, 3}));        // shorter than the magic
        assertNull(env.unwrapOrNull(MAGIC, SCOPE, new byte[IntegrityEnvelope.HEADER_SIZE - 1]));
    }

    @Test
    void truncatedAfterMagicThrows() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        // Long enough to be a structural envelope, magic intact, but chopped in the
        // middle of the payload/MAC - must fail loud (corruption), not null.
        byte[] truncated = Arrays.copyOf(wrapped, wrapped.length - 5);
        assertThrows(IntegrityException.class, () -> env.unwrapOrNull(MAGIC, SCOPE, truncated));
    }

    // keyless: foreign / non-envelope bytes

    @Test
    void keylessAcceptsForeignNonEnvelopedBytesAsNull() {
        // A keyless reader returns null for bytes whose leading 4 bytes are not the
        // magic - structurally absent (a caller that requires an envelope refuses that null).
        IntegrityEnvelope env = IntegrityEnvelope.keyless();
        byte[] foreign = "foreign-non-envelope-bytes-no-magic".getBytes();
        assertNull(env.unwrapOrNull(MAGIC, SCOPE, foreign));
    }

    @Test
    void keylessRefusesKeyedEnvelope() {
        // A keyless reader cannot authenticate a keyed (algId=HMAC) artifact and
        // must refuse rather than trust an unverifiable MAC.
        byte[] keyed = new IntegrityEnvelope(key()).wrap(MAGIC, SCOPE, payload());
        assertThrows(IntegrityException.class,
                () -> IntegrityEnvelope.keyless().unwrapOrNull(MAGIC, SCOPE, keyed));
    }

    @Test
    void keyedRefusesForeignNonEnvelopedBytes() {
        // Fail-closed: a keyed reader will not silently accept unauthenticated bytes.
        byte[] foreign = "foreign-non-envelope-bytes-no-magic".getBytes();
        assertThrows(IntegrityException.class,
                () -> new IntegrityEnvelope(key()).unwrapOrNull(MAGIC, SCOPE, foreign));
    }

    // hardening regressions

    @Test
    void reservedByteTamperUnderKeyedThrows() {
        // The reserved byte (offset 7) is MUST-be-zero: the dedicated MBZ check refuses a
        // non-zero value before the MAC is even consulted. It fires in every posture, so a
        // reserved-byte tamper is a fail-closed refusal, not an incidental MAC mismatch.
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        wrapped[7] ^= 0x01;          // flip a bit in the reserved byte
        recomputeCrc(wrapped);       // repair CRC so the version-independent CRC does not fire
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, SCOPE, wrapped));
        assertTrue(ex.getMessage().contains("reserved"),
                "reserved-byte tamper must be caught by the MBZ check, got: " + ex.getMessage());
    }

    @Test
    void reservedNonZeroThrowsKeyless() {
        // The MBZ check is what makes the reserved byte a genuine forward-compat escape:
        // it fails closed even in the KEYLESS posture, where no MAC covers it. A v3 reader
        // can never silently mis-parse bytes a future writer stamped into this slot.
        IntegrityEnvelope env = IntegrityEnvelope.keyless();
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        wrapped[7] = 0x01;           // set the reserved byte non-zero
        recomputeCrc(wrapped);       // repair CRC so only the MBZ check can fire
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, SCOPE, wrapped));
        assertTrue(ex.getMessage().contains("reserved"),
                "keyless reserved-byte MBZ must fail closed, got: " + ex.getMessage());
    }

    @Test
    void corruptHeaderReportsCrcNotVersion() {
        // CRC-before-version: a bit-flip in the version field (with the CRC left stale)
        // must surface as CORRUPTION, not as a misleading "unsupported version". The
        // version is only read from CRC-validated bytes.
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        wrapped[4] ^= 0x01;          // flip a bit in the 2-byte formatVersion field, CRC NOT repaired
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, SCOPE, wrapped));
        assertTrue(ex.getMessage().contains("CRC"),
                "a corrupt header must report corruption before version, got: " + ex.getMessage());
    }

    @Test
    void magicMatchingSubFloorBufferUnderKeyedThrows() {
        // A buffer that carries our magic but is below the v3 envelope floor
        // (header + scopeId + CRC = 16) is a deliberate IntegrityException under a key (not
        // an incidental downstream underflow). Keyless keeps the structurally-absent (null)
        // semantics.
        byte[] subFloor = new byte[12]; // >= 4 (magic readable), < MIN_ENVELOPE_SIZE (16)
        ByteBuffer.wrap(subFloor).putInt(MAGIC);
        assertThrows(IntegrityException.class,
                () -> new IntegrityEnvelope(key()).unwrapOrNull(MAGIC, SCOPE, subFloor));
        assertNull(IntegrityEnvelope.keyless().unwrapOrNull(MAGIC, SCOPE, subFloor));
    }

    // helper

    /** Recomputes the CRC32C trailer over [0, len-4) so only the MAC/version check fails. */
    private static void recomputeCrc(byte[] enveloped) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(enveloped, 0, enveloped.length - IntegrityEnvelope.CRC_SIZE);
        ByteBuffer.wrap(enveloped).putInt(enveloped.length - IntegrityEnvelope.CRC_SIZE, (int) crc.getValue());
    }
}
