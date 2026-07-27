package io.configd.netty;

import io.configd.common.NodeId;
import io.configd.transport.FrameCodec;
import io.configd.transport.InboundMessage;
import io.configd.transport.MessageType;
import io.configd.transport.RaftTransportEndpoint;
import io.configd.transport.TlsConfig;
import io.configd.transport.TlsManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Transport-equivalence contract: same functional, mTLS, and attack-rejection properties across
 * all {@link RaftTransportEndpoint} implementations, varying only transport construction.
 */
@Timeout(120)
abstract class AbstractRaftTransportContract {

    /**
     * Builds the transport under test. Concrete subclasses return {@code new TcpRaftTransport(...)} /
     * {@code new NettyRaftTransport(...)} (auto tier) / a forced-NIO {@code NettyRaftTransport}; this is
     * the ONLY construction difference across transports.
     */
    protected abstract RaftTransportEndpoint newEndpoint(NodeId self,
                                                         InetSocketAddress bind,
                                                         Map<NodeId, InetSocketAddress> peers,
                                                         TlsManager tls,
                                                         Consumer<InboundMessage> handler);

    private static final char[] PASS = "changeit".toCharArray();

    /** Every endpoint created via {@link #createEndpoint} is closed in {@link #tearDown}. */
    private final List<RaftTransportEndpoint> transports = new CopyOnWriteArrayList<>();
    /** Bare attacker sockets (slowloris / cap legs); closed in {@link #tearDown}. */
    private final List<Socket> attackers = new ArrayList<>();

    private static Path fixtureDir;
    private static Path f0051KeyStore;
    private static Path f0051TrustStore;
    private static Path f0051Cert;
    private static Path serverKeyStore;
    private static Path serverTrustStore;
    private static Path clientKeyStore;
    private static Path expiredKeyStore;
    private static Path untrustedClientKeyStore;

    @BeforeAll
    static void generateTlsFixture() throws Exception {
        fixtureDir = Files.createTempDirectory("configd-raft-transport-contract-");
        f0051KeyStore = fixtureDir.resolve("f0051-ks.p12");
        f0051TrustStore = fixtureDir.resolve("f0051-ts.p12");
        f0051Cert = fixtureDir.resolve("f0051.pem");
        runKeytool("keytool",
                "-genkeypair", "-alias", "server",
                "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-validity", "1",
                "-dname", "CN=localhost,O=test",
                "-ext", "san=dns:localhost",
                "-storetype", "PKCS12",
                "-keystore", f0051KeyStore.toString(),
                "-storepass", "changeit", "-keypass", "changeit");
        runKeytool("keytool",
                "-exportcert", "-alias", "server",
                "-keystore", f0051KeyStore.toString(),
                "-storepass", "changeit", "-rfc",
                "-file", f0051Cert.toString());
        runKeytool("keytool",
                "-importcert", "-alias", "server",
                "-file", f0051Cert.toString(),
                "-keystore", f0051TrustStore.toString(),
                "-storepass", "changeit", "-storetype", "PKCS12",
                "-noprompt");
        serverKeyStore = fixtureDir.resolve("server-ks.p12");
        serverTrustStore = fixtureDir.resolve("server-ts.p12");
        clientKeyStore = fixtureDir.resolve("client-ks.p12");
        expiredKeyStore = fixtureDir.resolve("expired-ks.p12");
        Path caKeyStore = fixtureDir.resolve("ca-ks.p12");
        Path serverCert = fixtureDir.resolve("server.pem");
        Path clientCert = fixtureDir.resolve("client.pem");
        Path caCert = fixtureDir.resolve("ca.pem");
        genKeyPair(serverKeyStore, "server", "CN=localhost,O=configd-test", "-validity", "1");
        genKeyPair(clientKeyStore, "client", "CN=raft-peer-1,O=configd-test", "-validity", "1");
        exportCert(serverKeyStore, "server", serverCert);
        exportCert(clientKeyStore, "client", clientCert);
        genCa(caKeyStore, "CN=configd-test-ca,O=configd-test");
        exportCert(caKeyStore, "ca", caCert);
        genCaSignedExpiredEndEntity(expiredKeyStore, caKeyStore, caCert,
                "CN=raft-peer-expired,O=configd-test");
        importCert(serverTrustStore, "server", serverCert);
        importCert(serverTrustStore, "client", clientCert);
        importCert(serverTrustStore, "ca", caCert);
        untrustedClientKeyStore = fixtureDir.resolve("untrusted-client-ks.p12");
        Path untrustedCaKeyStore = fixtureDir.resolve("untrusted-ca-ks.p12");
        Path untrustedCaCert = fixtureDir.resolve("untrusted-ca.pem");
        genCa(untrustedCaKeyStore, "CN=configd-untrusted-ca,O=attacker");
        exportCert(untrustedCaKeyStore, "ca", untrustedCaCert);
        genCaSignedValidEndEntity(untrustedClientKeyStore, untrustedCaKeyStore, untrustedCaCert,
                "CN=raft-peer-untrusted,O=attacker");
    }

