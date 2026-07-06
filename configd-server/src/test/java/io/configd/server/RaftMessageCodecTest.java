package io.configd.server;

import io.configd.common.NodeId;
import io.configd.raft.*;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RaftMessageCodec}: verifies round-trip encode/decode
 * for every {@link RaftMessage} variant.
 */
class RaftMessageCodecTest {

    private static final int GROUP_ID = 42;

    @Nested
    class AppendEntriesRoundTrip {

        @Test
        void heartbeatRoundTrip() {
            var req = new AppendEntriesRequest(5L, NodeId.of(1), 10L, 4L, List.of(), 9L);
            FrameCodec.Frame frame = RaftMessageCodec.encode(req, GROUP_ID);
            RaftMessage decoded = RaftMessageCodec.decode(frame);

            assertInstanceOf(AppendEntriesRequest.class, decoded);
            var result = (AppendEntriesRequest) decoded;
            assertEquals(5L, result.term());
            assertEquals(NodeId.of(1), result.leaderId());
            assertEquals(10L, result.prevLogIndex());
            assertEquals(4L, result.prevLogTerm());
            assertEquals(0, result.entries().size());
            assertEquals(9L, result.leaderCommit());
        }

        @Test
        void withEntriesRoundTrip() {
            var entries = List.of(
                    new LogEntry(11L, 5L, new byte[]{1, 2, 3}),
                    new LogEntry(12L, 5L, new byte[]{4, 5}),
                    LogEntry.noop(13L, 5L)
            );
            var req = new AppendEntriesRequest(5L, NodeId.of(2), 10L, 4L, entries, 9L);
            FrameCodec.Frame frame = RaftMessageCodec.encode(req, GROUP_ID);
            RaftMessage decoded = RaftMessageCodec.decode(frame);

            assertInstanceOf(AppendEntriesRequest.class, decoded);
            var result = (AppendEntriesRequest) decoded;
            assertEquals(3, result.entries().size());
            assertEquals(entries.get(0), result.entries().get(0));
            assertEquals(entries.get(1), result.entries().get(1));
            assertEquals(entries.get(2), result.entries().get(2));
        }
    }

    @Test
    void appendEntriesResponseRoundTrip() {
        var resp = new AppendEntriesResponse(5L, true, 12L, NodeId.of(3));
        FrameCodec.Frame frame = RaftMessageCodec.encode(resp, GROUP_ID);
        RaftMessage decoded = RaftMessageCodec.decode(frame);

        assertInstanceOf(AppendEntriesResponse.class, decoded);
        var result = (AppendEntriesResponse) decoded;
        assertEquals(5L, result.term());
        assertTrue(result.success());
        assertEquals(12L, result.matchIndex());
        assertEquals(NodeId.of(3), result.from());
    }

    @Test
    void appendEntriesResponseFailureRoundTrip() {
        var resp = new AppendEntriesResponse(3L, false, 0L, NodeId.of(2));
        FrameCodec.Frame frame = RaftMessageCodec.encode(resp, GROUP_ID);
        var result = (AppendEntriesResponse) RaftMessageCodec.decode(frame);
        assertFalse(result.success());
    }

    @Nested
    class RequestVoteRoundTrip {

        @Test
        void regularVoteRoundTrip() {
            var req = new RequestVoteRequest(7L, NodeId.of(4), 20L, 6L, false);
            FrameCodec.Frame frame = RaftMessageCodec.encode(req, GROUP_ID);
            RaftMessage decoded = RaftMessageCodec.decode(frame);

            assertInstanceOf(RequestVoteRequest.class, decoded);
            var result = (RequestVoteRequest) decoded;
            assertEquals(7L, result.term());
            assertEquals(NodeId.of(4), result.candidateId());
            assertEquals(20L, result.lastLogIndex());
            assertEquals(6L, result.lastLogTerm());
            assertFalse(result.preVote());
        }

        @Test
        void preVoteRoundTrip() {
            var req = new RequestVoteRequest(7L, NodeId.of(4), 20L, 6L, true);
            FrameCodec.Frame frame = RaftMessageCodec.encode(req, GROUP_ID);
            var result = (RequestVoteRequest) RaftMessageCodec.decode(frame);
            assertTrue(result.preVote());
        }
    }

