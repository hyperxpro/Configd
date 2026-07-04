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
        byte[] wrapped = env.wrap(MAGIC, payload());
        assertArrayEquals(payload(), env.unwrap(MAGIC, wrapped));
        assertArrayEquals(payload(), env.unwrapOrNull(MAGIC, wrapped));
    }

    @Test
    void keylessRoundTrip() {
        IntegrityEnvelope env = IntegrityEnvelope.keyless();
        assertEquals(false, env.isKeyed());
        byte[] wrapped = env.wrap(MAGIC, payload());
        assertArrayEquals(payload(), env.unwrap(MAGIC, wrapped));
    }

    @Test
    void keyedWrapIsLongerByMacThanKeyless() {
        byte[] keyed = new IntegrityEnvelope(key()).wrap(MAGIC, payload());
        byte[] keyless = IntegrityEnvelope.keyless().wrap(MAGIC, payload());
        assertEquals(keyless.length + IntegrityEnvelope.MAC_SIZE, keyed.length);
    }

    @Test
    void emptyPayloadRoundTrips() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        assertArrayEquals(new byte[0], env.unwrap(MAGIC, env.wrap(MAGIC, new byte[0])));
    }

    // tamper / forgery detection (keyed)

    @Test
    void tamperedPayloadByteThrows() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, payload());
        // Flip a payload byte AND recompute the CRC32C so only the MAC catches it -
        // proves the MAC, not the CRC, is the tamper control.
        int payloadStart = IntegrityEnvelope.HEADER_SIZE;
        wrapped[payloadStart] ^= 0x01;
        recomputeCrc(wrapped);
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, wrapped));
        assertTrue(ex.getMessage().contains("MAC mismatch"), ex.getMessage());
    }

    @Test
    void tamperedPayloadCaughtByCrcWhenCrcNotRecomputed() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, payload());
        wrapped[IntegrityEnvelope.HEADER_SIZE] ^= 0x01; // do not fix CRC
        assertThrows(IntegrityException.class, () -> env.unwrap(MAGIC, wrapped));
    }

    @Test
    void flippedAlgIdToNoneUnderKeyedThrows_downgrade() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, payload());
        // algId is at offset 6 (after magic:4 + version:2). Force it to NONE and
        // recompute the CRC; the keyed reader must still refuse (downgrade), even
        // though the bytes are now CRC-consistent.
        wrapped[6] = IntegrityEnvelope.ALG_NONE;
        recomputeCrc(wrapped);
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, wrapped));
        assertTrue(ex.getMessage().contains("downgrade"), ex.getMessage());
    }

    @Test
    void rolledFormatVersionThrows() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, payload());
        // formatVersion is the short at offset 4. Roll it back to 1.
        wrapped[4] = 0;
        wrapped[5] = 1;
        recomputeCrc(wrapped);
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, wrapped));
        assertTrue(ex.getMessage().contains("formatVersion"), ex.getMessage());
    }

    @Test
    void wrongMagicUnderKeyedThrows() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, payload());
        assertThrows(IntegrityException.class, () -> env.unwrap(OTHER_MAGIC, wrapped));
        assertThrows(IntegrityException.class, () -> env.unwrapOrNull(OTHER_MAGIC, wrapped));
    }

    @Test
    void crossKeyMacThrows() {
        byte[] wrapped = new IntegrityEnvelope(key()).wrap(MAGIC, payload());
        byte[] other = new byte[32];
        Arrays.fill(other, (byte) 0x7e);
        IntegrityEnvelope wrongKey = new IntegrityEnvelope(new SecretKeySpec(other, "HmacSHA256"));
        assertThrows(IntegrityException.class, () -> wrongKey.unwrap(MAGIC, wrapped));
    }

    // truncation / absence rules

    @Test
    void structurallyShortReturnsNullNotThrow() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        assertNull(env.unwrapOrNull(MAGIC, null));
        assertNull(env.unwrapOrNull(MAGIC, new byte[]{1, 2, 3}));        // shorter than header+CRC
        assertNull(env.unwrapOrNull(MAGIC, new byte[IntegrityEnvelope.HEADER_SIZE - 1]));
    }

    @Test
    void truncatedAfterMagicThrows() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, payload());
        // Long enough to be a structural envelope, magic intact, but chopped in the
        // middle of the payload/MAC - must fail loud (corruption), not null.
        byte[] truncated = Arrays.copyOf(wrapped, wrapped.length - 5);
        assertThrows(IntegrityException.class, () -> env.unwrapOrNull(MAGIC, truncated));
    }

    // keyless back-compat

    @Test
    void keylessAcceptsLegacyNonEnvelopedBytesAsNull() {
        // A keyless reader returns null for bytes whose leading 4 bytes are not the
        // magic - that is the legacy raw-bytes migration path (caller parses raw).
        IntegrityEnvelope env = IntegrityEnvelope.keyless();
        byte[] legacy = "legacy-raw-snapshot-bytes-no-envelope".getBytes();
        assertNull(env.unwrapOrNull(MAGIC, legacy));
    }

    @Test
    void keylessRefusesKeyedEnvelope() {
        // A keyless reader cannot authenticate a keyed (algId=HMAC) artifact and
        // must refuse rather than trust an unverifiable MAC.
        byte[] keyed = new IntegrityEnvelope(key()).wrap(MAGIC, payload());
        assertThrows(IntegrityException.class,
                () -> IntegrityEnvelope.keyless().unwrapOrNull(MAGIC, keyed));
    }

    @Test
    void keyedRefusesLegacyNonEnvelopedBytes() {
        // Fail-closed: a keyed reader will not silently accept unauthenticated bytes.
        byte[] legacy = "legacy-raw-snapshot-bytes-no-envelope".getBytes();
        assertThrows(IntegrityException.class,
                () -> new IntegrityEnvelope(key()).unwrapOrNull(MAGIC, legacy));
    }

    // hardening regressions

    @Test
    void reservedByteTamperUnderKeyedThrows() {
        // The reserved byte (offset 7) is MUST-be-zero: the dedicated MBZ check refuses a
        // non-zero value before the MAC is even consulted. It fires in every posture, so a
        // reserved-byte tamper is a fail-closed refusal, not an incidental MAC mismatch.
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, payload());
        wrapped[7] ^= 0x01;          // flip a bit in the reserved byte
        recomputeCrc(wrapped);       // repair CRC so the version-independent CRC does not fire
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, wrapped));
        assertTrue(ex.getMessage().contains("reserved"),
                "reserved-byte tamper must be caught by the MBZ check, got: " + ex.getMessage());
    }

    @Test
    void reservedNonZeroThrowsKeyless() {
        // The MBZ check is what makes the reserved byte a genuine forward-compat escape:
        // it fails closed even in the KEYLESS posture, where no MAC covers it. A v1 reader
        // can never silently mis-parse bytes a future writer stamped into this slot.
        IntegrityEnvelope env = IntegrityEnvelope.keyless();
        byte[] wrapped = env.wrap(MAGIC, payload());
        wrapped[7] = 0x01;           // set the reserved byte non-zero
        recomputeCrc(wrapped);       // repair CRC so only the MBZ check can fire
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, wrapped));
        assertTrue(ex.getMessage().contains("reserved"),
                "keyless reserved-byte MBZ must fail closed, got: " + ex.getMessage());
    }

    @Test
    void corruptHeaderReportsCrcNotVersion() {
        // CRC-before-version: a bit-flip in the version field (with the CRC left stale)
        // must surface as CORRUPTION, not as a misleading "unsupported version". The
        // version is only read from CRC-validated bytes.
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, payload());
        wrapped[4] ^= 0x01;          // flip a bit in the 2-byte formatVersion field, CRC NOT repaired
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, wrapped));
        assertTrue(ex.getMessage().contains("CRC"),
                "a corrupt header must report corruption before version, got: " + ex.getMessage());
    }

    @Test
    void magicMatchingSubFloorBufferUnderKeyedThrows() {
        // A buffer that carries our magic but is below the envelope floor is a deliberate
        // IntegrityException under a key (not an incidental downstream underflow).
        // Keyless keeps the structurally-absent (null) semantics.
        byte[] subFloor = new byte[10]; // >= 4 (magic readable), < HEADER_SIZE + CRC_SIZE (12)
        ByteBuffer.wrap(subFloor).putInt(MAGIC);
        assertThrows(IntegrityException.class,
                () -> new IntegrityEnvelope(key()).unwrapOrNull(MAGIC, subFloor));
        assertNull(IntegrityEnvelope.keyless().unwrapOrNull(MAGIC, subFloor));
    }

    // helper

    /** Recomputes the CRC32C trailer over [0, len-4) so only the MAC/version check fails. */
    private static void recomputeCrc(byte[] enveloped) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(enveloped, 0, enveloped.length - IntegrityEnvelope.CRC_SIZE);
        ByteBuffer.wrap(enveloped).putInt(enveloped.length - IntegrityEnvelope.CRC_SIZE, (int) crc.getValue());
    }
}
