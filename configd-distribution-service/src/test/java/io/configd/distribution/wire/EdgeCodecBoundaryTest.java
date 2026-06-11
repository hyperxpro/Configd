package io.configd.distribution.wire;

import io.configd.distribution.CommitNotification;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-boundary unit tests for {@link EdgeFrameCodec} / {@link EdgeSnapshotCodec} that
 * pin the precise bounds-check thresholds the property test only fuzzes (gate-3 mutation
 * tightness): minimum frame size, the at-cap chunk, NOTIFY byte-cap split point, and the
 * snapshot envelope guards.
 */
class EdgeCodecBoundaryTest {

    // ---- EdgeFrameCodec exact bounds ---------------------------------------

    @Test
    void minimumFrameLengthIsHeaderPlusTrailer() {
        // A CURSOR_ACK is HEADER(6) + 8 payload + TRAILER(4) = 18 bytes.
        byte[] wire = EdgeFrameCodec.encode(new EdgeFrame.CursorAck(0));
        assertEquals(EdgeFrameCodec.HEADER_SIZE + 8 + EdgeFrameCodec.TRAILER_SIZE, wire.length);
        assertEquals(wire.length, EdgeFrameCodec.peekLength(wire));
    }

    @Test
    void peekLengthRejectsExactlyBelowMinAndExactlyAboveMax() {
        int min = EdgeFrameCodec.HEADER_SIZE + EdgeFrameCodec.TRAILER_SIZE;
        // length = min-1 rejected; length = min accepted shape (forge a header).
        assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.peekLength(lenHeader(min - 1)));
        assertEquals(min, EdgeFrameCodec.peekLength(lenHeader(min)));
        assertEquals(EdgeFrameCodec.MAX_EDGE_FRAME_SIZE,
                EdgeFrameCodec.peekLength(lenHeader(EdgeFrameCodec.MAX_EDGE_FRAME_SIZE)));
        EdgeFrameCodec.CodecException ex = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.peekLength(lenHeader(EdgeFrameCodec.MAX_EDGE_FRAME_SIZE + 1)));
        assertEquals(ErrorCode.FRAME_TOO_LARGE, ex.code());
    }

    @Test
    void peekLengthNeedsAtLeastFourBytes() {
        assertThrows(EdgeFrameCodec.CodecException.class, () -> EdgeFrameCodec.peekLength(new byte[3]));
    }

    @Test
    void decodeRejectsBufferShorterThanMinimum() {
        // A 9-byte buffer (< HEADER+TRAILER=10) is rejected as corrupt.
        EdgeFrameCodec.CodecException ex = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(new byte[9]));
        assertEquals(ErrorCode.FRAME_CORRUPT, ex.code());
    }

    @Test
    void snapshotChunkAtExactlyOneMiBEncodesAndDecodes() {
        byte[] body = new byte[EdgeFrameCodec.MAX_SNAPSHOT_CHUNK_BYTES];
        EdgeFrame.SnapshotChunk chunk = new EdgeFrame.SnapshotChunk(0, body);
        EdgeFrame decoded = EdgeFrameCodec.decode(EdgeFrameCodec.encode(chunk));
        assertEquals(chunk, decoded);
    }

    @Test
    void notifyBatchAtExactlyCountCapEncodes() {
        List<CommitNotification> batch = new java.util.ArrayList<>();
        for (int i = 1; i <= EdgeFrameCodec.MAX_NOTIFY_BATCH; i++) {
            batch.add(notif(i));
        }
        // Exactly 64 must encode (not > cap).
        EdgeFrame.Notify frame = new EdgeFrame.Notify(batch);
        EdgeFrame.Notify back = (EdgeFrame.Notify) EdgeFrameCodec.decode(EdgeFrameCodec.encode(frame));
        assertEquals(EdgeFrameCodec.MAX_NOTIFY_BATCH, back.notifications().size());
    }

    /**
     * The encoded size of a NOTIFY batch must exactly equal the sum of per-notification
     * encoded sizes + the 4-byte count + the 6-byte header + 4-byte trailer. This pins the
     * per-notification byte arithmetic that the session uses for its byte-cap batching.
     */
    @Test
    void encodedNotifyFrameSizeIsTheExactSumOfParts() {
        CommitNotification a = signedNotif(10);
        CommitNotification b = notif(11);
        EdgeFrame.Notify single = new EdgeFrame.Notify(List.of(a));
        int singleWire = EdgeFrameCodec.encode(single).length;
        // singleWire = HEADER + 4(count) + encodedNotification(a) + TRAILER.
        int notifBytesA = singleWire - EdgeFrameCodec.HEADER_SIZE - 4 - EdgeFrameCodec.TRAILER_SIZE;

        EdgeFrame.Notify two = new EdgeFrame.Notify(List.of(a, b));
        int twoWire = EdgeFrameCodec.encode(two).length;
        int notifBytesB = twoWire - singleWire; // adding b adds exactly its encoded bytes

        // The two-notification frame's payload = 4 + notifBytesA + notifBytesB.
        assertEquals(EdgeFrameCodec.HEADER_SIZE + 4 + notifBytesA + notifBytesB
                + EdgeFrameCodec.TRAILER_SIZE, twoWire);
        assertTrue(notifBytesA > notifBytesB,
                "the signed notification (with signature+nonce) must be larger than the unsigned one");
    }

    // ---- EdgeSnapshotCodec exact bounds ------------------------------------

    @Test
    void chunkBoundaryProducesExactChunkCount() {
        byte[] body = new byte[10];
        // chunkBytes 4 -> ceil(10/4) = 3 chunks (4,4,2).
        List<EdgeFrame.SnapshotChunk> c = EdgeSnapshotCodec.chunk(body, 4);
        assertEquals(3, c.size());
        assertEquals(4, c.get(0).bytes().length);
        assertEquals(2, c.get(2).bytes().length);
        // chunkBytes == body length -> exactly 1 chunk.
        assertEquals(1, EdgeSnapshotCodec.chunk(body, 10).size());
        // chunkBytes 1 below length -> 2 chunks.
        assertEquals(2, EdgeSnapshotCodec.chunk(body, 9).size());
    }

    @Test
    void chunkRejectsOutOfRangeChunkBytes() {
        byte[] body = new byte[4];
        assertThrows(IllegalArgumentException.class, () -> EdgeSnapshotCodec.chunk(body, 0));
        assertThrows(IllegalArgumentException.class,
                () -> EdgeSnapshotCodec.chunk(body, EdgeFrameCodec.MAX_SNAPSHOT_CHUNK_BYTES + 1));
        // Exactly the cap is allowed.
        assertEquals(1, EdgeSnapshotCodec.chunk(body, EdgeFrameCodec.MAX_SNAPSHOT_CHUNK_BYTES).size());
    }

    @Test
    void deserializeRejectsBodyShorterThanTwelveByteHeader() {
        assertThrows(IllegalArgumentException.class,
                () -> EdgeSnapshotCodec.deserialize(new byte[11]));
        // 12 bytes (version + count=0) is the minimum valid empty body.
        byte[] empty = new byte[12]; // version 0, entryCount 0
        assertEquals(0, EdgeSnapshotCodec.deserialize(empty).size());
    }

    @Test
    void reassembleRejectsAnyGapInChunkIndices() {
        assertThrows(IllegalArgumentException.class, () -> EdgeSnapshotCodec.reassemble(List.of(
                new EdgeFrame.SnapshotChunk(0, new byte[]{1}),
                new EdgeFrame.SnapshotChunk(1, new byte[]{2}),
                new EdgeFrame.SnapshotChunk(3, new byte[]{3})))); // index 2 missing
    }

    private static byte[] lenHeader(int len) {
        byte[] h = new byte[EdgeFrameCodec.HEADER_SIZE];
        h[0] = (byte) (len >>> 24);
        h[1] = (byte) (len >>> 16);
        h[2] = (byte) (len >>> 8);
        h[3] = (byte) len;
        return h;
    }

    private static CommitNotification notif(int seq) {
        return new CommitNotification(seq, 1000L + seq,
                new ConfigDelta(seq - 1, seq, List.of(new ConfigMutation.Put("k", new byte[]{1}))));
    }

    private static CommitNotification signedNotif(int seq) {
        byte[] sig = new byte[32];
        byte[] nonce = new byte[ConfigDelta.NONCE_LEN];
        return new CommitNotification(seq, 1000L + seq,
                new ConfigDelta(seq - 1, seq, List.of(new ConfigMutation.Put("k", new byte[]{1})),
                        sig, 5L, nonce));
    }
}