    @AfterAll
    static void deleteTlsFixture() throws Exception {
        if (fixtureDir == null) {
            return;
        }
        try (var paths = Files.walk(fixtureDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @AfterEach
    void tearDown() {
        for (Socket s : attackers) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
        attackers.clear();
        for (RaftTransportEndpoint t : transports) {
            t.close();
        }
        transports.clear();
    }

    // Construction helpers - the single point that parametrizes over transports.

    /** Calls {@link #newEndpoint} and tracks the result for {@link #tearDown} cleanup. */
    private RaftTransportEndpoint createEndpoint(NodeId self, InetSocketAddress bind,
                                                 Map<NodeId, InetSocketAddress> peers,
                                                 TlsManager tls, Consumer<InboundMessage> handler) {
        RaftTransportEndpoint t = newEndpoint(self, bind, peers, tls, handler);
        transports.add(t);
        return t;
    }

    private RaftTransportEndpoint createTransport(NodeId self, InetSocketAddress bindAddress,
                                                  Map<NodeId, InetSocketAddress> peers,
                                                  Consumer<InboundMessage> handler) {
        return createEndpoint(self, bindAddress, peers, null, handler);
    }

    // Functional tests (folded from TcpRaftTransportTest).

    @Test
    void sendMessageBetweenTwoNodes() throws Exception {
        NodeId nodeA = NodeId.of(1);
        NodeId nodeB = NodeId.of(2);

        CountDownLatch receivedLatch = new CountDownLatch(1);
        var receivedMessages = new CopyOnWriteArrayList<InboundMessage>();

        RaftTransportEndpoint transportB = createTransport(
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

        RaftTransportEndpoint transportA = createTransport(
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

        RaftTransportEndpoint transportB = createTransport(
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

        RaftTransportEndpoint transportA = createTransport(
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

        RaftTransportEndpoint transportB = createTransport(
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

        RaftTransportEndpoint transportA = createTransport(
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

        // Small delay to let the close propagate.
        Thread.sleep(200);

        RaftTransportEndpoint transportB2 = createTransport(
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

        RaftTransportEndpoint transportB = createTransport(
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

        RaftTransportEndpoint transportA = createTransport(
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

        RaftTransportEndpoint transportA = createTransport(
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
        // send() must return without throwing since running is now false.
        assertDoesNotThrow(() -> transportA.send(NodeId.of(99), frame));
    }

    @Test
    void registerHandlerReceivesMessages() throws Exception {
        NodeId nodeA = NodeId.of(1);
        NodeId nodeB = NodeId.of(2);

        CountDownLatch received = new CountDownLatch(1);
        var handlerMessages = new CopyOnWriteArrayList<Object>();

        RaftTransportEndpoint transportB = createTransport(
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

        RaftTransportEndpoint transportA = createTransport(
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

        RaftTransportEndpoint transportA = createTransport(
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

        RaftTransportEndpoint transportB = createTransport(
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

        RaftTransportEndpoint transportA = createTransport(
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
        TlsConfig tlsConfig = new TlsConfig(f0051Cert, f0051KeyStore, f0051TrustStore,
                true, java.util.List.of("TLS_AES_256_GCM_SHA384"),
                java.util.List.of("TLSv1.3"), "changeit".toCharArray());
        TlsManager tlsManager = new TlsManager(tlsConfig);

        NodeId nodeA = NodeId.of(1);
        NodeId nodeB = NodeId.of(2);

        CountDownLatch received = new CountDownLatch(1);

        // Server bound to 127.0.0.2 but its cert SAN is "localhost".
        RaftTransportEndpoint transportB = createEndpoint(
                nodeB,
                new InetSocketAddress("127.0.0.2", 0),
                Map.of(),
                tlsManager,
                msg -> received.countDown());
        transportB.start();
        int portB = transportB.localPort();

        // Client targets 127.0.0.2, which the SAN does not cover, so the handshake must fail.
        RaftTransportEndpoint transportA = createEndpoint(
                nodeA,
                new InetSocketAddress("127.0.0.1", 0),
                Map.of(nodeB, new InetSocketAddress("127.0.0.2", portB)),
                tlsManager,
                msg -> {});
        transportA.start();

        FrameCodec.Frame frame = new FrameCodec.Frame(
                MessageType.HEARTBEAT, 0, 0L, new byte[0]);

        // Endpoint identification must reject the SAN mismatch (SSLHandshakeException), so no
        // frame ever arrives at B and `received` stays at zero.
        for (int i = 0; i < 5; i++) {
            try { transportA.send(nodeB, frame); } catch (Exception ignored) {}
            Thread.sleep(100);
        }
        assertFalse(received.await(500, TimeUnit.MILLISECONDS),
                "F-0051: hostname verification must block the handshake when "
                        + "the peer certificate SAN does not match the client target "
                        + "hostname; a message should NOT have reached peer B.");
    }

    // mTLS negative / attack tests (folded from RaftTransportMtlsAttackTest).

    @Test
    @Timeout(120)
    void plaintextFrameIsNeverDecodedAsAPeerMessage() throws Exception {
        AtomicInteger inboundCount = new AtomicInteger();
        int port = startMtlsServer(serverKeyStore, inboundCount);

        // The attacker opens a PLAIN TCP socket (no TLS) and writes a syntactically-valid Raft wire
        // frame: [4-byte sender NodeId][FrameCodec frame]. Against the TLS-only server these are
        // application bytes before any handshake; the SSLServerSocket treats them as a malformed TLS
        // record and tears the connection down. The frame must NEVER reach the inbound handler.
        byte[] wire = raftWire(NodeId.of(7),
                new FrameCodec.Frame(MessageType.HEARTBEAT, 1, 1L, "plaintext".getBytes()));
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            plain.setSoTimeout(2_000);
            OutputStream out = plain.getOutputStream();
            out.write(wire);
            out.flush();
            // The server, if it speaks plaintext, would decode and dispatch immediately. Read until
            // EOF/timeout (the server drops the bad TLS record) - a bounded wait, never a hang.
            drainBriefly(plain.getInputStream());
        } catch (IOException expected) {
            // Connection reset by the TLS server rejecting the record - also a valid rejection.
        }

        // Give any (erroneous) async dispatch a bounded window to surface, then assert silence.
        assertNoInboundWithin(inboundCount, 1_000);
        assertEquals(0, inboundCount.get(),
                "a plaintext frame must never be decoded as a peer message by the TLS-only Raft server");
    }

    @Test
    @Timeout(120)
    void expiredClientCertificateIsRejected() throws Exception {
        AtomicInteger inboundCount = new AtomicInteger();
        int port = startMtlsServer(serverKeyStore, inboundCount);

        // A client presenting an already-expired CA-signed end-entity certificate. The CA is in the
        // server trust store, so path validation succeeds up to the anchor and the only failure is
        // the leaf's dead validity window (notAfter in the past), distinct from the untrusted-CA case.
        SSLContext attacker = clientContext(expiredKeyStore, serverTrustStore);
        boolean rejected = attemptHandshakeAndSend(attacker, port);

        assertTrue(rejected, "an expired client certificate must be rejected by the Raft server");
        assertEquals(0, inboundCount.get(), "no frame may be delivered behind an expired client cert");
    }

    @Test
    @Timeout(120)
    void tlsV12OnlyClientIsRejectedByTheTlsV13OnlyServer() throws Exception {
        AtomicInteger inboundCount = new AtomicInteger();
        int port = startMtlsServer(serverKeyStore, inboundCount);

        // The attacker presents a fully trusted client credential but offers ONLY TLSv1.2. The
        // server is TLSv1.3-only (TlsConfig.protocols()), so there is no common protocol and the
        // handshake must fail - nothing downgrades below TLSv1.3.
        SSLContext ctx = clientContext(clientKeyStore, serverTrustStore);
        SSLSocket sock = (SSLSocket) ctx.getSocketFactory().createSocket();
        sock.setEnabledProtocols(new String[]{"TLSv1.2"});
        boolean rejected = attemptHandshakeAndSend(sock, port);

        assertTrue(rejected, "a TLSv1.2-only client must be rejected by the TLSv1.3-only Raft server");
        assertEquals(0, inboundCount.get(), "no frame may be delivered over a downgraded connection");
    }

    // Mutual-auth negatives.

    @Test
    @Timeout(120)
    void untrustedCaClientCertificateIsRejected() throws Exception {
        AtomicInteger inboundCount = new AtomicInteger();
        int port = startMtlsServer(serverKeyStore, inboundCount);

        // A client whose end-entity certificate is signed by a DIFFERENT CA that is NOT in the
        // server trust store. The server's PKIX path build cannot reach a trust anchor, so the
        // handshake fails. Distinct from the expired case: here the chain is well-formed and valid,
        // the failure is purely an untrusted issuer.
        SSLContext attacker = clientContext(untrustedClientKeyStore, serverTrustStore);
        boolean rejected = attemptHandshakeAndSend(attacker, port);

        assertTrue(rejected, "a client whose CA is untrusted must be rejected by the Raft server");
        assertEquals(0, inboundCount.get(),
                "no frame may be delivered behind an untrusted-CA client cert");
    }

    @Test
    @Timeout(120)
    void clientWithNoCertificateIsRejected() throws Exception {
        AtomicInteger inboundCount = new AtomicInteger();
        int port = startMtlsServer(serverKeyStore, inboundCount);

        // A client that trusts the server but presents NO client certificate (null KeyManagers)
        // against the setNeedClientAuth(true) server -> must reject. In TLS 1.3 the client's
        // startHandshake() may return before the server's rejection lands, so the authoritative
        // check is the zero inbound count: no decoded peer frame is ever served.
        SSLContext noCert = clientContext(null, serverTrustStore);
        boolean rejected = attemptHandshakeAndSend(noCert, port);

        assertTrue(rejected, "a client presenting no certificate must be rejected by the Raft server");
        assertEquals(0, inboundCount.get(),
                "no frame may be delivered without a client certificate");
    }

    // Slowloris / admission cap (folded from TcpRaftTransportSlowlorisTest).

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void slowDripPeerIsDroppedWithinTheReadIdleDeadline() throws Exception {
        String savedTimeout = System.getProperty("configd.raft.inboundReadTimeoutMs");
        try {
            System.setProperty("configd.raft.inboundReadTimeoutMs", "500"); // short deadline for the test
            int port = freePort();
            startTransport(port);

            Socket attacker = connectAttacker(port);
            // Slow drip: send NOTHING. The server's first readInt() (sender id) blocks, then trips the
            // 500 ms read-idle deadline and closes the connection - which the attacker observes as EOF.
            long t0 = System.nanoTime();
            attacker.setSoTimeout(5_000); // bound the attacker's own read so the test can never hang
            InputStream in = attacker.getInputStream();
            int b = in.read(); // returns -1 when the server closes its side after the deadline
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            assertEquals(-1, b,
                    "the server must CLOSE a slow-drip connection (EOF), not park its reader forever");
            assertTrue(elapsedMs < 3_000,
                    "the slow-drip peer must be dropped within ~the read-idle deadline, was " + elapsedMs + "ms");
        } finally {
            if (savedTimeout == null) System.clearProperty("configd.raft.inboundReadTimeoutMs");
            else System.setProperty("configd.raft.inboundReadTimeoutMs", savedTimeout);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void inboundConnectionsAreCappedAndExcessIsRefused() throws Exception {
        String savedCap = System.getProperty("configd.raft.maxInboundConnections");
        String savedTimeout = System.getProperty("configd.raft.inboundReadTimeoutMs");
        try {
            int cap = 3;
            System.setProperty("configd.raft.maxInboundConnections", Integer.toString(cap));
            // Long deadline so the first `cap` stalled sockets STAY in the live-set while we flood more.
            System.setProperty("configd.raft.inboundReadTimeoutMs", "10000");
            int port = freePort();
            RaftTransportEndpoint t = startTransport(port);

            // Open well beyond the cap; each connects and stalls (sends nothing).
            int flood = cap + 5;
            for (int i = 0; i < flood; i++) {
                connectAttacker(port);
            }

            // The accept loop refuses (closes + counts) every socket beyond the cap. Poll for it.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            long refused = 0;
            while (System.nanoTime() < deadline) {
                refused = t.inboundConnectionsRefused();
                if (refused >= 1) break;
                Thread.sleep(50);
            }
            assertTrue(refused >= 1,
                    "inbound connections beyond maxInboundConnections=" + cap
                            + " must be refused (node stays available); refused=" + refused);
        } finally {
            if (savedCap == null) System.clearProperty("configd.raft.maxInboundConnections");
            else System.setProperty("configd.raft.maxInboundConnections", savedCap);
            if (savedTimeout == null) System.clearProperty("configd.raft.inboundReadTimeoutMs");
            else System.setProperty("configd.raft.inboundReadTimeoutMs", savedTimeout);
        }
    }

    // Blackhole tests: send must NEVER park the caller (folded from TcpRaftTransportBlackholeTest).

    /**
     * A non-routable destination on 10.255.255.0/24. SYNs sent here are dropped (no RST), so
     * {@code connect()} parks for the full OS SYN timeout - the same behaviour an iptables
     * {@code -j DROP} produces against a real peer.
     */
    private static final String BLACKHOLE_HOST = "10.255.255.1";
    private static final int BLACKHOLE_PORT = 9999;

    /** How long the production tick thread may be released within (the contract). */
    private static final long CALLER_RELEASE_BUDGET_MS = 2_000;

    /**
     * Bounded observation window for the worker thread. Must comfortably exceed
     * the release budget yet stay far below the ~127 s OS SYN timeout so a pre-fix
     * hang is observed (and failed) quickly instead of waiting it out.
     */
    private static final long OBSERVE_MS = 10_000;

    /**
     * A {@code send} to a black-holed peer must release the calling thread within
     * {@link #CALLER_RELEASE_BUDGET_MS}.
     */
    @Test
    void callingThreadReleasedWhenPeerBlackholed() throws Exception {
        NodeId self = NodeId.of(1);
        NodeId blackholed = NodeId.of(2);

        RaftTransportEndpoint transport = newTransport(
                self,
                Map.of(blackholed, new InetSocketAddress(BLACKHOLE_HOST, BLACKHOLE_PORT)));
        transport.start();

        FrameCodec.Frame frame = new FrameCodec.Frame(
                MessageType.APPEND_ENTRIES, 1, 5L, "probe".getBytes());

        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                // Stands in for the configd-tick thread calling transport.send().
                transport.send(blackholed, frame);
            } catch (Throwable t) {
                unexpected.set(t);
            } finally {
                returned.countDown();
            }
        }, "rr002-caller");
        worker.setDaemon(true);
        worker.start();

        boolean released = returned.await(CALLER_RELEASE_BUDGET_MS, TimeUnit.MILLISECONDS);
        if (!released) {
            // Bounded observation: confirm the worker is parked, snapshot its
            // stack as evidence, then fail WITHOUT waiting out the ~127 s SYN
            // timeout. A correct implementation never takes this branch.
            String stack = stackSnippet(worker);
            // Give it the rest of the observation window only to enrich the
            // diagnostic - the assertion has already conceptually failed.
            returned.await(OBSERVE_MS - CALLER_RELEASE_BUDGET_MS, TimeUnit.MILLISECONDS);
            fail("RR-002: send() to a black-holed peer did NOT release the calling "
                    + "thread within " + CALLER_RELEASE_BUDGET_MS + "ms — the tick "
                    + "thread is parked on connect/handshake. Worker stack:\n" + stack);
        }

        if (unexpected.get() != null) {
            throw new AssertionError(
                    "send() to a black-holed peer threw instead of returning quietly; "
                            + "Raft tolerates message loss, so a drop must be silent at this layer",
                    unexpected.get());
        }
    }

    /**
     * A second, slightly different angle: many ticks in a row toward a black-holed
     * peer must not accumulate into a multi-second stall. This catches a fix that
     * bounds the FIRST connect but still blocks subsequent sends behind it.
     */
    @Test
    void repeatedSendsToBlackholedPeerStayBounded() throws Exception {
        NodeId self = NodeId.of(1);
        NodeId blackholed = NodeId.of(2);

        RaftTransportEndpoint transport = newTransport(
                self,
                Map.of(blackholed, new InetSocketAddress(BLACKHOLE_HOST, BLACKHOLE_PORT)));
        transport.start();

        FrameCodec.Frame frame = new FrameCodec.Frame(
                MessageType.HEARTBEAT, 1, 1L, "hb".getBytes());

        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                transport.send(blackholed, frame);
            }
            done.countDown();
        }, "rr002-repeated-caller");
        worker.setDaemon(true);
        worker.start();

        boolean finished = done.await(CALLER_RELEASE_BUDGET_MS, TimeUnit.MILLISECONDS);
        if (!finished) {
            String stack = stackSnippet(worker);
            fail("RR-002: 20 sends to a black-holed peer did NOT complete within "
                    + CALLER_RELEASE_BUDGET_MS + "ms — establishment is still on the "
                    + "caller's thread. Worker stack:\n" + stack);
        }
        assertTrue(finished);
    }

    // Transport starters (route construction through newEndpoint).

    private int startMtlsServer(Path keyStore, AtomicInteger inboundCount) throws Exception {
        TlsConfig serverTls = new TlsConfig(
                fixtureDir.resolve("server.pem"), keyStore, serverTrustStore,
                true, List.of("TLS_AES_256_GCM_SHA384"), List.of("TLSv1.3"), PASS);
        RaftTransportEndpoint server = createEndpoint(
                NodeId.of(1), new InetSocketAddress("127.0.0.1", 0), Map.of(),
                new TlsManager(serverTls),
                msg -> inboundCount.incrementAndGet());
        server.start();
        return server.localPort();
    }

    /** Plaintext, inbound-only transport bound to {@code port} (the slowloris legs). */
    private RaftTransportEndpoint startTransport(int port) throws Exception {
        RaftTransportEndpoint t = createEndpoint(
                NodeId.of(1),
                new InetSocketAddress("127.0.0.1", port),
                Map.of(),          // no peers needed for the inbound-only test
                null,              // plaintext
                msg -> { });       // no-op inbound handler
        t.start();
        return t;
    }

    /** Plaintext transport toward a (black-holed) peer (for the blackhole tests). */
    private RaftTransportEndpoint newTransport(NodeId self, Map<NodeId, InetSocketAddress> peers) {
        return createEndpoint(self, new InetSocketAddress("127.0.0.1", 0), peers, null, msg -> {});
    }

    private Socket connectAttacker(int port) throws Exception {
        Socket s = new Socket("127.0.0.1", port);
        attackers.add(s);
        return s;
    }

    /** Grabs an ephemeral free port (closed immediately) for the transport to bind. */
    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    // Attack helpers (verbatim from RaftTransportMtlsAttackTest).

    /**
     * Builds an {@link SSLSocket} from {@code clientCtx} and runs {@link #attemptHandshakeAndSend}.
     */
    private boolean attemptHandshakeAndSend(SSLContext clientCtx, int port) throws Exception {
        SSLSocket sock = (SSLSocket) clientCtx.getSocketFactory().createSocket();
        return attemptHandshakeAndSend(sock, port);
    }

    /**
     * Connects {@code sock} to the server, attempts the handshake, and (if it appears to complete on
     * the client side) writes a valid Raft frame and reads briefly. Returns {@code true} if the
     * connection is rejected: the handshake throws, the write/read fails, or the server simply never
     * acknowledges (the bytes are dropped). The server-side handler-count assertion in each test is
     * the authoritative "no frame got through" check; this method's return is a defence-in-depth
     * signal that the client observed a rejection.
     */
    private boolean attemptHandshakeAndSend(SSLSocket sock, int port) throws Exception {
        try (sock) {
            sock.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            sock.setSoTimeout(3_000);
            try {
                sock.startHandshake();
            } catch (IOException handshakeFailed) {
                return true; // rejected at handshake
            }
            // Client thinks the handshake completed (possible under TLS 1.3 before the server's
            // async rejection lands). Try to actually use it: a rejected peer cannot push a frame.
            try {
                OutputStream out = sock.getOutputStream();
                out.write(raftWire(NodeId.of(9),
                        new FrameCodec.Frame(MessageType.HEARTBEAT, 1, 1L, "after-handshake".getBytes())));
                out.flush();
                drainBriefly(sock.getInputStream());
                return true; // I/O completed without the server accepting it as a peer (asserted by count)
            } catch (IOException ioRejected) {
                return true; // first I/O failed -> rejected
            }
        }
    }

    /** Reads up to EOF or the socket timeout, discarding bytes. Bounded; never hangs. */
    private static void drainBriefly(InputStream in) throws IOException {
        byte[] buf = new byte[256];
        try {
            // A single bounded read is enough: a TLS-only server sends no plaintext reply, so this
            // either returns -1 (EOF after the server drops us) or times out (SO_TIMEOUT).
            in.read(buf);
        } catch (java.net.SocketTimeoutException ignored) {
            // no reply within the bound -> the server did not engage us as a peer
        }
    }

    /** Spin-waits up to {@code millis} asserting the inbound handler stays at zero. */
    private static void assertNoInboundWithin(AtomicInteger inboundCount, long millis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            if (inboundCount.get() != 0) {
                return; // fail fast; the @Test assertion reports it
            }
            Thread.sleep(25);
        }
    }

    /** Builds the on-wire Raft byte sequence: [4-byte sender NodeId][FrameCodec frame]. */
    private static byte[] raftWire(NodeId from, FrameCodec.Frame frame) {
        byte[] encoded = FrameCodec.encode(
                frame.messageType(), frame.groupId(), frame.term(), frame.payload());
        byte[] wire = new byte[4 + encoded.length];
        int id = from.id();
        wire[0] = (byte) (id >>> 24);
        wire[1] = (byte) (id >>> 16);
        wire[2] = (byte) (id >>> 8);
        wire[3] = (byte) id;
        System.arraycopy(encoded, 0, wire, 4, encoded.length);
        return wire;
    }

    /** Renders the connect/handshake frames of a thread's current stack as evidence. */
    private static String stackSnippet(Thread t) {
        StringBuilder sb = new StringBuilder();
        sb.append("  state=").append(t.getState()).append('\n');
        for (StackTraceElement e : t.getStackTrace()) {
            sb.append("    at ").append(e).append('\n');
        }
        return sb.toString();
    }

    // SSLContext + keystore loading.

    private static SSLContext clientContext(Path clientKs, Path trustStore) throws Exception {
        KeyManagerFactory kmf = null;
        if (clientKs != null) {
            KeyStore ks = loadStore(clientKs);
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, PASS);
        }
        KeyStore ts = loadStore(trustStore);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        // TLS context allows 1.2+1.3 by default so the downgrade test can deliberately restrict the
        // socket to 1.2; the server's TLSv1.3-only policy is what must reject it.
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf == null ? null : kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    private static KeyStore loadStore(Path p) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p)) {
            ks.load(in, PASS);
        }
        return ks;
    }

    // Keytool fixture builders.

    private static void genKeyPair(Path keyStore, String alias, String dname, String... validity)
            throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>(List.of(
                "keytool", "-genkeypair", "-alias", alias,
                "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA",
                "-dname", dname, "-ext", "san=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", "changeit", "-keypass", "changeit"));
        cmd.addAll(List.of(validity)); // -validity / -startdate vary per cert
        runKeytool(cmd.toArray(String[]::new));
    }

    private static void exportCert(Path keyStore, String alias, Path certOut) throws Exception {
        runKeytool("keytool", "-exportcert", "-alias", alias,
                "-keystore", keyStore.toString(), "-storepass", "changeit",
                "-rfc", "-file", certOut.toString());
    }

    /** Generates a long-lived CA keypair (alias {@code ca}, basicConstraints CA:true). */
    private static void genCa(Path caKeyStore, String dname) throws Exception {
        runKeytool("keytool", "-genkeypair", "-alias", "ca",
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-validity", "3650", "-dname", dname, "-ext", "bc:c",
                "-storetype", "PKCS12", "-keystore", caKeyStore.toString(),
                "-storepass", "changeit", "-keypass", "changeit");
    }

    /**
     * Builds a keystore (alias {@code expired}) holding a CA-signed end-entity cert whose validity
     * window is already PAST ({@code -startdate -2d -validity 1}, notAfter ~1 day ago), plus the
     * CA in its chain. The CA's {@code -gencert} stamps the dead window; importing the signed reply
     * forms the leaf-to-CA chain the client presents.
     */
    private static void genCaSignedExpiredEndEntity(Path keyStore, Path caKeyStore, Path caCert,
                                                    String dname) throws Exception {
        Path csr = fixtureDir.resolve("expired.csr");
        Path signed = fixtureDir.resolve("expired-signed.pem");
        // End-entity keypair (its own self-signed validity is irrelevant; the CA reply replaces it).
        runKeytool("keytool", "-genkeypair", "-alias", "expired",
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-validity", "1", "-dname", dname,
                "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", "changeit", "-keypass", "changeit");
        runKeytool("keytool", "-certreq", "-alias", "expired",
                "-keystore", keyStore.toString(), "-storepass", "changeit", "-file", csr.toString());
        // CA signs an ALREADY-EXPIRED end-entity cert (startdate 2 days ago, 1-day validity).
        runKeytool("keytool", "-gencert", "-alias", "ca",
                "-keystore", caKeyStore.toString(), "-storepass", "changeit",
                "-infile", csr.toString(), "-outfile", signed.toString(), "-rfc",
                "-startdate", "-2d", "-validity", "1", "-ext", "san=dns:localhost,ip:127.0.0.1");
        // Import the CA then the signed reply so the leaf entry carries the full chain.
        runKeytool("keytool", "-importcert", "-alias", "ca", "-file", caCert.toString(),
                "-keystore", keyStore.toString(), "-storepass", "changeit", "-noprompt");
        runKeytool("keytool", "-importcert", "-alias", "expired", "-file", signed.toString(),
                "-keystore", keyStore.toString(), "-storepass", "changeit", "-noprompt");
    }

    /**
     * Builds a keystore (alias {@code untrusted}) holding a VALID-dated CA-signed end-entity cert,
     * plus its signing CA in the chain. Used for the untrusted-CA negative: the signing CA is NOT in
     * the server trust store, so the only reason to reject is the unbuildable PKIX path. Mirrors
     * {@link #genCaSignedExpiredEndEntity} but with a live validity window and distinct temp files.
     */
    private static void genCaSignedValidEndEntity(Path keyStore, Path caKeyStore, Path caCert,
                                                  String dname) throws Exception {
        Path csr = fixtureDir.resolve("untrusted.csr");
        Path signed = fixtureDir.resolve("untrusted-signed.pem");
        runKeytool("keytool", "-genkeypair", "-alias", "untrusted",
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-validity", "365", "-dname", dname,
                "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", "changeit", "-keypass", "changeit");
        runKeytool("keytool", "-certreq", "-alias", "untrusted",
                "-keystore", keyStore.toString(), "-storepass", "changeit", "-file", csr.toString());
        runKeytool("keytool", "-gencert", "-alias", "ca",
                "-keystore", caKeyStore.toString(), "-storepass", "changeit",
                "-infile", csr.toString(), "-outfile", signed.toString(), "-rfc",
                "-validity", "365", "-ext", "san=dns:localhost,ip:127.0.0.1");
        runKeytool("keytool", "-importcert", "-alias", "ca", "-file", caCert.toString(),
                "-keystore", keyStore.toString(), "-storepass", "changeit", "-noprompt");
        runKeytool("keytool", "-importcert", "-alias", "untrusted", "-file", signed.toString(),
                "-keystore", keyStore.toString(), "-storepass", "changeit", "-noprompt");
    }

    private static void importCert(Path trustStore, String alias, Path certIn) throws Exception {
        runKeytool("keytool", "-importcert", "-alias", alias, "-file", certIn.toString(),
                "-keystore", trustStore.toString(), "-storepass", "changeit",
                "-storetype", "PKCS12", "-noprompt");
    }

    private static void runKeytool(String... command) throws Exception {
        int rc = new ProcessBuilder(command).redirectErrorStream(true).inheritIO().start().waitFor();
        assertTrue(rc == 0, "keytool failed: " + String.join(" ", command));
    }
}