    @Nested
    class RequestVoteResponseRoundTrip {

        @Test
        void grantedRoundTrip() {
            var resp = new RequestVoteResponse(7L, true, NodeId.of(5), false);
            FrameCodec.Frame frame = RaftMessageCodec.encode(resp, GROUP_ID);
            var result = (RequestVoteResponse) RaftMessageCodec.decode(frame);
            assertEquals(7L, result.term());
            assertTrue(result.voteGranted());
            assertEquals(NodeId.of(5), result.from());
            assertFalse(result.preVote());
        }

        @Test
        void preVoteResponseRoundTrip() {
            var resp = new RequestVoteResponse(7L, false, NodeId.of(5), true);
            FrameCodec.Frame frame = RaftMessageCodec.encode(resp, GROUP_ID);
            var result = (RequestVoteResponse) RaftMessageCodec.decode(frame);
            assertFalse(result.voteGranted());
            assertTrue(result.preVote());
        }
    }

    @Nested
    class InstallSnapshotRoundTrip {

        @Test
        void withDataRoundTrip() {
            byte[] data = {10, 20, 30, 40, 50};
            var req = new InstallSnapshotRequest(8L, NodeId.of(1), 100L, 7L, 0, data, true);
            FrameCodec.Frame frame = RaftMessageCodec.encode(req, GROUP_ID);
            var result = (InstallSnapshotRequest) RaftMessageCodec.decode(frame);

            assertEquals(8L, result.term());
            assertEquals(NodeId.of(1), result.leaderId());
            assertEquals(100L, result.lastIncludedIndex());
            assertEquals(7L, result.lastIncludedTerm());
            assertEquals(0, result.offset());
            assertArrayEquals(data, result.data());
            assertTrue(result.done());
        }

        @Test
        void withClusterConfigRoundTrip() {
            byte[] data = {1, 2, 3};
            byte[] config = {99, 88, 77};
            var req = new InstallSnapshotRequest(8L, NodeId.of(1), 100L, 7L, 0, data, true, config);
            FrameCodec.Frame frame = RaftMessageCodec.encode(req, GROUP_ID);
            var result = (InstallSnapshotRequest) RaftMessageCodec.decode(frame);

            assertArrayEquals(data, result.data());
            assertArrayEquals(config, result.clusterConfigData());
        }

        @Test
        void emptyDataRoundTrip() {
            var req = new InstallSnapshotRequest(8L, NodeId.of(1), 100L, 7L, 0, new byte[0], true);
            FrameCodec.Frame frame = RaftMessageCodec.encode(req, GROUP_ID);
            var result = (InstallSnapshotRequest) RaftMessageCodec.decode(frame);
            assertEquals(0, result.data().length);
        }

        @Test
        void midStreamChunkRoundTrip() {
            // An intermediate chunk of a chunked transfer: nonzero offset, not the final chunk, and
            // no cluster config (config rides only the final chunk).
            byte[] data = {5, 6, 7, 8};
            var req = new InstallSnapshotRequest(8L, NodeId.of(1), 100L, 7L, 4096, data, false);
            FrameCodec.Frame frame = RaftMessageCodec.encode(req, GROUP_ID);
            var result = (InstallSnapshotRequest) RaftMessageCodec.decode(frame);

            assertEquals(4096, result.offset());
            assertFalse(result.done());
            assertArrayEquals(data, result.data());
            assertNull(result.clusterConfigData());
        }

        @Test
        void chunkExceedingPerChunkCapIsRejectedOnEncode() {
            // A single chunk larger than the per-chunk data ceiling is rejected on encode: the
            // sender's chunk size must stay at or below this cap. The TOTAL snapshot is unbounded
            // (it streams as many chunks), but no single chunk may exceed one frame.
            byte[] tooBig = new byte[RaftMessageCodec.MAX_SNAPSHOT_BLOB_LEN + 1];
            var req = new InstallSnapshotRequest(8L, NodeId.of(1), 100L, 7L, 0, tooBig, true);
            assertThrows(IllegalArgumentException.class, () -> RaftMessageCodec.encode(req, GROUP_ID));
        }
    }

