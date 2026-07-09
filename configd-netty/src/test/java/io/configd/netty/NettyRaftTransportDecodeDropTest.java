package io.configd.netty;

import io.configd.common.NodeId;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import io.configd.transport.PeerIdentityPolicy;
import io.configd.transport.RaftTransportMetrics;
import io.configd.transport.RaftWireProtocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Netty twin of {@code TcpRaftTransportDecodeDropTest}: the Netty consensus transport must count a
 * connection dropped at the frame-decode boundary. A corrupt frame trips {@code CorruptedFrameException}
 * in {@code RaftFrameDecoder}; the inbound handler's {@code exceptionCaught} previously did a bare
 * {@code ctx.close()} with no signal, so this proves the drop now increments
 * {@link RaftTransportMetrics#onInboundConnectionDropped()}. Plaintext (no mTLS fixture needed).
 */
@Timeout(30)
class NettyRaftTransportDecodeDropTest {

    private NettyRaftTransport server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void corruptFrameDropsConnectionAndIncrementsDecodeDropCounter() throws Exception {
        AtomicLong inbound = new AtomicLong();
        AtomicLong drops = new AtomicLong();
        RaftTransportMetrics sink = new RaftTransportMetrics() {
            @Override public void onInboundConnectionDropped() { drops.incrementAndGet(); }
        };
        server = new NettyRaftTransport(NodeId.of(1), new InetSocketAddress("127.0.0.1", 0), Map.of(),
                null, msg -> inbound.incrementAndGet(), PeerIdentityPolicy.unenforced(), sink);
        server.start();

        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", server.localPort()), 2_000);
            OutputStream out = s.getOutputStream();
            out.write(corruptWire(NodeId.of(2)));
            out.flush();
            awaitAtLeast(drops, 1, 5_000);
        }

        assertEquals(0, inbound.get(), "a frame that fails to decode must never be dispatched as a message");
        assertTrue(drops.get() >= 1, "a corrupt frame must increment the connection-decode-drop counter");
    }

    @Test
    void outboundQueueOverflowToDownPeerIncrementsFramesDropped() throws Exception {
        // A free-then-closed port: nothing listens, so the connect is refused and the peer's bounded
        // outbound queue never drains. Overfilling it makes the real drop-oldest site (enqueueOrDrop) fire.
        int deadPort;
        try (ServerSocket free = new ServerSocket(0)) {
            deadPort = free.getLocalPort();
        }
        NodeId peer = NodeId.of(2);
        server = new NettyRaftTransport(NodeId.of(1), new InetSocketAddress("127.0.0.1", 0),
                Map.of(peer, new InetSocketAddress("127.0.0.1", deadPort)), null, msg -> { });
        server.start();

        int sends = RaftWireProtocol.OUTBOUND_QUEUE_CAPACITY + 64; // overfill the 1024-deep bounded queue
        for (int i = 0; i < sends; i++) {
            server.send(peer, new FrameCodec.Frame(MessageType.HEARTBEAT, 1, i, new byte[8]));
        }

        assertTrue(server.framesDropped() >= 1,
                "overflowing the bounded outbound queue to a down peer must increment framesDropped");
    }

    /** A wire buffer {@code [4B senderId][frame]} whose CRC trailer is flipped: the length prefix stays
     *  valid so the decoder consumes the whole frame, then the CRC check fails (CorruptedFrameException). */
    private static byte[] corruptWire(NodeId from) {
        byte[] encoded = FrameCodec.encode(MessageType.HEARTBEAT, 1, 1L, "corrupt".getBytes());
        encoded[encoded.length - 1] ^= 0xFF;
        byte[] wire = new byte[4 + encoded.length];
        int id = from.id();
        wire[0] = (byte) (id >>> 24);
        wire[1] = (byte) (id >>> 16);
        wire[2] = (byte) (id >>> 8);
        wire[3] = (byte) id;
        System.arraycopy(encoded, 0, wire, 4, encoded.length);
        return wire;
    }

    private static void awaitAtLeast(AtomicLong counter, long expected, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline && counter.get() < expected) {
            Thread.sleep(25);
        }
    }
}
