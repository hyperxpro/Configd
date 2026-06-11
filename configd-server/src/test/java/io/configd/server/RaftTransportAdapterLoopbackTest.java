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
 * RR-087 — {@code RaftTransportAdapter} was 0/16 lines: the real network seam
 * (encode -&gt; TCP -&gt; decode -&gt; marshal -&gt; handle) was never traversed
 * end-to-end by any test. This drives ONE bounded loopback round-trip over real
 * localhost sockets: node A's adapter {@link RaftTransportAdapter#send} serializes
 * a {@link RaftMessage} to a frame and writes it on the wire; node B's
 * {@link RaftTransportAdapter#registerInboundHandler} reads the frame, decodes it
 * back to a {@code RaftMessage}, and dispatches it to the consumer. We assert the
 * message arrives byte-faithful, proving both directions of the adapter codec and
 * its TCP delegation.
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

        transportB = new TcpRaftTransport(nodeB, new InetSocketAddress("127.0.0.1", 0),
                Map.of(), null, /* ctor inbound handler unused; adapter registers its own */ m -> {});
        RaftTransportAdapter adapterB = new RaftTransportAdapter(transportB, GROUP);
        adapterB.registerInboundHandler((from, message) -> {
            fromRef.set(from);
            msgRef.set(message);
            received.countDown();
        });
        transportB.start();
        int portB = transportB.localPort();

        // Node A: knows B's address; its adapter encodes + sends over TCP.
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
        RaftMessage got = msgRef.get();
        RequestVoteRequest decoded = assertInstanceOf(RequestVoteRequest.class, got,
                "the decoded message must be a RequestVoteRequest");
        assertEquals(sent.term(), decoded.term(), "term must survive the round-trip");
        assertEquals(sent.candidateId(), decoded.candidateId(), "candidateId must survive the round-trip");
        assertEquals(sent.lastLogIndex(), decoded.lastLogIndex(), "lastLogIndex must survive the round-trip");
        assertEquals(sent.lastLogTerm(), decoded.lastLogTerm(), "lastLogTerm must survive the round-trip");
        assertEquals(sent.preVote(), decoded.preVote(), "preVote flag must survive the round-trip");
    }
}
