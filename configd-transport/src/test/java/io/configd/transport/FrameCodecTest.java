package io.configd.transport;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class FrameCodecTest {

    @Test
    void encodeDecodeRoundtrip() {
        byte[] payload = "hello world".getBytes();
        byte[] frame = FrameCodec.encode(MessageType.APPEND_ENTRIES, 42, 7L, payload);

        FrameCodec.Frame decoded = FrameCodec.decode(frame);
        assertEquals(MessageType.APPEND_ENTRIES, decoded.messageType());
        assertEquals(42, decoded.groupId());
        assertEquals(7L, decoded.term());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void encodeDecodeEmptyPayload() {
        byte[] frame = FrameCodec.encode(MessageType.HEARTBEAT, 0, 1L, new byte[0]);
        FrameCodec.Frame decoded = FrameCodec.decode(frame);

        assertEquals(MessageType.HEARTBEAT, decoded.messageType());
        assertEquals(0, decoded.groupId());
        assertEquals(1L, decoded.term());
        assertEquals(0, decoded.payload().length);
    }

    @Test
    void encodeToByteBuffer() {
        byte[] payload = new byte[]{1, 2, 3};
        int size = FrameCodec.frameSize(payload.length);
        ByteBuffer buf = ByteBuffer.allocate(size);

        FrameCodec.encode(buf, MessageType.REQUEST_VOTE, 10, 99L, payload);
        buf.flip();

        byte[] frame = new byte[buf.remaining()];
        buf.get(frame);
        FrameCodec.Frame decoded = FrameCodec.decode(frame);

        assertEquals(MessageType.REQUEST_VOTE, decoded.messageType());
        assertEquals(10, decoded.groupId());
        assertEquals(99L, decoded.term());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void peekLengthMatchesActualLength() {
        byte[] payload = new byte[100];
        byte[] frame = FrameCodec.encode(MessageType.INSTALL_SNAPSHOT, 1, 2L, payload);

        int peeked = FrameCodec.peekLength(frame);
        assertEquals(frame.length, peeked);
    }

    @Test
    void frameSizeCalculation() {
        assertEquals(FrameCodec.HEADER_SIZE + FrameCodec.TRAILER_SIZE,
                FrameCodec.frameSize(0));
        assertEquals(FrameCodec.HEADER_SIZE + 100 + FrameCodec.TRAILER_SIZE,
                FrameCodec.frameSize(100));
    }

    @Test
    void decodeTooShortThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> FrameCodec.decode(new byte[5]));
    }

    @Test
    void decodeLengthMismatchThrows() {
        byte[] frame = FrameCodec.encode(MessageType.HEARTBEAT, 0, 0L, new byte[10]);
        byte[] truncated = new byte[frame.length - 5];
        System.arraycopy(frame, 0, truncated, 0, truncated.length);

        assertThrows(IllegalArgumentException.class,
                () -> FrameCodec.decode(truncated));
    }

    @Test
    void allMessageTypesRoundtrip() {
        for (MessageType type : MessageType.values()) {
            byte[] frame = FrameCodec.encode(type, 1, 1L, new byte[0]);
            FrameCodec.Frame decoded = FrameCodec.decode(frame);
            assertEquals(type, decoded.messageType());
        }
    }

    @Test
    void nonZeroReservedEpochRejected() {
        // The 8-byte epoch at offset 18 is MBZ in v1. Set it non-zero, repair the CRC so the
        // MBZ check (not the checksum) is what fires, and expect a fail-closed rejection: a
        // non-zero reserved field is a newer peer this reader cannot safely interpret.
        byte[] frame = FrameCodec.encode(MessageType.APPEND_ENTRIES, 42, 7L, "payload".getBytes());
        ByteBuffer.wrap(frame).putLong(18, 1L); // stamp epoch = 1 (offset 18, after ver+type+groupId+term)
        recomputeCrc(frame);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> FrameCodec.decode(frame));
        assertTrue(ex.getMessage().contains("epoch"),
                "a non-zero reserved epoch must fail closed on the MBZ check, got: " + ex.getMessage());
    }

    /** Recomputes the CRC32C trailer over [0, len-4) so only the field-level check under test fails. */
    private static void recomputeCrc(byte[] frame) {
        int crcOffset = frame.length - FrameCodec.TRAILER_SIZE;
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(frame, 0, crcOffset);
        ByteBuffer.wrap(frame).putInt(crcOffset, (int) crc.getValue());
    }
}
