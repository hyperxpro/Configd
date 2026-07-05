package io.configd.netty;

import io.configd.common.NodeId;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import io.configd.transport.PeerIdentityPolicy;
import io.configd.transport.RaftTransportMetrics;
import io.configd.transport.TlsConfig;
import io.configd.transport.TlsManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Netty twin of {@code RaftTransportMtlsAttackTest}'s peer-identity coverage: the WH-08/09 identity
 * binding must reject the SAME hostile inputs on {@link NettyRaftTransport} as on the JDK
 * {@code TcpRaftTransport} (tier-parity discipline, cf. PR #66). Mirrors the four scenarios in
 * {@code RaftPeerIdentityBindingTest}; the attacker is a raw mTLS {@link SSLSocket}, so the client
 * side is transport-agnostic and only the server pipeline differs.
 */
class NettyRaftPeerIdentityBindingTest {

    private static Path fixtureDir;
    private static Path node1Ks;
    private static Path node1Cert;
    private static Path node2Ks;
    private static Path clientKs;
    private static Path trustStore;
    private static final char[] PASS = "changeit".toCharArray();

    private static final PeerIdentityPolicy ENFORCED = PeerIdentityPolicy.of("CN",
            Map.of("raft-node-1", NodeId.of(1), "raft-node-2", NodeId.of(2)));

    private final List<NettyRaftTransport> transports = new CopyOnWriteArrayList<>();
    private final List<SSLServerSocket> maliciousServers = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void generateTlsFixture() throws Exception {
        fixtureDir = Files.createTempDirectory("configd-netty-identity-");
        node1Ks = fixtureDir.resolve("node1-ks.p12");
        node2Ks = fixtureDir.resolve("node2-ks.p12");
        clientKs = fixtureDir.resolve("client-ks.p12");
        trustStore = fixtureDir.resolve("trust.p12");
        node1Cert = fixtureDir.resolve("node1.pem");
        Path node2Cert = fixtureDir.resolve("node2.pem");
        Path clientCert = fixtureDir.resolve("client.pem");

        genKeyPair(node1Ks, "node1", "CN=raft-node-1,O=configd-test");
        genKeyPair(node2Ks, "node2", "CN=raft-node-2,O=configd-test");
        genKeyPair(clientKs, "client", "CN=raft-client,O=configd-test");
        exportCert(node1Ks, "node1", node1Cert);
        exportCert(node2Ks, "node2", node2Cert);
        exportCert(clientKs, "client", clientCert);
        importCert(trustStore, "node1", node1Cert);
        importCert(trustStore, "node2", node2Cert);
        importCert(trustStore, "client", clientCert);
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
                    // best-effort temp cleanup
                }
            });
        }
    }

    @AfterEach
    void tearDown() {
        for (NettyRaftTransport t : transports) {
            t.close();
        }
        transports.clear();
        for (SSLServerSocket ss : maliciousServers) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
        maliciousServers.clear();
    }

    @Test
    @Timeout(120)
    void peerForgingAnotherNodesSenderIdIsRejected() throws Exception {
        AtomicInteger inbound = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        int port = startServer(ENFORCED, inbound, rejections);

        connectHandshakeWrite(node2Ks, 1, port);

        assertNoInboundWithin(inbound, 1_000);
        assertEquals(0, inbound.get(), "a forged senderId must never be delivered as a peer message");
        assertTrue(rejections.get() >= 1, "the forged senderId must increment the identity-mismatch counter");
    }

    @Test
    @Timeout(120)
    void clientCertNotInAllowListCannotOpenPeerConnection() throws Exception {
        AtomicInteger inbound = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        int port = startServer(ENFORCED, inbound, rejections);

        connectHandshakeWrite(clientKs, 2, port);

        assertNoInboundWithin(inbound, 1_000);
        assertEquals(0, inbound.get(), "a non-node client cert must not open a peer connection");
        assertTrue(rejections.get() >= 1, "an unauthorized cert identity must increment the mismatch counter");
    }

    @Test
    @Timeout(120)
    void sameIdentityFrameIsDelivered() throws Exception {
        AtomicInteger inbound = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        int port = startServer(ENFORCED, inbound, rejections);

        connectHandshakeWrite(node2Ks, 2, port);

        awaitInbound(inbound, 1, 3_000);
        assertEquals(1, inbound.get(), "a same-identity frame from an authorized node must be delivered");
        assertEquals(0, rejections.get(), "a legitimate frame must not be counted as a mismatch");
    }

    @Test
    @Timeout(120)
    void unconfiguredAllowListPreservesLegacyBehaviour() throws Exception {
        AtomicInteger inbound = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        int port = startServer(PeerIdentityPolicy.unenforced(), inbound, rejections);

        connectHandshakeWrite(node2Ks, 1, port);

        awaitInbound(inbound, 1, 3_000);
        assertEquals(1, inbound.get(),
                "with no allow-list the transport must preserve legacy CA-chain-only delivery");
        assertEquals(0, rejections.get(), "nothing is counted when identity binding is unconfigured");
    }

    @Test
    @Timeout(120)
    void peerForgingSenderIdOnOutboundReversePathIsRejected() throws Exception {
        // A malicious peer presents a valid node-2 cert, accepts the connection WE dial, and writes a
        // frame forging a THIRD node's senderId (3) back on the reverse path. Our outbound PeerHandler
        // must bind that reply to the dialed target (node-2) and reject the forged id.
        SSLServerSocket evil = maliciousReplyServer(node2Ks, 3);
        int evilPort = evil.getLocalPort();

        AtomicInteger inbound = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        NettyRaftTransport ours = startClientTransport(NodeId.of(2), evilPort, ENFORCED, inbound, rejections);

        ours.send(NodeId.of(2), new FrameCodec.Frame(MessageType.HEARTBEAT, 1, 1L, "dial".getBytes()));

        awaitAtLeast(rejections, 1, 6_000);
        assertEquals(0, inbound.get(),
                "a forged-senderId reply on our own outbound connection must not be dispatched");
        assertTrue(rejections.get() >= 1, "the reverse-path forgery must increment the mismatch counter");
    }

    @Test
    @Timeout(120)
    void honestReplyOnOutboundReversePathIsDelivered() throws Exception {
        SSLServerSocket honest = maliciousReplyServer(node2Ks, 2); // replies with its OWN id (== target)
        int port = honest.getLocalPort();

        AtomicInteger inbound = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        NettyRaftTransport ours = startClientTransport(NodeId.of(2), port, ENFORCED, inbound, rejections);

        ours.send(NodeId.of(2), new FrameCodec.Frame(MessageType.HEARTBEAT, 1, 1L, "dial".getBytes()));

        awaitInbound(inbound, 1, 6_000);
        assertEquals(1, inbound.get(), "a same-identity reply on our outbound connection must be delivered");
        assertEquals(0, rejections.get());
    }

    @Test
    @Timeout(120)
    void enforcedPolicyWithPlaintextTransportRefusesToStart() {
        // Parity with the JDK transport: an enforced allow-list without mTLS must fail loud at startup.
        NettyRaftTransport plaintext = new NettyRaftTransport(
                NodeId.of(1), new InetSocketAddress("127.0.0.1", 0), Map.of(),
                null, msg -> { }, ENFORCED, RaftTransportMetrics.NOOP);
        transports.add(plaintext);
        assertThrows(IllegalStateException.class, plaintext::start,
                "an enforced allow-list without mTLS must fail loud at startup, never fail open");
    }

    // ---- helpers ----

    private NettyRaftTransport startClientTransport(NodeId peerId, int peerPort, PeerIdentityPolicy policy,
            AtomicInteger inbound, AtomicInteger rejections) throws Exception {
        TlsConfig tls = new TlsConfig(node1Cert, node1Ks, trustStore, true,
                List.of("TLS_AES_256_GCM_SHA384"), List.of("TLSv1.3"), PASS);
        RaftTransportMetrics metrics = new RaftTransportMetrics() {
            @Override public void onPeerIdentityRejected() { rejections.incrementAndGet(); }
        };
        NettyRaftTransport t = new NettyRaftTransport(
                NodeId.of(1), new InetSocketAddress("127.0.0.1", 0),
                Map.of(peerId, new InetSocketAddress("127.0.0.1", peerPort)),
                new TlsManager(tls), msg -> inbound.incrementAndGet(), policy, metrics);
        transports.add(t);
        t.start();
        return t;
    }

    private SSLServerSocket maliciousReplyServer(Path serverKs, int forgedSenderId) throws Exception {
        SSLContext ctx = clientContext(serverKs, trustStore); // symmetric ctx; used as server factory
        SSLServerSocket ss = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket();
        ss.setEnabledProtocols(new String[]{"TLSv1.3"});
        ss.setNeedClientAuth(false);
        ss.bind(new InetSocketAddress("127.0.0.1", 0));
        maliciousServers.add(ss);
        Thread t = new Thread(() -> {
            try (SSLSocket s = (SSLSocket) ss.accept()) {
                s.startHandshake();
                OutputStream out = s.getOutputStream();
                out.write(raftWire(NodeId.of(forgedSenderId),
                        new FrameCodec.Frame(MessageType.HEARTBEAT, 1, 1L, "reverse".getBytes())));
                out.flush();
                Thread.sleep(2_000);
            } catch (Exception ignored) {
                // accept/handshake interrupted at teardown, or client already gone - benign
            }
        }, "malicious-reply-peer");
        t.setDaemon(true);
        t.start();
        return ss;
    }

    private static void awaitAtLeast(AtomicInteger counter, int expected, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline && counter.get() < expected) {
            Thread.sleep(25);
        }
    }

    private int startServer(PeerIdentityPolicy policy, AtomicInteger inbound, AtomicInteger rejections)
            throws Exception {
        TlsConfig serverTls = new TlsConfig(node1Cert, node1Ks, trustStore, true,
                List.of("TLS_AES_256_GCM_SHA384"), List.of("TLSv1.3"), PASS);
        RaftTransportMetrics metrics = new RaftTransportMetrics() {
            @Override public void onPeerIdentityRejected() { rejections.incrementAndGet(); }
        };
        NettyRaftTransport server = new NettyRaftTransport(
                NodeId.of(1), new InetSocketAddress("127.0.0.1", 0), Map.of(),
                new TlsManager(serverTls), msg -> inbound.incrementAndGet(),
                policy, metrics);
        transports.add(server);
        server.start();
        return server.localPort();
    }

    private void connectHandshakeWrite(Path attackerKs, int senderId, int port) throws Exception {
        SSLContext ctx = clientContext(attackerKs, trustStore);
        SSLSocket sock = (SSLSocket) ctx.getSocketFactory().createSocket();
        try (sock) {
            sock.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            sock.setSoTimeout(3_000);
            try {
                sock.startHandshake();
            } catch (IOException handshakeFailed) {
                return;
            }
            OutputStream out = sock.getOutputStream();
            out.write(raftWire(NodeId.of(senderId),
                    new FrameCodec.Frame(MessageType.HEARTBEAT, 1, 1L, "id-bind".getBytes())));
            out.flush();
            drainBriefly(sock.getInputStream());
        } catch (IOException dropped) {
            // server reset the connection (a valid rejection); asserted via the server-side counters
        }
    }

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

    private static void drainBriefly(InputStream in) throws IOException {
        byte[] buf = new byte[256];
        try {
            in.read(buf);
        } catch (java.net.SocketTimeoutException ignored) {
            // no reply within the bound
        }
    }

    private static void assertNoInboundWithin(AtomicInteger inbound, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            if (inbound.get() != 0) {
                return;
            }
            Thread.sleep(25);
        }
    }

    private static void awaitInbound(AtomicInteger inbound, int expected, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline && inbound.get() < expected) {
            Thread.sleep(25);
        }
    }

    private static SSLContext clientContext(Path clientKs, Path trustStorePath) throws Exception {
        KeyStore ks = loadStore(clientKs);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS);
        KeyStore ts = loadStore(trustStorePath);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    private static KeyStore loadStore(Path p) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p)) {
            ks.load(in, PASS);
        }
        return ks;
    }

    private static void genKeyPair(Path keyStore, String alias, String dname) throws Exception {
        runKeytool("keytool", "-genkeypair", "-alias", alias,
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-validity", "1", "-dname", dname, "-ext", "san=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", "changeit", "-keypass", "changeit");
    }

    private static void exportCert(Path keyStore, String alias, Path certOut) throws Exception {
        runKeytool("keytool", "-exportcert", "-alias", alias,
                "-keystore", keyStore.toString(), "-storepass", "changeit",
                "-rfc", "-file", certOut.toString());
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
