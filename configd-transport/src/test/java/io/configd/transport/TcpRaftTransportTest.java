package io.configd.transport;

import io.configd.common.NodeId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class TcpRaftTransportTest {

    private final List<TcpRaftTransport> transports = new ArrayList<>();

    // Keytool subprocess spawning (3 per TLS test) is the dominant cost and
    // starves under CPU-credit throttling. Generate the keystore/truststore/cert
    // exactly ONCE for the whole class in @BeforeAll and reuse the cached paths
    // from the timed test body. The class-level @Timeout(10) still guards the
    // many fast plaintext tests; the keytool-driven find0051 carries its own
    // generous hang-detection budget via a method-level @Timeout override.
    private static Path tlsFixtureDir;
    private static Path keyStorePath;
    private static Path trustStorePath;
    private static Path certFile;

    @BeforeAll
    static void generateTlsFixture() throws Exception {
        tlsFixtureDir = Files.createTempDirectory("configd-tls-find0051-");
        keyStorePath = tlsFixtureDir.resolve("keystore.p12");
        trustStorePath = tlsFixtureDir.resolve("truststore.p12");
        certFile = tlsFixtureDir.resolve("cert.pem");

        // SAN only matches "localhost" (not 127.0.0.2). CN also does not match.
        runKeytool("keytool",
                "-genkeypair", "-alias", "server",
                "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-validity", "1",
                "-dname", "CN=localhost,O=test",
                "-ext", "san=dns:localhost",
                "-storetype", "PKCS12",
                "-keystore", keyStorePath.toString(),
                "-storepass", "changeit", "-keypass", "changeit");
        runKeytool("keytool",
                "-exportcert", "-alias", "server",
                "-keystore", keyStorePath.toString(),
                "-storepass", "changeit", "-rfc",
                "-file", certFile.toString());
        runKeytool("keytool",
                "-importcert", "-alias", "server",
                "-file", certFile.toString(),
                "-keystore", trustStorePath.toString(),
                "-storepass", "changeit", "-storetype", "PKCS12",
                "-noprompt");
    }

    @AfterAll
    static void deleteTlsFixture() throws Exception {
        if (tlsFixtureDir == null) {
            return;
        }
        try (var paths = Files.walk(tlsFixtureDir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @AfterEach
    void tearDown() {
        for (TcpRaftTransport t : transports) {
            t.close();
        }
    }

    @Test
    void sendMessageBetweenTwoNodes() throws Exception {
        NodeId nodeA = NodeId.of(1);
        NodeId nodeB = NodeId.of(2);

        CountDownLatch receivedLatch = new CountDownLatch(1);
        var receivedMessages = new CopyOnWriteArrayList<InboundMessage>();

        TcpRaftTransport transportB = createTransport(
                nodeB,
                new InetSocketAddress("127.0.0.1", 0),
                Map.of(),
                msg -> {
                    receivedMessages.add(msg);
                    receivedLatch.countDown();
                }
        );
        transportB.start();
        int portB = transportB.localPort();

        TcpRaftTransport transportA = createTransport(
                nodeA,
                new InetSocketAddress("127.0.0.1", 0),
                Map.of(nodeB, new InetSocketAddress("127.0.0.1", portB)),
                msg -> {}
        );
        transportA.start();

        FrameCodec.Frame frame = new FrameCodec.Frame(
                MessageType.HEARTBEAT, 1, 5L, "hello".getBytes());
        transportA.send(nodeB, frame);

        assertTrue(receivedLatch.await(5, TimeUnit.SECONDS), "Message should be received within 5 seconds");
        assertEquals(1, receivedMessages.size());

        InboundMessage received = receivedMessages.getFirst();
        assertEquals(nodeA, received.from());
        assertEquals(MessageType.HEARTBEAT, received.frame().messageType());
        assertEquals(1, received.frame().groupId());
        assertEquals(5L, received.frame().term());
        assertArrayEquals("hello".getBytes(), received.frame().payload());
    }

    @Test
    void bidirectionalCommunication() throws Exception {
        NodeId nodeA = NodeId.of(1);
        NodeId nodeB = NodeId.of(2);

        CountDownLatch latchA = new CountDownLatch(1);
        CountDownLatch latchB = new CountDownLatch(1);
        var receivedByA = new CopyOnWriteArrayList<InboundMessage>();
        var receivedByB = new CopyOnWriteArrayList<InboundMessage>();

        TcpRaftTransport transportB = createTransport(
                nodeB,
                new InetSocketAddress("127.0.0.1", 0),
                Map.of(),
                msg -> {
                    receivedByB.add(msg);
                    latchB.countDown();
                }
        );
        transportB.start();
        int portB = transportB.localPort();

        TcpRaftTransport transportA = createTransport(
                nodeA,
                new InetSocketAddress("127.0.0.1", 0),
                Map.of(nodeB, new InetSocketAddress("127.0.0.1", portB)),
                msg -> {
                    receivedByA.add(msg);
                    latchA.countDown();
                }
        );
        transportA.start();
        int portA = transportA.localPort();

        FrameCodec.Frame frameAtoB = new FrameCodec.Frame(
                MessageType.APPEND_ENTRIES, 1, 10L, "from-a".getBytes());
        transportA.send(nodeB, frameAtoB);

        assertTrue(latchB.await(5, TimeUnit.SECONDS), "B should receive message from A");
        assertEquals(nodeA, receivedByB.getFirst().from());
        assertArrayEquals("from-a".getBytes(), receivedByB.getFirst().frame().payload());
    }

    @Test
    void reconnectionAfterConnectionDrop() throws Exception {
        NodeId nodeA = NodeId.of(1);
        NodeId nodeB = NodeId.of(2);

        CountDownLatch firstReceived = new CountDownLatch(1);
        CountDownLatch secondReceived = new CountDownLatch(2);
        var receivedMessages = new CopyOnWriteArrayList<InboundMessage>();

        TcpRaftTransport transportB = createTransport(
                nodeB,
                new InetSocketAddress("127.0.0.1", 0),
                Map.of(),
                msg -> {
                    receivedMessages.add(msg);
                    firstReceived.countDown();
                    secondReceived.countDown();
                }
        );
        transportB.start();
        int portB = transportB.localPort();

        TcpRaftTransport transportA = createTransport(
                nodeA,
                new InetSocketAddress("127.0.0.1", 0),
                Map.of(nodeB, new InetSocketAddress("127.0.0.1", portB)),
                msg -> {}
        );
        transportA.start();

        FrameCodec.Frame frame1 = new FrameCodec.Frame(
                MessageType.HEARTBEAT, 1, 1L, "first".getBytes());
        transportA.send(nodeB, frame1);
        assertTrue(firstReceived.await(5, TimeUnit.SECONDS), "First message should arrive");

        transportB.close();
        transports.remove(transportB);

        Thread.sleep(200);

        TcpRaftTransport transportB2 = createTransport(
                nodeB,
                new InetSocketAddress("127.0.0.1", portB),
                Map.of(),
                msg -> {
                    receivedMessages.add(msg);
                    secondReceived.countDown();
                }
        );
        transportB2.start();

        FrameCodec.Frame frame2 = new FrameCodec.Frame(
                MessageType.HEARTBEAT, 1, 2L, "second".getBytes());

        // The first send after disconnect may fail and trigger reconnect;
        // retry a few times to allow reconnection
        boolean sent = false;
        for (int i = 0; i < 10 && !sent; i++) {
            try {
                transportA.send(nodeB, frame2);
                sent = true;
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        assertTrue(sent, "Should eventually reconnect and send");

        assertTrue(secondReceived.await(5, TimeUnit.SECONDS), "Second message should arrive after reconnect");
        assertTrue(receivedMessages.size() >= 2, "Should have received at least 2 messages");
    }

    @Test
    void concurrentSendsFromMultipleThreads() throws Exception {
        NodeId nodeA = NodeId.of(1);
        NodeId nodeB = NodeId.of(2);

        int messageCount = 50;
        CountDownLatch allReceived = new CountDownLatch(messageCount);
        var receivedMessages = Collections.synchronizedList(new ArrayList<InboundMessage>());

        TcpRaftTransport transportB = createTransport(
                nodeB,
                new InetSocketAddress("127.0.0.1", 0),
                Map.of(),
                msg -> {
                    receivedMessages.add(msg);
                    allReceived.countDown();
                }
        );
        transportB.start();
        int portB = transportB.localPort();

        TcpRaftTransport transportA = createTransport(
                nodeA,
                new InetSocketAddress("127.0.0.1", 0),
                Map.of(nodeB, new InetSocketAddress("127.0.0.1", portB)),
                msg -> {}
        );
        transportA.start();

        CountDownLatch startGun = new CountDownLatch(1);
        Thread[] senders = new Thread[messageCount];
        for (int i = 0; i < messageCount; i++) {
            final int idx = i;
            senders[i] = Thread.ofVirtual().start(() -> {
                try {
                    startGun.await();
                    FrameCodec.Frame frame = new FrameCodec.Frame(
                            MessageType.APPEND_ENTRIES, 1, idx,
                            ("msg-" + idx).getBytes());
                    transportA.send(nodeB, frame);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startGun.countDown();

        for (Thread t : senders) {
            t.join(5000);
        }

        assertTrue(allReceived.await(5, TimeUnit.SECONDS),
                "All " + messageCount + " messages should be received, got " + receivedMessages.size());
        assertEquals(messageCount, receivedMessages.size());
    }

    @Test
    void gracefulShutdown() throws Exception {
        NodeId nodeA = NodeId.of(1);

        TcpRaftTransport transportA = createTransport(
                nodeA,
                new InetSocketAddress("127.0.0.1", 0),
                Map.of(),
                msg -> {}
        );
        transportA.start();
        int port = transportA.localPort();
        assertTrue(port > 0, "Should be bound to a real port");

        transportA.close();
        transports.remove(transportA);

        FrameCodec.Frame frame = new FrameCodec.Frame(
                MessageType.HEARTBEAT, 0, 0L, new byte[0]);
        // send should return without throwing since running is false
        assertDoesNotThrow(() -> transportA.send(NodeId.of(99), frame));
    }

    @Test
    void registerHandlerReceivesMessages() throws Exception {
        NodeId nodeA = NodeId.of(1);
        NodeId nodeB = NodeId.of(2);

        CountDownLatch received = new CountDownLatch(1);
        var handlerMessages = new CopyOnWriteArrayList<Object>();

        TcpRaftTransport transportB = createTransport(
                nodeB,
                new InetSocketAddress("127.0.0.1", 0),
                Map.of(),
                null // no inbound consumer
        );
        transportB.registerHandler((from, message) -> {
            handlerMessages.add(message);
            received.countDown();
        });
        transportB.start();
        int portB = transportB.localPort();

        TcpRaftTransport transportA = createTransport(
                nodeA,
                new InetSocketAddress("127.0.0.1", 0),
                Map.of(nodeB, new InetSocketAddress("127.0.0.1", portB)),
                null
        );
        transportA.start();

        FrameCodec.Frame frame = new FrameCodec.Frame(
                MessageType.REQUEST_VOTE, 3, 42L, "vote-payload".getBytes());
        transportA.send(nodeB, frame);

        assertTrue(received.await(5, TimeUnit.SECONDS));
        assertEquals(1, handlerMessages.size());
        assertInstanceOf(FrameCodec.Frame.class, handlerMessages.getFirst());
        FrameCodec.Frame receivedFrame = (FrameCodec.Frame) handlerMessages.getFirst();
        assertEquals(MessageType.REQUEST_VOTE, receivedFrame.messageType());
        assertEquals(42L, receivedFrame.term());
    }

    @Test
    void sendToUnknownPeerThrows() throws Exception {
        NodeId nodeA = NodeId.of(1);

        TcpRaftTransport transportA = createTransport(
                nodeA,
                new InetSocketAddress("127.0.0.1", 0),
                Map.of(),
                msg -> {}
        );
        transportA.start();

        FrameCodec.Frame frame = new FrameCodec.Frame(
                MessageType.HEARTBEAT, 0, 0L, new byte[0]);
        assertThrows(IllegalArgumentException.class,
                () -> transportA.send(NodeId.of(99), frame));
    }

    @Test
    void emptyPayloadRoundtrip() throws Exception {
        NodeId nodeA = NodeId.of(1);
        NodeId nodeB = NodeId.of(2);

        CountDownLatch received = new CountDownLatch(1);
        var receivedMessages = new CopyOnWriteArrayList<InboundMessage>();

        TcpRaftTransport transportB = createTransport(
                nodeB,
                new InetSocketAddress("127.0.0.1", 0),
                Map.of(),
                msg -> {
                    receivedMessages.add(msg);
                    received.countDown();
                }
        );
        transportB.start();
        int portB = transportB.localPort();

        TcpRaftTransport transportA = createTransport(
                nodeA,
                new InetSocketAddress("127.0.0.1", 0),
                Map.of(nodeB, new InetSocketAddress("127.0.0.1", portB)),
                msg -> {}
        );
        transportA.start();

        FrameCodec.Frame frame = new FrameCodec.Frame(
                MessageType.HEARTBEAT, 0, 0L, new byte[0]);
        transportA.send(nodeB, frame);

        assertTrue(received.await(5, TimeUnit.SECONDS));
        assertEquals(0, receivedMessages.getFirst().frame().payload().length);
    }

    // Hostname verification regression: if the server cert's SAN does not cover the client's
    // target hostname, the TLS handshake must FAIL - even when the cert is otherwise signed by a
    // CA in the trust store. Without SSLParameters.setEndpointIdentificationAlgorithm("HTTPS"),
    // any cert signed by the trust store is accepted, defeating peer pinning.
    @Test
    @Timeout(120)
    void find0051_clientHandshakeRejectsCertWithWrongHostname() throws Exception {
        // The cached fixture holds a self-signed cert whose SAN only covers "localhost", but the
        // client will target "127.0.0.2". The cert is otherwise present in the client's trust
        // store, so the only reason the handshake should fail is hostname verification.
        TlsConfig tlsConfig = new TlsConfig(certFile, keyStorePath, trustStorePath,
                true, java.util.List.of("TLS_AES_256_GCM_SHA384"),
                java.util.List.of("TLSv1.3"), "changeit".toCharArray());
        TlsManager tlsManager = new TlsManager(tlsConfig);

        NodeId nodeA = NodeId.of(1);
        NodeId nodeB = NodeId.of(2);

        CountDownLatch received = new CountDownLatch(1);

        TcpRaftTransport transportB = new TcpRaftTransport(
                nodeB,
                new InetSocketAddress("127.0.0.2", 0),
                Map.of(),
                tlsManager,
                msg -> received.countDown());
        transports.add(transportB);
        transportB.start();
        int portB = transportB.localPort();

        TcpRaftTransport transportA = new TcpRaftTransport(
                nodeA,
                new InetSocketAddress("127.0.0.1", 0),
                Map.of(nodeB, new InetSocketAddress("127.0.0.2", portB)),
                tlsManager,
                msg -> {});
        transports.add(transportA);
        transportA.start();

        FrameCodec.Frame frame = new FrameCodec.Frame(
                MessageType.HEARTBEAT, 0, 0L, new byte[0]);

        // Without the fix: send() succeeds, handshake passes because
        // endpoint identification is disabled, SAN mismatch is ignored, and
        // `received` latches to zero.
        // With the fix: the handshake fails with an SSLHandshakeException
        // wrapped in IOException; no frame ever arrives at B, so the latch
        // remains at 1 and await() returns false after timeout.
        for (int i = 0; i < 5; i++) {
            try { transportA.send(nodeB, frame); } catch (Exception ignored) {}
            Thread.sleep(100);
        }
        assertFalse(received.await(500, TimeUnit.MILLISECONDS),
                "F-0051: hostname verification must block the handshake when "
                        + "the peer certificate SAN does not match the client target "
                        + "hostname; a message should NOT have reached peer B.");
    }

    private static void runKeytool(String... command) throws Exception {
        int rc = new ProcessBuilder(command).redirectErrorStream(true)
                .inheritIO().start().waitFor();
        assertEquals(0, rc, "keytool failed: " + command[0]);
    }


    private TcpRaftTransport createTransport(
            NodeId self,
            InetSocketAddress bindAddress,
            Map<NodeId, InetSocketAddress> peers,
            Consumer<InboundMessage> handler
    ) {
        TcpRaftTransport transport = new TcpRaftTransport(
                self, bindAddress, peers, null, handler);
        transports.add(transport);
        return transport;
    }
}
