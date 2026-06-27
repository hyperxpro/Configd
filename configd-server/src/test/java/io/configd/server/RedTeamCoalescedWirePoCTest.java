package io.configd.server;

import io.configd.common.NodeId;
import io.configd.raft.AppendEntriesRequest;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-TEAM (Seam F audit) — independent adversarial PoC battery for the v2 wire bump + coalesced
 * heartbeat codec. Complements the author's {@code CoalescedHeartbeatCodecTest} /
 * {@code RaftTransportAdapterCoalescedInboundTest} / {@code FrameCodecEpochReservationTest} by closing
 * the gaps they did not cover:
 * <ul>
 *   <li>full <b>end-to-end wire</b> round-trip of the NEW coalesced type through {@link FrameCodec}
 *       (encode-to-bytes → CRC/epoch → decode), proving the 8-byte reserved epoch does not shift the
 *       coalesced payload boundary (hunting target #4 — desync);</li>
 *   <li><b>type confusion both directions</b> (#5): a coalesced frame must fail-closed in the generic
 *       {@link RaftMessageCodec#decode}, and a non-coalesced payload must fail-closed in
 *       {@link RaftMessageCodec#decodeCoalescedHeartbeat};</li>
 *   <li>the <b>{@code MAX_COALESCED_GROUPS} boundary</b> (#1/#2): exactly-1024 accepted; one byte short
 *       rejected with {@link IllegalArgumentException}, never {@link java.nio.BufferUnderflowException};</li>
 *   <li><b>sign safety</b> (#3): negative groupId / leaderId decode without crashing;</li>
 *   <li>the <b>int-overflow count</b> (#1): a count whose {@code count*40} overflows 32-bit is rejected.</li>
 * </ul>
 * Result: every attack is DEFENDED (each PoC asserts a clean reject or a faithful round-trip).
 */
class RedTeamCoalescedWirePoCTest {

    private static final NodeId LEADER = NodeId.of(7);
    /** groupId(4)+term(8)+leaderId(4)+prevLogIndex(8)+prevLogTerm(8)+leaderCommit(8). */
    private static final int RECORD = 4 + 8 + 4 + 8 + 8 + 8; // 40

    private static AppendEntriesRequest hb(long term, long prevIdx, long prevTerm, long commit) {
        return new AppendEntriesRequest(term, LEADER, prevIdx, prevTerm, List.of(), commit);
    }

    private static FrameCodec.Frame coalescedFrame(byte[] payload) {
        return new FrameCodec.Frame(MessageType.RAFT_COALESCED_HEARTBEAT, 0, 0L, payload);
    }

    // ---- #4: end-to-end wire round-trip of the coalesced type (the epoch must not desync it) ----

    @Test
    void coalescedSurvivesFullFrameCodecWireRoundTrip() {
        Map<Integer, AppendEntriesRequest> in = new LinkedHashMap<>();
        in.put(5, hb(10L, 100L, 9L, 99L));
        in.put(9, hb(11L, 200L, 10L, 199L));
        in.put(2, hb(12L, 300L, 11L, 299L));

        FrameCodec.Frame logical = RaftMessageCodec.encodeCoalescedHeartbeat(in);

        // Go through the REAL wire path: Frame -> bytes (len/ver/type/gid/term/EPOCH/payload/crc) -> Frame.
        byte[] wire = FrameCodec.encode(
                logical.messageType(), logical.groupId(), logical.term(), logical.payload());
        FrameCodec.Frame onWire = FrameCodec.decode(wire);

        assertEquals(MessageType.RAFT_COALESCED_HEARTBEAT, onWire.messageType());
        assertArrayEquals(logical.payload(), onWire.payload(),
                "the 8-byte reserved epoch must not shift the coalesced payload boundary on the wire");

        Map<Integer, AppendEntriesRequest> out = RaftMessageCodec.decodeCoalescedHeartbeat(onWire);
        assertEquals(in.keySet(), out.keySet());
        for (Integer g : in.keySet()) {
            assertEquals(in.get(g).term(), out.get(g).term());
            assertEquals(in.get(g).prevLogIndex(), out.get(g).prevLogIndex());
            assertEquals(in.get(g).leaderCommit(), out.get(g).leaderCommit());
            assertTrue(out.get(g).entries().isEmpty());
        }
    }

    // ---- #5: type confusion, both directions, must fail closed ----

    @Test
    void genericDecodeRejectsCoalescedFrame() {
        FrameCodec.Frame coalesced =
                RaftMessageCodec.encodeCoalescedHeartbeat(Map.of(3, hb(1L, 0L, 0L, 0L)));
        // A coalesced frame must NOT be silently misparsed as some RaftMessage via the generic decode().
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decode(coalesced));
        assertTrue(ex.getMessage().contains("decodeCoalescedHeartbeat"),
                "the directional error must point at the correct decoder, not a generic 'unknown type'");
    }

    @Test
    void coalescedDecodeFailsClosedOnNonCoalescedPayload() {
        // Reverse misroute: hand a REQUEST_VOTE frame's bytes to the coalesced decoder. It must reject,
        // not return a bogus map or throw an uncaught BufferUnderflowException.
        FrameCodec.Frame requestVote = RaftMessageCodec.encode(
                new io.configd.raft.RequestVoteRequest(5L, LEADER, 1L, 1L, false), 0);
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decodeCoalescedHeartbeat(requestVote));
    }

    // ---- #1/#2: MAX_COALESCED_GROUPS boundary — exactly-max accepted, one byte short = clean IAE ----

    @Test
    void exactlyMaxGroupsAccepted_oneByteShortRejectedCleanly() {
        Map<Integer, AppendEntriesRequest> max = new LinkedHashMap<>();
        for (int i = 0; i < RaftMessageCodec.MAX_COALESCED_GROUPS; i++) {
            max.put(i, hb(1L, i, 0L, 0L));
        }
        FrameCodec.Frame frame = RaftMessageCodec.encodeCoalescedHeartbeat(max);
        Map<Integer, AppendEntriesRequest> out =
                assertDoesNotThrow(() -> RaftMessageCodec.decodeCoalescedHeartbeat(frame));
        assertEquals(RaftMessageCodec.MAX_COALESCED_GROUPS, out.size());

        // Drop the final byte: the declared count no longer fits -> the pre-check must reject with a
        // clean IllegalArgumentException, NOT an uncaught BufferUnderflowException.
        byte[] truncated = new byte[frame.payload().length - 1];
        System.arraycopy(frame.payload(), 0, truncated, 0, truncated.length);
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decodeCoalescedHeartbeat(coalescedFrame(truncated)));
    }

    // ---- #3: sign safety — negative groupId / leaderId decode without crashing ----

    @Test
    void negativeGroupIdAndLeaderIdDecodeWithoutCrash() {
        ByteBuffer buf = ByteBuffer.allocate(4 + RECORD);
        buf.putInt(1);                       // count
        buf.putInt(-1);                      // groupId (negative — unregistered downstream, dropped there)
        buf.putLong(0L);                     // term
        buf.putInt(Integer.MIN_VALUE);       // leaderId (extreme negative)
        buf.putLong(-5L);                    // prevLogIndex (negative; record does no validation)
        buf.putLong(0L);                     // prevLogTerm
        buf.putLong(0L);                     // leaderCommit
        Map<Integer, AppendEntriesRequest> out =
                assertDoesNotThrow(() -> RaftMessageCodec.decodeCoalescedHeartbeat(coalescedFrame(buf.array())));
        assertEquals(1, out.size());
        assertTrue(out.containsKey(-1), "a negative groupId is a valid wire value; the drop is at dispatch");
        assertEquals(Integer.MIN_VALUE, out.get(-1).leaderId().id());
    }

    // ---- #1: integer-overflow count (count*40 wraps 32-bit) must be rejected ----

    @Test
    void overflowingCountRejected() {
        // 0x40000000 * 40 overflows a 32-bit int (to a small/negative value); the long-cast pre-check
        // AND the prior MAX_COALESCED_GROUPS gate must both refuse it.
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putInt(0x40000000); // 1,073,741,824 groups
        buf.putInt(0);          // a few stray bytes so remaining() > 0
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decodeCoalescedHeartbeat(coalescedFrame(buf.array())));
    }
}
