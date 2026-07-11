package io.configd.distribution.wire;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Codec-level tests for the topology-epoch binding on the watch cursor and the SUBSCRIBE resume
 * token. The server-side STALE_TOPOLOGY policy (epoch != current) is exercised by the
 * fan-out coordinator tests; here we pin the wire-format facts: the epoch round-trips, the
 * reserved-illegal epoch {@code 0} decodes as FRAME_CORRUPT, and a cursor payload below the
 * 12-byte floor decodes as FRAME_CORRUPT (never an uncaught underflow).
 */
class EdgeCursorEpochWireTest {

    private static final byte V2 = EdgeFrameCodec.EDGE_WIRE_VERSION_V2;

    /** Recompute the frame CRC32C trailer in place after mutating a payload byte. */
    private static void rewriteCrc(byte[] wire) {
        int crcOffset = wire.length - EdgeFrameCodec.TRAILER_SIZE;
        CRC32C crc = new CRC32C();
        crc.update(wire, 0, crcOffset);
        ByteBuffer.wrap(wire, crcOffset, EdgeFrameCodec.TRAILER_SIZE).putInt((int) crc.getValue());
    }

    /** Assemble a raw edge frame [len:4][ver:1][type:1][payload][crc:4] with a correct length + CRC. */
    private static byte[] frame(byte version, int typeCode, byte[] payload) {
        int len = 6 + payload.length + EdgeFrameCodec.TRAILER_SIZE;
        byte[] wire = new byte[len];
        ByteBuffer buf = ByteBuffer.wrap(wire);
        buf.putInt(len);
        buf.put(version);
        buf.put((byte) typeCode);
        buf.put(payload);
        rewriteCrc(wire);
        return wire;
    }

    @Test
    void watchCursorEpochRoundTripsThroughWatchProgress() {
        WatchCursor cursor = new WatchCursor(7L, List.of(new WatchCursor.Component(0, 42L)));
        EdgeFrame.WatchProgress wp = new EdgeFrame.WatchProgress(1L, cursor, 9L);
        EdgeFrame.WatchProgress back =
                (EdgeFrame.WatchProgress) EdgeFrameCodec.decode(EdgeFrameCodec.encode(wp, V2), V2);
        assertEquals(7L, back.cursor().topologyEpoch(), "the topology epoch survives the round-trip");
        assertEquals(cursor, back.cursor());
    }

    @Test
    void epochZeroCursorIsFrameCorrupt() {
        // A valid WATCH_PROGRESS (from-now cursor, epoch 1): watchId@6, topologyEpoch@14. Zero the epoch
        // and repair the CRC so the failure is the reserved-illegal epoch, not a CRC mismatch.
        byte[] wire = EdgeFrameCodec.encode(new EdgeFrame.WatchProgress(7L, WatchCursor.fromNow(), 9L), V2);
        ByteBuffer.wrap(wire).putLong(14, 0L);
        rewriteCrc(wire);
        EdgeFrameCodec.CodecException ex =
                assertThrows(EdgeFrameCodec.CodecException.class, () -> EdgeFrameCodec.decode(wire, V2));
        assertEquals(ErrorCode.FRAME_CORRUPT, ex.code());
    }

    @Test
    void cursorUnderflowIsFrameCorrupt() {
        // A WATCH_PROGRESS whose cursor region is below the 12-byte [epoch:8][count:4] floor.
        // Payload = [watchId:8][only 4 bytes], so decodeCursor sees remaining < 12 -> FRAME_CORRUPT,
        // never an uncaught BufferUnderflowException.
        byte[] payload = new byte[8 + 4];
        ByteBuffer.wrap(payload).putLong(1L); // watchId; the trailing 4 bytes are an underflowing cursor
        byte[] wire = frame(V2, FrameType.WATCH_PROGRESS.code(), payload);
        EdgeFrameCodec.CodecException ex =
                assertThrows(EdgeFrameCodec.CodecException.class, () -> EdgeFrameCodec.decode(wire, V2));
        assertEquals(ErrorCode.FRAME_CORRUPT, ex.code());
    }

    @Test
    void subscribeCarriesEpoch() {
        EdgeFrame.Subscribe sub = new EdgeFrame.Subscribe(
                false, List.of("svc/"), 5L, 100L, 200L, "edge-A", false);
        EdgeFrame.Subscribe back = (EdgeFrame.Subscribe) EdgeFrameCodec.decode(EdgeFrameCodec.encode(sub));
        assertEquals(5L, back.topologyEpoch(), "the SUBSCRIBE resume token binds the topology epoch");
        assertEquals(sub, back);
    }

    @Test
    void subscribeEpochZeroIsFrameCorrupt() {
        // Full-store SUBSCRIBE payload: [fullStore:1][prefixCount:4][topologyEpoch:8]... => epoch@11.
        byte[] wire = EdgeFrameCodec.encode(
                new EdgeFrame.Subscribe(true, List.of(), 3L, -1L, "e"));
        ByteBuffer.wrap(wire).putLong(11, 0L);
        rewriteCrc(wire);
        EdgeFrameCodec.CodecException ex =
                assertThrows(EdgeFrameCodec.CodecException.class, () -> EdgeFrameCodec.decode(wire));
        assertEquals(ErrorCode.FRAME_CORRUPT, ex.code());
    }

    /** A non-empty prefix SUBSCRIBE places the epoch after the prefix bytes; confirm it still binds. */
    @Test
    void prefixSubscribeStillCarriesEpoch() {
        EdgeFrame.Subscribe sub = new EdgeFrame.Subscribe(
                false, List.of("a/", "b/"), 9L, 0L, -1L, "edge-B", false);
        byte[] wire = EdgeFrameCodec.encode(sub);
        EdgeFrame.Subscribe back = (EdgeFrame.Subscribe) EdgeFrameCodec.decode(wire);
        assertEquals(9L, back.topologyEpoch());
        // Sanity: the prefixes survive too (the epoch was inserted after them, not over them).
        assertEquals("a/", back.prefixes().get(0));
    }
}
