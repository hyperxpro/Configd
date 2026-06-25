package io.configd.netty;

import io.configd.common.NodeId;
import io.configd.transport.FrameCodec;
import io.configd.transport.InboundMessage;
import io.configd.transport.MessageType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * De-risking smoke test for {@link NettyRaftTransport} (plaintext) — confirms the core round-trip /
 * bidirectional / concurrency / unknown-peer / shutdown behaviour before the full 3-transport
 * {@code AbstractRaftTransportContract} folds these in. Mirrors the plaintext legs of
 * {@code TcpRaftTransportTest}.
 */
@Timeout(20)
class NettyRaftTransportSmokeTest {

    private final List<NettyRaftTransport> transports = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (NettyRaftTransport t : transports) {
            t.close();
        }
        transports.clear();
    }

    private NettyRaftTransport create(NodeId self, InetSocketAddress bind,
                                      Map<NodeId, InetSocketAddress> peers,
                                      Consumer<InboundMessage> handler) {
        NettyRaftTransport t = new NettyRaftTransport(self, bind, peers, null, handler);
        transports.add(t);
        return t;
    }

    @Test
    void roundTripPreservesAllFrameFields() throws Exception {
        NodeId a = NodeId.of(1);
        NodeId b = NodeId.of(2);
        CountDownLatch received = new CountDownLatch(1);
        var msgs = new CopyOnWriteArrayList<InboundMessage>();

        NettyRaftTransport tb = create(b, new InetSocketAddress("127.0.0.1", 0), Map.of(), msg -> {
            msgs.add(msg);
            received.countDown();
        });
        tb.start();
        int portB = tb.localPort();

        NettyRaftTransport ta = create(a, new InetSocketAddress("127.0.0.1", 0),
                Map.of(b, new InetSocketAddress("127.0.0.1", portB)), msg -> {});
        ta.start();

        ta.send(b, new FrameCodec.Frame(MessageType.HEARTBEAT, 1, 5L, "hello".getBytes()));

        assertTrue(received.await(5, TimeUnit.SECONDS), "message should arrive");
        InboundMessage m = msgs.getFirst();
        assertEquals(a, m.from());
        assertEquals(MessageType.HEARTBEAT, m.frame().messageType());
        assertEquals(1, m.frame().groupId());
        assertEquals(5L, m.frame().term());
        assertArrayEquals("hello".getBytes(), m.frame().payload());
    }

    @Test
    void emptyPayloadRoundtrip() throws Exception {
        NodeId a = NodeId.of(1);
        NodeId b = NodeId.of(2);
        CountDownLatch received = new CountDownLatch(1);
        var msgs = new CopyOnWriteArrayList<InboundMessage>();
        NettyRaftTransport tb = create(b, new InetSocketAddress("127.0.0.1", 0), Map.of(), msg -> {
            msgs.add(msg);
            received.countDown();
        });
        tb.start();
        NettyRaftTransport ta = create(a, new InetSocketAddress("127.0.0.1", 0),
                Map.of(b, new InetSocketAddress("127.0.0.1", tb.localPort())), msg -> {});
        ta.start();
        ta.send(b, new FrameCodec.Frame(MessageType.HEARTBEAT, 0, 0L, new byte[0]));
        assertTrue(received.await(5, TimeUnit.SECONDS));
        assertEquals(0, msgs.getFirst().frame().payload().length);
    }

    @Test
    void concurrentSendsFromMultipleThreads() throws Exception {
        NodeId a = NodeId.of(1);
        NodeId b = NodeId.of(2);
        int n = 50;
        CountDownLatch all = new CountDownLatch(n);
        var msgs = Collections.synchronizedList(new ArrayList<InboundMessage>());
        NettyRaftTransport tb = create(b, new InetSocketAddress("127.0.0.1", 0), Map.of(), msg -> {
            msgs.add(msg);
            all.countDown();
        });
        tb.start();
        NettyRaftTransport ta = create(a, new InetSocketAddress("127.0.0.1", 0),
                Map.of(b, new InetSocketAddress("127.0.0.1", tb.localPort())), msg -> {});
        ta.start();

        CountDownLatch gun = new CountDownLatch(1);
        Thread[] senders = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            senders[i] = Thread.ofVirtual().start(() -> {
                try {
                    gun.await();
                    ta.send(b, new FrameCodec.Frame(MessageType.APPEND_ENTRIES, 1, idx,
                            ("msg-" + idx).getBytes()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        gun.countDown();
        for (Thread t : senders) {
            t.join(5000);
        }
        assertTrue(all.await(10, TimeUnit.SECONDS),
                "all " + n + " messages should arrive, got " + msgs.size());
        assertEquals(n, msgs.size());
    }

    @Test
    void sendToUnknownPeerThrows() throws Exception {
        NettyRaftTransport ta = create(NodeId.of(1), new InetSocketAddress("127.0.0.1", 0),
                Map.of(), msg -> {});
        ta.start();
        assertThrows(IllegalArgumentException.class,
                () -> ta.send(NodeId.of(99), new FrameCodec.Frame(MessageType.HEARTBEAT, 0, 0L, new byte[0])));
    }

    @Test
    void sendAfterCloseSilentlyDrops() throws Exception {
        NettyRaftTransport ta = create(NodeId.of(1), new InetSocketAddress("127.0.0.1", 0),
                Map.of(), msg -> {});
        ta.start();
        assertTrue(ta.localPort() > 0);
        ta.close();
        transports.remove(ta);
        assertDoesNotThrow(() -> ta.send(NodeId.of(99),
                new FrameCodec.Frame(MessageType.HEARTBEAT, 0, 0L, new byte[0])));
    }

    @Test
    void registerHandlerReceivesMessages() throws Exception {
        NodeId a = NodeId.of(1);
        NodeId b = NodeId.of(2);
        CountDownLatch received = new CountDownLatch(1);
        var handlerMsgs = new CopyOnWriteArrayList<Object>();
        NettyRaftTransport tb = create(b, new InetSocketAddress("127.0.0.1", 0), Map.of(), null);
        tb.registerHandler((from, message) -> {
            handlerMsgs.add(message);
            received.countDown();
        });
        tb.start();
        NettyRaftTransport ta = create(a, new InetSocketAddress("127.0.0.1", 0),
                Map.of(b, new InetSocketAddress("127.0.0.1", tb.localPort())), null);
        ta.start();
        ta.send(b, new FrameCodec.Frame(MessageType.REQUEST_VOTE, 3, 42L, "vote".getBytes()));
        assertTrue(received.await(5, TimeUnit.SECONDS));
        assertEquals(1, handlerMsgs.size());
        FrameCodec.Frame f = (FrameCodec.Frame) handlerMsgs.getFirst();
        assertEquals(MessageType.REQUEST_VOTE, f.messageType());
        assertEquals(42L, f.term());
    }
}
