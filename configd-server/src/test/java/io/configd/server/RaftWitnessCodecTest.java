package io.configd.server;

import io.configd.common.NodeId;
import io.configd.raft.RaftMessage;
import io.configd.raft.WitnessMessage;
import io.configd.raft.WitnessReply;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codec proof for the anchor-witness wire types ({@code RAFT_WITNESS} / {@code RAFT_WITNESS_REPLY}).
 * Pins the frozen 29-byte body layout (golden), the round-trip, the fail-closed decode paths, and the
 * security-relevant property that the sender identity is the transport-authenticated {@code from}, never
 * a spoofable body field.
 */
final class RaftWitnessCodecTest {

    private static final int GID = 4;
    private static final NodeId SENDER = NodeId.of(2);

    @Test
    void witnessBodyIsFrozen29ByteGolden() {
        // selfAnchorSeq=7, selfTerm=3, selfVotedFor=5, seenOfYouSeq=4, query=true.
        WitnessMessage m = new WitnessMessage(SENDER, 7L, 3L, 5, 4L, true);
        FrameCodec.Frame frame = RaftMessageCodec.encode(m, GID);
        assertEquals(MessageType.RAFT_WITNESS, frame.messageType());
        assertEquals(GID, frame.groupId());
        assertEquals(3L, frame.term(), "frame header term mirrors selfTerm");
        byte[] expected = {
                0, 0, 0, 0, 0, 0, 0, 7,   // selfAnchorSeq
                0, 0, 0, 0, 0, 0, 0, 3,   // selfTerm
                0, 0, 0, 5,               // selfVotedFor
                0, 0, 0, 0, 0, 0, 0, 4,   // seenOfYouSeq
                0x01                      // flags: QUERY
        };
        assertArrayEquals(expected, frame.payload(), "witness body must be the frozen 29-byte layout");
    }

    @Test
    void replyBodyClearsQueryFlag() {
        WitnessReply r = new WitnessReply(SENDER, 9L, 2L, -1, 8L);
        FrameCodec.Frame frame = RaftMessageCodec.encode(r, GID);
        assertEquals(MessageType.RAFT_WITNESS_REPLY, frame.messageType());
        assertEquals(29, frame.payload().length);
        assertEquals(0x00, frame.payload()[28], "a reply never carries the QUERY flag");
        // votedFor=-1 (null) round-trips as 0xFFFFFFFF in the 4-byte field.
        int votedFor = ByteBuffer.wrap(frame.payload(), 16, 4).getInt();
        assertEquals(-1, votedFor);
    }

    @Test
    void witnessRoundTripsWithFromInjectedAsSender() {
        WitnessMessage sent = new WitnessMessage(SENDER, 100L, 7L, 3, 55L, true);
        FrameCodec.Frame frame = RaftMessageCodec.encode(sent, GID);
        NodeId from = NodeId.of(42);
        WitnessMessage got = assertInstanceOf(WitnessMessage.class,
                RaftMessageCodec.decodeWitness(frame, from));
        assertEquals(from, got.sender(), "sender is the transport `from`, not the body");
        assertEquals(100L, got.selfAnchorSeq());
        assertEquals(7L, got.selfTerm());
        assertEquals(3, got.selfVotedFor());
        assertEquals(55L, got.seenOfYouSeq());
        assertTrue(got.query());
    }

    @Test
    void replyRoundTrips() {
        WitnessReply sent = new WitnessReply(SENDER, 200L, 9L, -1, 150L);
        FrameCodec.Frame frame = RaftMessageCodec.encode(sent, GID);
        NodeId from = NodeId.of(7);
        WitnessReply got = assertInstanceOf(WitnessReply.class,
                RaftMessageCodec.decodeWitness(frame, from));
        assertEquals(from, got.sender());
        assertEquals(200L, got.selfAnchorSeq());
        assertEquals(9L, got.selfTerm());
        assertEquals(-1, got.selfVotedFor());
        assertEquals(150L, got.seenOfYouSeq());
    }

    @Test
    void senderIsFromNotBody_spoofResistant() {
        // A hostile-but-authenticated peer forges a body claiming to be node 999. The wire carries no
        // in-body sender, so after decode the attributed sender is the transport-authenticated `from`.
        WitnessMessage forged = new WitnessMessage(NodeId.of(999), 5L, 1L, 999, 5L, false);
        FrameCodec.Frame frame = RaftMessageCodec.encode(forged, GID);
        NodeId authenticatedFrom = NodeId.of(2);
        WitnessMessage got = (WitnessMessage) RaftMessageCodec.decodeWitness(frame, authenticatedFrom);
        assertEquals(authenticatedFrom, got.sender(),
                "the witness tables are keyed on the authenticated origin, never a body-claimed id");
    }

    @Test
    void genericDecodeRejectsWitnessFramesDirectionally() {
        FrameCodec.Frame frame = RaftMessageCodec.encode(
                new WitnessMessage(SENDER, 1L, 1L, -1, 0L, false), GID);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decode(frame));
        assertTrue(e.getMessage().contains("decodeWitness"), "decode() must point at decodeWitness()");
    }

    @Test
    void decodeWitnessRejectsWrongType() {
        FrameCodec.Frame notWitness = RaftMessageCodec.encode(
                new io.configd.raft.TimeoutNowRequest(1L, SENDER), GID);
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decodeWitness(notWitness, NodeId.of(1)));
    }

    @Test
    void decodeWitnessRejectsTruncatedBody() {
        FrameCodec.Frame truncated = new FrameCodec.Frame(
                MessageType.RAFT_WITNESS, GID, 0L, new byte[10]); // < 29
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decodeWitness(truncated, NodeId.of(1)));
    }

    @Test
    void decodeWitnessRejectsNullFrom() {
        FrameCodec.Frame frame = RaftMessageCodec.encode(
                new WitnessMessage(SENDER, 1L, 1L, -1, 0L, false), GID);
        assertThrows(NullPointerException.class, () -> RaftMessageCodec.decodeWitness(frame, null));
    }

    @Test
    void existingRaftGoldenPathsUnaffected() {
        // Sanity: a RequestVote still encodes/decodes through the generic path (witness additions did not
        // disturb the existing sealed-switch cases).
        RaftMessage rv = new io.configd.raft.RequestVoteRequest(3L, SENDER, 1L, 1L, false);
        FrameCodec.Frame frame = RaftMessageCodec.encode(rv, GID);
        assertEquals(MessageType.REQUEST_VOTE, frame.messageType());
        assertFalse(RaftMessageCodec.decode(frame) instanceof WitnessMessage);
    }
}