    @Test
    void installSnapshotResponseRoundTrip() {
        // Carries a non-zero nextExpectedOffset (chunked-transfer position) to prove it round-trips.
        var resp = new InstallSnapshotResponse(8L, true, NodeId.of(3), 42L, 65536);
        FrameCodec.Frame frame = RaftMessageCodec.encode(resp, GROUP_ID);
        var result = (InstallSnapshotResponse) RaftMessageCodec.decode(frame);

        assertEquals(8L, result.term());
        assertTrue(result.success());
        assertEquals(NodeId.of(3), result.from());
        assertEquals(42L, result.lastIncludedIndex());
        assertEquals(65536, result.nextExpectedOffset());
    }

    @Test
    void installSnapshotResponseDefaultsOffsetWhenAbsentOnWire() {
        // An InstallSnapshotResponse frame WITHOUT the trailing nextExpectedOffset field (the 13-byte
        // pre-chunking layout) must still decode, defaulting the offset to 0. This is the optional-
        // trailing-field contract the decoder relies on.
        ByteBuffer legacy = ByteBuffer.allocate(1 + 4 + 8);
        legacy.put((byte) 1);            // success
        legacy.putInt(NodeId.of(3).id());
        legacy.putLong(42L);             // lastIncludedIndex
        FrameCodec.Frame frame = new FrameCodec.Frame(
                MessageType.INSTALL_SNAPSHOT_RESPONSE, GROUP_ID, 8L, legacy.array());
        var result = (InstallSnapshotResponse) RaftMessageCodec.decode(frame);

        assertEquals(42L, result.lastIncludedIndex());
        assertEquals(0, result.nextExpectedOffset());
    }

    @Test
    void timeoutNowRoundTrip() {
        var req = new TimeoutNowRequest(9L, NodeId.of(1));
        FrameCodec.Frame frame = RaftMessageCodec.encode(req, GROUP_ID);
        var result = (TimeoutNowRequest) RaftMessageCodec.decode(frame);

        assertEquals(9L, result.term());
        assertEquals(NodeId.of(1), result.leaderId());
    }

    @Test
    void groupIdPreservedInFrame() {
        var req = new AppendEntriesRequest(1L, NodeId.of(1), 0L, 0L, List.of(), 0L);
        FrameCodec.Frame frame = RaftMessageCodec.encode(req, 99);
        assertEquals(99, frame.groupId());
    }

    @Test
    void termPreservedInFrame() {
        var req = new RequestVoteRequest(42L, NodeId.of(1), 0L, 0L, false);
        FrameCodec.Frame frame = RaftMessageCodec.encode(req, GROUP_ID);
        assertEquals(42L, frame.term());
    }

    /**
     * Codec-strictness batch (Gate 2 Workstream D): WH-05 (negative InstallSnapshot offset) and WH-06
     * (strict-end trailing-byte rejection on the request-side fixed-shape decoders). Every check rejects
     * only a malformed frame; the round-trip tests above prove well-formed frames are unaffected.
     */
    @Nested
    class CodecStrictness {

        /** Rebuilds a frame with {@code n} zero padding bytes appended to a valid payload. */
        private FrameCodec.Frame withTrailing(FrameCodec.Frame good, int n) {
            byte[] padded = Arrays.copyOf(good.payload(), good.payload().length + n);
            return new FrameCodec.Frame(good.messageType(), good.groupId(), good.term(), padded);
        }

        @Test
        void appendEntriesRejectsTrailingBytes() {
            // WH-06: a fixed-shape AppendEntries carries no padding past its declared entries.
            var entries = List.of(new LogEntry(11L, 5L, new byte[]{1, 2, 3}));
            var req = new AppendEntriesRequest(5L, NodeId.of(2), 10L, 4L, entries, 9L);
            FrameCodec.Frame good = RaftMessageCodec.encode(req, GROUP_ID);
            assertDoesNotThrow(() -> RaftMessageCodec.decode(good)); // control: valid frame unaffected

            FrameCodec.Frame bad = withTrailing(good, 1);
            var ex = assertThrows(IllegalArgumentException.class, () -> RaftMessageCodec.decode(bad));
            assertTrue(ex.getMessage().contains("trailing"), ex.getMessage());
        }

