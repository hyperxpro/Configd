package io.configd.distribution.wire;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codec-strictness batch (Gate 2 Workstream D): WH-14 - the NOTIFY decoder enforces the same
 * {@link EdgeFrameCodec#MAX_NOTIFY_BATCH_BYTES} payload-sum cap the encoder does, for canonical-encoding
 * parity. The over-cap batch cannot be produced by {@link EdgeFrameCodec#encode} (the encoder rejects it),
 * so the frame is hand-framed: the decode-side size gate fires on {@code p.remaining()} BEFORE any content
 * is parsed, so a garbage payload of the right SIZE is a sufficient and precise probe of the boundary.
 */
class EdgeFrameCodecStrictnessTest {

    /** Frames an arbitrary NOTIFY payload as a well-formed 0x01 edge frame (valid length + CRC). */
    private static byte[] frameNotify(byte[] payload) {
        int total = EdgeFrameCodec.HEADER_SIZE + payload.length + EdgeFrameCodec.TRAILER_SIZE;
        ByteBuffer buf = ByteBuffer.allocate(total);
        buf.putInt(total);
        buf.put(EdgeFrameCodec.EDGE_WIRE_VERSION);
        buf.put((byte) FrameType.NOTIFY.code());
        buf.put(payload);
        CRC32C crc = new CRC32C();
        crc.update(buf.array(), 0, total - EdgeFrameCodec.TRAILER_SIZE);
        buf.putInt((int) crc.getValue());
        return buf.array();
    }

    @Test
    void notifyPayloadOverCapRejectedOnDecode() {
        // WH-14: a NOTIFY payload one byte over the 256 KiB cap is rejected as FRAME_TOO_LARGE, matching
        // the encode-side ceiling. Total frame (~256 KiB) is well under the 2 MiB frame cap, so this is
        // the NOTIFY sum cap firing, not the frame cap.
        byte[] payload = new byte[EdgeFrameCodec.MAX_NOTIFY_BATCH_BYTES + 1];
        var ex = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(frameNotify(payload)));
        assertEquals(ErrorCode.FRAME_TOO_LARGE, ex.code(), ex.getMessage());
        assertTrue(ex.getMessage().contains("MAX_NOTIFY_BATCH_BYTES"), ex.getMessage());
    }

    @Test
    void notifyPayloadAtCapPassesTheSizeGate() {
        // Boundary control: a payload of EXACTLY the cap does NOT trip the WH-14 size gate (remaining ==
        // cap, not > cap). It fails LATER on content parsing (FRAME_CORRUPT), which proves the gate is
        // exactly `> MAX` and does not off-by-one-reject a maximal legitimate batch.
        byte[] payload = new byte[EdgeFrameCodec.MAX_NOTIFY_BATCH_BYTES];
        var ex = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(frameNotify(payload)));
        assertNotEquals(ErrorCode.FRAME_TOO_LARGE, ex.code(),
                "a payload exactly at the cap must clear the size gate, got: " + ex.getMessage());
    }
}
