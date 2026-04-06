package io.configd.server;

import io.configd.common.NodeId;
import io.configd.raft.*;
import io.configd.transport.FrameCodec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
    }

    @Test
    void installSnapshotResponseRoundTrip() {
        var resp = new InstallSnapshotResponse(8L, true, NodeId.of(3), 42L);
        FrameCodec.Frame frame = RaftMessageCodec.encode(resp, GROUP_ID);
        var result = (InstallSnapshotResponse) RaftMessageCodec.decode(frame);

        assertEquals(8L, result.term());
        assertTrue(result.success());
        assertEquals(NodeId.of(3), result.from());
        assertEquals(42L, result.lastIncludedIndex());
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
}