        @Test
        void appendEntriesHeartbeatRejectsTrailingBytes() {
            // An empty heartbeat (0 entries) is the tightest fixed shape: any trailing byte is padding.
            var req = new AppendEntriesRequest(5L, NodeId.of(1), 10L, 4L, List.of(), 9L);
            FrameCodec.Frame bad = withTrailing(RaftMessageCodec.encode(req, GROUP_ID), 4);
            assertThrows(IllegalArgumentException.class, () -> RaftMessageCodec.decode(bad));
        }

        @Test
        void installSnapshotRejectsTrailingBytesAfterConfig() {
            // WH-06: trailing bytes past the optional configData blob are rejected.
            byte[] data = {1, 2, 3};
            byte[] config = {9, 8, 7};
            var req = new InstallSnapshotRequest(8L, NodeId.of(1), 100L, 7L, 0, data, true, config);
            FrameCodec.Frame good = RaftMessageCodec.encode(req, GROUP_ID);
            assertDoesNotThrow(() -> RaftMessageCodec.decode(good)); // control

            FrameCodec.Frame bad = withTrailing(good, 2);
            var ex = assertThrows(IllegalArgumentException.class, () -> RaftMessageCodec.decode(bad));
            assertTrue(ex.getMessage().contains("trailing"), ex.getMessage());
        }

        @Test
        void installSnapshotRejectsTrailingBytesWithNoConfig() {
            // The no-config shape (encoder writes configLen=0) must also reject padding after it.
            byte[] data = {5, 6, 7, 8};
            var req = new InstallSnapshotRequest(8L, NodeId.of(1), 100L, 7L, 4096, data, false);
            FrameCodec.Frame bad = withTrailing(RaftMessageCodec.encode(req, GROUP_ID), 1);
            assertThrows(IllegalArgumentException.class, () -> RaftMessageCodec.decode(bad));
        }

        @Test
        void installSnapshotRejectsNegativeOffset() {
            // WH-05: a negative chunk offset is rejected at decode (symmetry with the response's
            // nextExpectedOffset check). Build a raw payload with offset = -1, dataLen = 0.
            ByteBuffer p = ByteBuffer.allocate(4 + 8 + 8 + 4 + 1 + 4);
            p.putInt(NodeId.of(1).id());
            p.putLong(100L); // lastIncludedIndex
            p.putLong(7L);   // lastIncludedTerm
            p.putInt(-1);    // offset (negative - illegal)
            p.put((byte) 1); // done
            p.putInt(0);     // dataLen
            FrameCodec.Frame bad = new FrameCodec.Frame(
                    MessageType.INSTALL_SNAPSHOT, GROUP_ID, 8L, p.array());
            var ex = assertThrows(IllegalArgumentException.class, () -> RaftMessageCodec.decode(bad));
            assertTrue(ex.getMessage().contains("Negative InstallSnapshot offset"), ex.getMessage());
        }

        @Test
        void installSnapshotResponseStillToleratesAbsentTrailingOffset() {
            // Negative control for WH-06: the response's nextExpectedOffset is a DELIBERATE optional
            // trailing field and must NOT be made strict-end. A 13-byte legacy response still decodes.
            ByteBuffer legacy = ByteBuffer.allocate(1 + 4 + 8);
            legacy.put((byte) 1);
            legacy.putInt(NodeId.of(3).id());
            legacy.putLong(42L);
            FrameCodec.Frame frame = new FrameCodec.Frame(
                    MessageType.INSTALL_SNAPSHOT_RESPONSE, GROUP_ID, 8L, legacy.array());
            var result = (InstallSnapshotResponse) RaftMessageCodec.decode(frame);
            assertEquals(0, result.nextExpectedOffset());
        }

        // ---- C2 (WH-06 completeness): strict-end on the remaining fixed-size decoders ----

