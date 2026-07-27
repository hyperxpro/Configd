package io.configd.server;

import io.configd.common.NodeId;
import io.configd.raft.RaftMessage;
import io.configd.raft.RequestVoteRequest;
import io.configd.transport.TcpRaftTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives ONE bounded loopback round-trip over real localhost sockets, exercising the real network seam
 * end-to-end (encode -&gt; TCP -&gt; decode -&gt; marshal -&gt; handle): node A's adapter
 * {@link RaftTransportAdapter#send} serializes a {@link RaftMessage} to a frame and writes it on the wire;
 * node B's {@link RaftTransportAdapter#registerInboundHandler} reads the frame, decodes it back to a
 * {@code RaftMessage}, and dispatches it to the consumer. Asserts the message arrives byte-faithful,
 * proving both directions of the adapter codec and its TCP delegation.
 */
@Timeout(15)
final class RaftTransportAdapterLoopbackTest {

    private TcpRaftTransport transportA;
    private TcpRaftTransport transportB;

    @AfterEach
    void tearDown() {
        if (transportA != null) transportA.close();
        if (transportB != null) transportB.close();
    }

    @Test
    void messageTraversesEncodeTcpDecodeMarshalEndToEnd() throws Exception {
        final int GROUP = 7;
        NodeId nodeA = NodeId.of(1);
        NodeId nodeB = NodeId.of(2);

        // Node B: bind first so we learn its port, with an adapter whose inbound
        // handler captures the decoded RaftMessage (the "marshal -> handle" end).
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<NodeId> fromRef = new AtomicReference<>();
        AtomicReference<RaftMessage> msgRef = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger groupRef =
                new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);

        transportB = new TcpRaftTransport(nodeB, new InetSocketAddress("127.0.0.1", 0),
                Map.of(), null, /* ctor inbound handler unused; adapter registers its own */ m -> {});
        RaftTransportAdapter adapterB = new RaftTransportAdapter(transportB, GROUP);
        // The handler receives the frame's groupId; capture it to prove the groupId survives the
        // encode -> TCP -> decode round-trip and is delivered (the basis for the N-group inbound demux).
        adapterB.registerInboundHandler((from, groupId, message) -> {
            fromRef.set(from);
            groupRef.set(groupId);
            msgRef.set(message);
            received.countDown();
        });
        transportB.start();
        int portB = transportB.localPort();

        transportA = new TcpRaftTransport(nodeA, new InetSocketAddress("127.0.0.1", 0),
                Map.of(nodeB, new InetSocketAddress("127.0.0.1", portB)), null, m -> {});
        RaftTransportAdapter adapterA = new RaftTransportAdapter(transportA, GROUP);
        transportA.start();

        // A RequestVote with distinctive fields so we can confirm a faithful decode.
        RequestVoteRequest sent = new RequestVoteRequest(
                /* term */ 9L, /* candidateId */ nodeA,
                /* lastLogIndex */ 42L, /* lastLogTerm */ 5L, /* preVote */ false);

        adapterA.send(nodeB, sent);

        assertTrue(received.await(10, TimeUnit.SECONDS),
                "the message must traverse encode -> TCP -> decode -> handler within the bound");

        assertEquals(nodeA, fromRef.get(), "the decoded message must carry the sender's NodeId");
        assertEquals(GROUP, groupRef.get(),
                "the frame's groupId must survive the round-trip and be delivered to the handler (DL-P1-06)");
        RaftMessage got = msgRef.get();
        RequestVoteRequest decoded = assertInstanceOf(RequestVoteRequest.class, got,
                "the decoded message must be a RequestVoteRequest");
        assertEquals(sent.term(), decoded.term(), "term must survive the round-trip");
        assertEquals(sent.candidateId(), decoded.candidateId(), "candidateId must survive the round-trip");
        assertEquals(sent.lastLogIndex(), decoded.lastLogIndex(), "lastLogIndex must survive the round-trip");
        assertEquals(sent.lastLogTerm(), decoded.lastLogTerm(), "lastLogTerm must survive the round-trip");
        assertEquals(sent.preVote(), decoded.preVote(), "preVote flag must survive the round-trip");
    }

    /**
     * A witness frame traverses the SAME encode -&gt; TCP -&gt; decode -&gt; handler path, and the
     * decoded {@code sender} is the transport-authenticated {@code from} (node A), NOT the id the sender
     * put in the record (here a forged 999). This is the on-wire proof of the spoof-resistance the
     * codec test asserts in isolation: witness identity comes from the authenticated 4-byte prefix.
     */
    @Test
    void witnessFrameInjectsAuthenticatedFromOverTheWire() throws Exception {
        final int GROUP = 3;
        NodeId nodeA = NodeId.of(11);
        NodeId nodeB = NodeId.of(22);

        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<NodeId> fromRef = new AtomicReference<>();
        AtomicReference<RaftMessage> msgRef = new AtomicReference<>();

        transportB = new TcpRaftTransport(nodeB, new InetSocketAddress("127.0.0.1", 0),
                Map.of(), null, m -> {});
        RaftTransportAdapter adapterB = new RaftTransportAdapter(transportB, GROUP);
        adapterB.registerInboundHandler((from, groupId, message) -> {
            fromRef.set(from);
            msgRef.set(message);
            received.countDown();
        });
        transportB.start();
        int portB = transportB.localPort();

        transportA = new TcpRaftTransport(nodeA, new InetSocketAddress("127.0.0.1", 0),
                Map.of(nodeB, new InetSocketAddress("127.0.0.1", portB)), null, m -> {});
        RaftTransportAdapter adapterA = new RaftTransportAdapter(transportA, GROUP);
        transportA.start();

        // The record claims sender=999 (a spoof); the wire body carries no sender, so B must attribute it
        // to the authenticated origin nodeA.
        adapterA.send(nodeB, new io.configd.raft.WitnessMessage(
                NodeId.of(999), /* selfAnchorSeq */ 77L, /* selfTerm */ 4L,
                /* selfVotedFor */ 11, /* seenOfYouSeq */ 12L, /* query */ true));

        assertTrue(received.await(10, TimeUnit.SECONDS), "witness frame must traverse the adapter path");
        assertEquals(nodeA, fromRef.get(), "sender is the transport-authenticated `from`, never the body 999");
        io.configd.raft.WitnessMessage decoded = assertInstanceOf(io.configd.raft.WitnessMessage.class,
                msgRef.get());
        assertEquals(nodeA, decoded.sender());
        assertEquals(77L, decoded.selfAnchorSeq());
        assertEquals(12L, decoded.seenOfYouSeq());
        assertTrue(decoded.query());
    }
}
