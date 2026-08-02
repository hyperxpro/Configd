package io.configd.server;

import io.configd.common.NodeId;
import io.configd.raft.AppendEntriesRequest;
import io.configd.raft.LogEntry;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoalescedHeartbeatCodecTest {

    private static final NodeId LEADER = NodeId.of(7);

    private static AppendEntriesRequest heartbeat(long term, long prevIdx, long prevTerm, long commit) {
        return new AppendEntriesRequest(term, LEADER, prevIdx, prevTerm, List.of(), commit);
    }

    @Test
    void roundTripPreservesEveryGroupsHeartbeat() {
        Map<Integer, AppendEntriesRequest> in = new LinkedHashMap<>();
        in.put(5, heartbeat(10L, 100L, 9L, 99L));
        in.put(9, heartbeat(11L, 200L, 10L, 199L));
        in.put(2, heartbeat(12L, 300L, 11L, 299L));

        FrameCodec.Frame frame = RaftMessageCodec.encodeCoalescedHeartbeat(in);
        Map<Integer, AppendEntriesRequest> out = RaftMessageCodec.decodeCoalescedHeartbeat(frame);

        assertEquals(in.keySet(), out.keySet(), "all group ids must round-trip");
        for (Map.Entry<Integer, AppendEntriesRequest> e : in.entrySet()) {
            AppendEntriesRequest a = e.getValue();
            AppendEntriesRequest b = out.get(e.getKey());
            assertEquals(a.term(), b.term(), "term");
            assertEquals(a.leaderId(), b.leaderId(), "leaderId");
            assertEquals(a.prevLogIndex(), b.prevLogIndex(), "prevLogIndex");
            assertEquals(a.prevLogTerm(), b.prevLogTerm(), "prevLogTerm");
            assertEquals(a.leaderCommit(), b.leaderCommit(), "leaderCommit");
            assertTrue(b.entries().isEmpty(), "a decoded heartbeat carries no entries");
        }
    }

    @Test
    void frameHasCoalescedTypeAndSentinelHeader() {
        Map<Integer, AppendEntriesRequest> in = new LinkedHashMap<>();
        in.put(3, heartbeat(1L, 0L, 0L, 0L));
        FrameCodec.Frame frame = RaftMessageCodec.encodeCoalescedHeartbeat(in);
        assertEquals(MessageType.RAFT_COALESCED_HEARTBEAT, frame.messageType());
        assertEquals(0, frame.groupId(), "frame groupId is a sentinel — real ids are in the payload");
        assertEquals(0L, frame.term(), "frame term is a sentinel — real terms are in the payload");
    }

    @Test
    void groupOrderIsPreserved() {
        Map<Integer, AppendEntriesRequest> in = new LinkedHashMap<>();
        in.put(9, heartbeat(1L, 0L, 0L, 0L));
        in.put(1, heartbeat(2L, 0L, 0L, 0L));
        in.put(5, heartbeat(3L, 0L, 0L, 0L));
        Map<Integer, AppendEntriesRequest> out =
                RaftMessageCodec.decodeCoalescedHeartbeat(RaftMessageCodec.encodeCoalescedHeartbeat(in));
        assertEquals(new ArrayList<>(in.keySet()), new ArrayList<>(out.keySet()),
                "demux replay order must match send order (deterministic)");
    }

    @Test
    void encodeRejectsEmptyMap() {
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.encodeCoalescedHeartbeat(new LinkedHashMap<>()));
    }

    @Test
    void encodeRejectsTooManyGroups() {
        Map<Integer, AppendEntriesRequest> in = new LinkedHashMap<>();
        for (int i = 0; i <= RaftMessageCodec.MAX_COALESCED_GROUPS; i++) {
            in.put(i, heartbeat(1L, 0L, 0L, 0L));
        }
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.encodeCoalescedHeartbeat(in));
    }

    @Test
    void encodeRejectsNonEmptyAppendEntries() {
        Map<Integer, AppendEntriesRequest> in = new LinkedHashMap<>();
        AppendEntriesRequest notAHeartbeat = new AppendEntriesRequest(
                1L, LEADER, 0L, 0L, List.of(new LogEntry(1L, 1L, new byte[] {1})), 0L);
        in.put(4, notAHeartbeat);
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.encodeCoalescedHeartbeat(in),
                "only empty heartbeats may be coalesced — a non-empty AE must be rejected");
    }

    private static FrameCodec.Frame coalescedFrame(byte[] payload) {
        return new FrameCodec.Frame(MessageType.RAFT_COALESCED_HEARTBEAT, 0, 0L, payload);
    }

    @Test
    void decodeRejectsEmptyPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decodeCoalescedHeartbeat(coalescedFrame(new byte[0])));
    }

    @Test
    void decodeRejectsNegativeCount() {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putInt(-1);
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decodeCoalescedHeartbeat(coalescedFrame(buf.array())));
    }

    @Test
    void decodeRejectsCountAboveMax() {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putInt(RaftMessageCodec.MAX_COALESCED_GROUPS + 1);
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decodeCoalescedHeartbeat(coalescedFrame(buf.array())));
    }

    @Test
    void decodeRejectsHugeCountTinyBuffer() {
        // The tiny-frame / big-alloc guard: a 4-byte payload claims Integer.MAX_VALUE groups.
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putInt(Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decodeCoalescedHeartbeat(coalescedFrame(buf.array())));
    }

    @Test
    void decodeRejectsTruncatedRecord() {
        // count=2 but only one 40-byte record follows.
        Map<Integer, AppendEntriesRequest> one = new LinkedHashMap<>();
        one.put(1, heartbeat(1L, 0L, 0L, 0L));
        byte[] valid = RaftMessageCodec.encodeCoalescedHeartbeat(one).payload();
        byte[] tampered = valid.clone();
        ByteBuffer.wrap(tampered, 0, 4).putInt(2); // claim 2 groups, supply 1
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decodeCoalescedHeartbeat(coalescedFrame(tampered)));
    }

    @Test
    void decodeRejectsDuplicateGroupId() {
        int rec = 4 + 8 + 4 + 8 + 8 + 8;
        ByteBuffer buf = ByteBuffer.allocate(4 + 2 * rec);
        buf.putInt(2);
        for (int i = 0; i < 2; i++) {
            buf.putInt(7);       // same groupId both times
            buf.putLong(1L);
            buf.putInt(LEADER.id());
            buf.putLong(0L);
            buf.putLong(0L);
            buf.putLong(0L);
        }
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decodeCoalescedHeartbeat(coalescedFrame(buf.array())));
    }

    @Test
    void decodeRejectsTrailingBytes() {
        Map<Integer, AppendEntriesRequest> one = new LinkedHashMap<>();
        one.put(1, heartbeat(1L, 0L, 0L, 0L));
        byte[] valid = RaftMessageCodec.encodeCoalescedHeartbeat(one).payload();
        byte[] padded = new byte[valid.length + 3];
        System.arraycopy(valid, 0, padded, 0, valid.length);
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decodeCoalescedHeartbeat(coalescedFrame(padded)),
                "a well-formed coalesced heartbeat has no padding after its records");
    }
}
