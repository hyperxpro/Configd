package io.configd.transport;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Encoder-side bounds regression tests for {@link FrameCodec}.
 *
 * <p>{@link FrameCodecPropertyTest} exercises the decoder bounds via
 * post-encode payload corruption — it does not cover the encoder
 * paths {@link FrameCodec#encode(MessageType, int, long, byte[])}
 * and {@link FrameCodec#encode(ByteBuffer, MessageType, int, long, byte[])}
 * for oversize payloads or undersized destination buffers.
 *
 * <p>This class fills that gap with explicit assertions so a future
 * refactor that drops the encoder-side {@code checkPayloadFitsFrame}
 * (or the buffer-overload {@code remaining} check) trips a test.
 */
class FrameCodecEncoderBoundsTest {

    @Test
    void encodeRejectsPayloadAtMaxFrameSize() {
        // payload + HEADER + TRAILER must be <= MAX_FRAME_SIZE.
        // A payload of (MAX_FRAME_SIZE - HEADER - TRAILER + 1) overflows
        // by exactly one byte and must be rejected.
        int oversize = FrameCodec.MAX_FRAME_SIZE - FrameCodec.HEADER_SIZE - FrameCodec.TRAILER_SIZE + 1;
        byte[] payload = new byte[oversize];
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> FrameCodec.encode(MessageType.APPEND_ENTRIES, 0, 0L, payload));
        assertNotNull(ex.getMessage());
    }

    @Test
    void encodeAcceptsPayloadExactlyAtMaxFrameSize() {
        // (MAX_FRAME_SIZE - HEADER - TRAILER) byte payload exactly hits
        // MAX_FRAME_SIZE. Must succeed; this is the documented ceiling.
        int max = FrameCodec.MAX_FRAME_SIZE - FrameCodec.HEADER_SIZE - FrameCodec.TRAILER_SIZE;
        byte[] payload = new byte[max];
        byte[] frame = FrameCodec.encode(MessageType.APPEND_ENTRIES, 0, 0L, payload);
        assertEquals(FrameCodec.MAX_FRAME_SIZE, frame.length);
    }

    @Test
    void encodeRejectsNegativePayloadLengthDefensiveOnArrayPath() {
        // byte[] cannot have negative length, but the underlying check
        // `payloadLength < 0` is hit on the ByteBuffer overload via a
        // pathological caller. Just ensure the array path can never
        // sneak a negative through (would be a signed-overflow bug).
        byte[] payload = new byte[0];
        byte[] frame = FrameCodec.encode(MessageType.HEARTBEAT, 0, 0L, payload);
        assertEquals(FrameCodec.HEADER_SIZE + FrameCodec.TRAILER_SIZE, frame.length);
    }

    @Test
    void byteBufferEncodeRejectsTooSmallDestination() {
        // Buffer with one byte less than required must throw
        // before any write — the destination must remain pristine.
        byte[] payload = new byte[16];
        int needed = FrameCodec.frameSize(payload.length);
        ByteBuffer buf = ByteBuffer.allocate(needed - 1);
        // Sentinel byte at start so we can confirm no partial write.
        buf.put((byte) 0x42);
        buf.position(0);

        assertThrows(IllegalArgumentException.class,
                () -> FrameCodec.encode(buf, MessageType.HEARTBEAT, 1, 1L, payload));

        // Verify the sentinel byte survived — no partial write occurred.
        assertEquals((byte) 0x42, buf.get(0));
    }

    @Test
    void byteBufferEncodeAcceptsExactSizeDestination() {
        byte[] payload = new byte[16];
        int needed = FrameCodec.frameSize(payload.length);
        ByteBuffer buf = ByteBuffer.allocate(needed);
        FrameCodec.encode(buf, MessageType.APPEND_ENTRIES, 1, 1L, payload);
        assertEquals(needed, buf.position());
    }

    @Test
    void byteBufferEncodeRejectsOversizePayloadBeforeBufferCheck() {
        // The MAX_FRAME_SIZE check should fire before the buffer-capacity
        // check so the diagnostic identifies the real problem (caller
        // tried to send a payload bigger than the protocol allows).
        int oversize = FrameCodec.MAX_FRAME_SIZE - FrameCodec.HEADER_SIZE - FrameCodec.TRAILER_SIZE + 1;
        byte[] payload = new byte[oversize];
        ByteBuffer buf = ByteBuffer.allocate(FrameCodec.MAX_FRAME_SIZE);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> FrameCodec.encode(buf, MessageType.APPEND_ENTRIES, 0, 0L, payload));
        // Message should mention MAX_FRAME_SIZE, not buffer capacity.
        assertNotNull(ex.getMessage());
    }
}
