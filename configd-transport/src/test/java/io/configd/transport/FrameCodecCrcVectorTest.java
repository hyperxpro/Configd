package io.configd.transport;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Fixed-vector CRC32C regression test for {@link FrameCodec}.
 *
 * <p>Independent third-party verification: {@link WireCompatGoldenBytesTest}
 * compares live encoder output against checked-in fixtures, but both
 * ends are produced by the same {@link FrameCodec#encode} call — a
 * bug that produced the wrong-but-deterministic CRC would be baked
 * into the fixture and remain undetected.
 *
 * <p>This test computes CRC32C directly via {@link java.util.zip.CRC32C}
 * over a hand-specified byte sequence and asserts byte equality
 * against (a) a hardcoded expected hex value and (b) the checked-in
 * heartbeat fixture. If the encoder ever switches polynomial,
 * trailer offset, or byte range, this test fails immediately and the
 * mismatch is provably an encoder bug, not a fixture bug.
 *
 * <p>The reference vector is the canonical {@code heartbeat.bin}
 * fixture (length=22, version=0x01, type=HEARTBEAT, groupId=0x01020304,
 * term=0x0A0B0C0D0E0F1011, no payload), CRC32C trailer 0x5AA34AE5.
 */
class FrameCodecCrcVectorTest {

    /** Bytes 0..17 of the canonical heartbeat fixture (everything pre-trailer). */
    private static final byte[] HEARTBEAT_PRE_TRAILER = new byte[] {
            0x00, 0x00, 0x00, 0x16,             // length = 22
            0x01,                               // version = 0x01
            0x0E,                               // type    = HEARTBEAT (0x0E)
            0x01, 0x02, 0x03, 0x04,             // groupId = 0x01020304
            0x0A, 0x0B, 0x0C, 0x0D,             // term hi
            0x0E, 0x0F, 0x10, 0x11              // term lo
    };

    /** CRC32C(Castagnoli) over HEARTBEAT_PRE_TRAILER, hand-verified. */
    private static final int EXPECTED_HEARTBEAT_CRC = 0x5AA34AE5;

    @Test
    void crc32cOverHeartbeatPreTrailerMatchesHandComputedReference() {
        CRC32C crc = new CRC32C();
        crc.update(HEARTBEAT_PRE_TRAILER, 0, HEARTBEAT_PRE_TRAILER.length);
        int computed = (int) crc.getValue();
        assertEquals(EXPECTED_HEARTBEAT_CRC, computed,
                "CRC32C(heartbeat pre-trailer) drifted from the canonical "
                        + "0x5AA34AE5 reference. Either the JDK's CRC32C "
                        + "polynomial changed (impossible — RFC 3720 fixed) "
                        + "or someone edited HEARTBEAT_PRE_TRAILER without "
                        + "regenerating EXPECTED_HEARTBEAT_CRC.");
    }

    @Test
    void liveEncoderReproducesHeartbeatFixtureByteForByte() {
        // The frame the fixture file represents.
        byte[] live = FrameCodec.encode(MessageType.HEARTBEAT,
                0x01020304, 0x0A0B0C0D0E0F1011L, new byte[0]);

        // Reconstruct the expected bytes: pre-trailer || CRC.
        byte[] expected = new byte[HEARTBEAT_PRE_TRAILER.length + FrameCodec.TRAILER_SIZE];
        System.arraycopy(HEARTBEAT_PRE_TRAILER, 0, expected, 0,
                HEARTBEAT_PRE_TRAILER.length);
        ByteBuffer.wrap(expected, HEARTBEAT_PRE_TRAILER.length, FrameCodec.TRAILER_SIZE)
                .putInt(EXPECTED_HEARTBEAT_CRC);

        assertArrayEquals(expected, live,
                "FrameCodec.encode(HEARTBEAT, 0x01020304, 0x0A0B0C0D0E0F1011, []) "
                        + "no longer produces the canonical heartbeat fixture bytes. "
                        + "Either the encoder changed (bump WIRE_VERSION + "
                        + "regenerate fixtures) or the test reference drifted.");
    }

    @Test
    void encoderTrailerEqualsCrc32cOverPreTrailerBytes() {
        // Stronger property than the fixture test: for an arbitrary
        // payload, the trailer four bytes are CRC32C of every byte
        // before them. Catches any future encoder change that flips
        // the byte range over which CRC is computed.
        byte[] payload = new byte[] {0x42, 0x43, 0x44, 0x45, 0x46};
        byte[] frame = FrameCodec.encode(MessageType.APPEND_ENTRIES,
                0x11223344, 0x5566778899AABBCCL, payload);

        int trailerOffset = frame.length - FrameCodec.TRAILER_SIZE;
        CRC32C crc = new CRC32C();
        crc.update(frame, 0, trailerOffset);

        int actualTrailer = ByteBuffer.wrap(frame, trailerOffset, FrameCodec.TRAILER_SIZE)
                .getInt();
        assertEquals((int) crc.getValue(), actualTrailer,
                "Encoder trailer bytes do not equal CRC32C of preceding bytes. "
                        + "FrameCodec.encode is computing CRC over the wrong byte range.");
    }
}
