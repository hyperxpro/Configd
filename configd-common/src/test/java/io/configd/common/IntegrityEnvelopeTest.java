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

class IntegrityEnvelopeTest {

    private static final int MAGIC = 0x5253_4E50; // "RSNP"
    private static final int OTHER_MAGIC = 0x5246_5354; // "RFST"
    // A per-shard scope (gid 3) and a different one, so the tests exercise the scope-assert
    // rather than the gid-0/scope-0 special case where a mismatch would be masked by the default.
    private static final int SCOPE = 3;
    private static final int OTHER_SCOPE = 7;

    private static final int PAYLOAD_START = IntegrityEnvelope.HEADER_SIZE + IntegrityEnvelope.SCOPE_ID_SIZE;

    private static SecretKey key() {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) 0x42);
        return new SecretKeySpec(k, "HmacSHA256");
    }

    private static byte[] payload() {
        return "hello-raft-payload".getBytes();
    }

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
        assertEquals(keyless.length + IntegrityEnvelope.KEY_TERM_SIZE + IntegrityEnvelope.MAC_SIZE,
                keyed.length);
    }

    @Test
    void emptyPayloadRoundTrips() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        assertArrayEquals(new byte[0], env.unwrap(MAGIC, SCOPE, env.wrap(MAGIC, SCOPE, new byte[0])));
    }


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
        IntegrityEnvelope env = IntegrityEnvelope.keyless();
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, OTHER_SCOPE, wrapped));
        assertTrue(ex.getMessage().contains("scope mismatch"), ex.getMessage());
    }

    @Test
    void inPlaceScopeForgeFailsMac() {
        // An attacker re-stamps a shard-3 record's scopeId to the reader's expected 7 and
        // repairs the CRC so the scope assert would pass - but the MAC was computed over the
        // original scopeId, so the keyed reader still refuses. The scope assert together with
        // the unforgeable scope closes the splice; the assert alone (as in keyless) would be
        // bypassable.
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        ByteBuffer.wrap(wrapped).putInt(IntegrityEnvelope.HEADER_SIZE, OTHER_SCOPE);
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


    @Test
    void tamperedPayloadByteThrows() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
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
        wrapped[PAYLOAD_START] ^= 0x01;
        assertThrows(IntegrityException.class, () -> env.unwrap(MAGIC, SCOPE, wrapped));
    }

    @Test
    void flippedAlgIdToNoneUnderKeyedThrows_downgrade() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
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


    @Test
    void structurallyShortReturnsNullNotThrow() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        assertNull(env.unwrapOrNull(MAGIC, SCOPE, null));
        assertNull(env.unwrapOrNull(MAGIC, SCOPE, new byte[]{1, 2, 3}));
        assertNull(env.unwrapOrNull(MAGIC, SCOPE, new byte[IntegrityEnvelope.HEADER_SIZE - 1]));
    }

    @Test
    void truncatedAfterMagicThrows() {
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        byte[] truncated = Arrays.copyOf(wrapped, wrapped.length - 5);
        assertThrows(IntegrityException.class, () -> env.unwrapOrNull(MAGIC, SCOPE, truncated));
    }


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
        byte[] foreign = "foreign-non-envelope-bytes-no-magic".getBytes();
        assertThrows(IntegrityException.class,
                () -> new IntegrityEnvelope(key()).unwrapOrNull(MAGIC, SCOPE, foreign));
    }


    @Test
    void reservedByteTamperUnderKeyedThrows() {
        // The reserved byte (offset 7) must be zero: the dedicated MBZ check refuses a
        // non-zero value before the MAC is even consulted. It fires in every posture, so a
        // reserved-byte tamper is a fail-closed refusal, not an incidental MAC mismatch.
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        wrapped[7] ^= 0x01;
        recomputeCrc(wrapped);
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, SCOPE, wrapped));
        assertTrue(ex.getMessage().contains("reserved"),
                "reserved-byte tamper must be caught by the MBZ check, got: " + ex.getMessage());
    }

    @Test
    void reservedNonZeroThrowsKeyless() {
        // The MBZ check is what makes the reserved byte a genuine forward-compat escape:
        // it fails closed even in the keyless posture, where no MAC covers it. A v3 reader
        // can never silently mis-parse bytes a future writer stamped into this slot.
        IntegrityEnvelope env = IntegrityEnvelope.keyless();
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        wrapped[7] = 0x01;
        recomputeCrc(wrapped);
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> env.unwrap(MAGIC, SCOPE, wrapped));
        assertTrue(ex.getMessage().contains("reserved"),
                "keyless reserved-byte MBZ must fail closed, got: " + ex.getMessage());
    }

    @Test
    void corruptHeaderReportsCrcNotVersion() {
        // CRC-before-version: a bit-flip in the version field (with the CRC left stale)
        // must surface as corruption, not as a misleading "unsupported version". The
        // version is only read from CRC-validated bytes.
        IntegrityEnvelope env = new IntegrityEnvelope(key());
        byte[] wrapped = env.wrap(MAGIC, SCOPE, payload());
        wrapped[4] ^= 0x01;
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
        byte[] subFloor = new byte[12];
        ByteBuffer.wrap(subFloor).putInt(MAGIC);
        assertThrows(IntegrityException.class,
                () -> new IntegrityEnvelope(key()).unwrapOrNull(MAGIC, SCOPE, subFloor));
        assertNull(IntegrityEnvelope.keyless().unwrapOrNull(MAGIC, SCOPE, subFloor));
    }

    private static void recomputeCrc(byte[] enveloped) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(enveloped, 0, enveloped.length - IntegrityEnvelope.CRC_SIZE);
        ByteBuffer.wrap(enveloped).putInt(enveloped.length - IntegrityEnvelope.CRC_SIZE, (int) crc.getValue());
    }
}