        @Test
        void appendEntriesResponseRejectsTrailingBytes() {
            var resp = new AppendEntriesResponse(5L, true, 12L, NodeId.of(3));
            FrameCodec.Frame good = RaftMessageCodec.encode(resp, GROUP_ID);
            assertDoesNotThrow(() -> RaftMessageCodec.decode(good)); // control
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> RaftMessageCodec.decode(withTrailing(good, 1)));
            assertTrue(ex.getMessage().contains("trailing"), ex.getMessage());
        }

        @Test
        void requestVoteRejectsTrailingBytes() {
            var req = new RequestVoteRequest(5L, NodeId.of(2), 10L, 4L, false);
            FrameCodec.Frame good = RaftMessageCodec.encode(req, GROUP_ID);
            assertDoesNotThrow(() -> RaftMessageCodec.decode(good)); // control
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> RaftMessageCodec.decode(withTrailing(good, 1)));
            assertTrue(ex.getMessage().contains("trailing"), ex.getMessage());
        }

        @Test
        void preVoteRejectsTrailingBytes() {
            // PreVote shares decodeRequestVote; a trailing byte on the PRE_VOTE type is rejected too.
            var req = new RequestVoteRequest(5L, NodeId.of(2), 10L, 4L, true);
            FrameCodec.Frame good = RaftMessageCodec.encode(req, GROUP_ID);
            assertDoesNotThrow(() -> RaftMessageCodec.decode(good)); // control
            assertThrows(IllegalArgumentException.class,
                    () -> RaftMessageCodec.decode(withTrailing(good, 2)));
        }

        @Test
        void requestVoteResponseRejectsTrailingBytes() {
            var resp = new RequestVoteResponse(5L, true, NodeId.of(3), false);
            FrameCodec.Frame good = RaftMessageCodec.encode(resp, GROUP_ID);
            assertDoesNotThrow(() -> RaftMessageCodec.decode(good)); // control
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> RaftMessageCodec.decode(withTrailing(good, 1)));
            assertTrue(ex.getMessage().contains("trailing"), ex.getMessage());
        }

        @Test
        void timeoutNowRejectsTrailingBytes() {
            var req = new TimeoutNowRequest(5L, NodeId.of(2));
            FrameCodec.Frame good = RaftMessageCodec.encode(req, GROUP_ID);
            assertDoesNotThrow(() -> RaftMessageCodec.decode(good)); // control
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> RaftMessageCodec.decode(withTrailing(good, 1)));
            assertTrue(ex.getMessage().contains("trailing"), ex.getMessage());
        }

        @Test
        void installSnapshotResponseRejectsTrailingBytesAfterOptionalOffset() {
            // The strict-end fires AFTER the optional nextExpectedOffset: a full 17-byte response
            // decodes (control), but a byte PAST the present optional field is rejected.
            var resp = new InstallSnapshotResponse(8L, true, NodeId.of(3), 42L, 7);
            FrameCodec.Frame good = RaftMessageCodec.encode(resp, GROUP_ID);
            var ok = (InstallSnapshotResponse) RaftMessageCodec.decode(good); // control: offset preserved
            assertEquals(7, ok.nextExpectedOffset());
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> RaftMessageCodec.decode(withTrailing(good, 1)));
            assertTrue(ex.getMessage().contains("trailing"), ex.getMessage());
        }

        @Test
        void witnessRejectsTrailingBytes() {
            // WH-06 (Gate-7 round-2): the witness body is exactly WITNESS_BODY_LEN; a trailing byte is
            // rejected, matching every other fixed-shape Raft decoder. decodeWitness needs the
            // authenticated sender, so it is exercised directly rather than via decode().
            var from = NodeId.of(2);
            var msg = new io.configd.raft.WitnessMessage(from, 11L, 9L, 3, 4L, true);
            FrameCodec.Frame good = RaftMessageCodec.encode(msg, GROUP_ID);
            assertDoesNotThrow(() -> RaftMessageCodec.decodeWitness(good, from)); // control: exact body
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> RaftMessageCodec.decodeWitness(withTrailing(good, 1), from));
            assertTrue(ex.getMessage().contains("trailing"), ex.getMessage());
        }
    }
}
