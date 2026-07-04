package io.configd.transport;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent red-team pass over the frozen raft-wire reserved-epoch reservation in
 * {@link FrameCodec} (offset 18, MBZ, reject-if-nonzero).
 *
 * <p>Beyond the builder's "populate the epoch, repair the CRC, expect a reject" test, these pin the
 * ORDERING that the design mandates: a non-zero epoch left with a STALE CRC must surface as
 * corruption (CRC-before-field), and only a repaired-CRC non-zero epoch reaches the MBZ check.
 * A single-bit populate is used (not all-0xFF) so the minimal adversarial mutation is covered.
 */
class FrameCodecEpochRedteamTest {

    private static final int GROUP_ID = 0x11223344;
    private static final long TERM = 0x00A1B2C3D4E5F607L;
    private static final byte[] PAYLOAD = {(byte) 0xCA, (byte) 0xFE};
    private static final int EPOCH_OFFSET = 18;

    private static void repairCrc(byte[] frame) {
        int crcOffset = frame.length - FrameCodec.TRAILER_SIZE;
        CRC32C crc = new CRC32C();
        crc.update(frame, 0, crcOffset);
        ByteBuffer.wrap(frame, crcOffset, FrameCodec.TRAILER_SIZE).putInt((int) crc.getValue());
    }

    @Test
    void nonZeroEpochWithStaleCrcReportsCorruptionNotMbz() {
        // Attack: flip a single epoch bit but DO NOT repair the CRC. The design orders CRC before any
        // field read, so this must be reported as corruption ("CRC32C mismatch"), NOT as the MBZ
        // epoch error — otherwise a bit-flip would point operators at "newer peer" instead of "bad
        // hardware/bug".
        byte[] frame = FrameCodec.encode(MessageType.APPEND_ENTRIES, GROUP_ID, TERM, PAYLOAD);
        frame[EPOCH_OFFSET] ^= 0x01; // corrupt one epoch byte; CRC now stale
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> FrameCodec.decode(frame));
        assertTrue(ex.getMessage().contains("CRC32C"),
                "a stale-CRC epoch corruption must report CRC, not MBZ, got: " + ex.getMessage());
    }

    @Test
    void singleBitEpochWithRepairedCrcRejectedAsMbz() {
        // Attack: the minimal reserved-field violation — flip ONE epoch bit and repair the CRC so the
        // MBZ check (not the checksum) is what fires. A single bit surviving the CRC is a newer peer
        // that put meaning into the reserved slot; the reader must fail closed.
        byte[] frame = FrameCodec.encode(MessageType.REQUEST_VOTE, GROUP_ID, TERM, PAYLOAD);
        frame[EPOCH_OFFSET + 7] ^= 0x01; // low byte of the 8-byte epoch
        repairCrc(frame);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> FrameCodec.decode(frame));
        assertTrue(ex.getMessage().contains("epoch"),
                "a repaired-CRC non-zero epoch must be refused as MBZ, got: " + ex.getMessage());
    }

    @Test
    void zeroEpochFrameRoundTripsCleanly() {
        // Regression / no-false-positive: the canonical MBZ epoch (all zero) must decode without
        // tripping the reserved-field guard.
        byte[] frame = FrameCodec.encode(MessageType.APPEND_ENTRIES, GROUP_ID, TERM, PAYLOAD);
        FrameCodec.Frame decoded = assertDoesNotThrow(() -> FrameCodec.decode(frame));
        assertEquals(MessageType.APPEND_ENTRIES, decoded.messageType());
        assertEquals(GROUP_ID, decoded.groupId());
        assertEquals(TERM, decoded.term());
    }
}
