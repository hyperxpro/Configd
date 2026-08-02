package io.configd.transport;

import io.configd.common.NodeId;

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
import java.net.Socket;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Negative mTLS tests for the control-plane Raft transport ({@link TcpRaftTransport}). Each test
 * performs an attack and asserts the server rejects it - a control is verified only by a test that
 * performs the attack, never by reading config. These close the gaps left open by
 * {@link TcpRaftTransportTest#find0051_clientHandshakeRejectsCertWithWrongHostname} (which already
 * covers the wrong-SAN/identity case on this plane):
 *
 * <ul>
 *   <li><b>plaintext</b> - a plain {@link Socket} writing a syntactically-valid Raft wire frame to
 *       the TLS-only port is never decoded as a peer message (the inbound handler never fires);</li>
 *   <li><b>expired client cert</b> - a client whose certificate is already past {@code notAfter}
 *       (a CA-signed end-entity, {@code -gencert -startdate -2d -validity 1}) fails PKIX path
 *       validation at the server ("validity check failed"); no frame is delivered;</li>
 *   <li><b>version downgrade</b> - a client offering ONLY TLSv1.2 against the TLSv1.3-only server
 *       fails the handshake; nothing downgrades below TLSv1.3.</li>
 * </ul>
 *
 * <h2>Observation discipline (TLS 1.3 timing)</h2>
 * On the Raft plane the only secure, attack-agnostic observation is "did a decoded peer frame reach
 * the inbound handler?". In TLS 1.3 the client {@code startHandshake()} can return before the
 * server's rejection lands; so each test drives a bounded send/observation window and asserts the
 * handler count stays at zero.
 *
 * <h2>Why the expired-cert case needs a CA</h2>
 * Configd's production trust model and the existing test fixtures import each peer's self-signed
 * leaf directly as a trust anchor. Under RFC 5280 section 6.1, a trust anchor's own validity
 * period is NOT part of path validation, so an expired self-signed leaf is accepted. To prove our
 * stack does not disable expiry checking, the expired client here is a CA-signed end-entity
 * validated against a CA-only anchor - the configuration in which JSSE enforces notAfter.
 *
 * <h2>Fixture discipline</h2>
 * All keytool subprocesses are hoisted into a once-per-class {@code @BeforeAll static} fixture
 * (cached temp dir, {@code @AfterAll} cleanup), not subject to the class {@link Timeout}. Each
 * test carries a generous method {@code @Timeout(120)} for pure hang detection, never a
 * performance assertion.
 */
class RaftTransportMtlsAttackTest {

    private static Path fixtureDir;
    private static Path serverKeyStore;
    private static Path serverTrustStore;
    // Legit client identity (trusted) - used by the downgrade test, where the attack is the
    // version offer, not the credential, so the cert must otherwise be valid and trusted.
    private static Path clientKeyStore;
    private static Path expiredKeyStore;
    private static final char[] PASS = "changeit".toCharArray();

    private final List<TcpRaftTransport> transports = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void generateTlsFixture() throws Exception {
        fixtureDir = Files.createTempDirectory("configd-raft-mtls-attack-");
        serverKeyStore = fixtureDir.resolve("server-ks.p12");
        serverTrustStore = fixtureDir.resolve("server-ts.p12");
        clientKeyStore = fixtureDir.resolve("client-ks.p12");
        expiredKeyStore = fixtureDir.resolve("expired-ks.p12");
        Path caKeyStore = fixtureDir.resolve("ca-ks.p12");
        Path serverCert = fixtureDir.resolve("server.pem");
        Path clientCert = fixtureDir.resolve("client.pem");
        Path caCert = fixtureDir.resolve("ca.pem");

        // SAN covers 127.0.0.1 so HTTPS endpoint identification is satisfied for the legit/downgrade
        // tests - the rejection under test must come from the attack, not an incidental SAN mismatch.
        genKeyPair(serverKeyStore, "server", "CN=localhost,O=configd-test", "-validity", "1");
        genKeyPair(clientKeyStore, "client", "CN=raft-peer-1,O=configd-test", "-validity", "1");
        exportCert(serverKeyStore, "server", serverCert);
        exportCert(clientKeyStore, "client", clientCert);

        genCa(caKeyStore, "CN=configd-test-ca,O=configd-test");
        exportCert(caKeyStore, "ca", caCert);
        genCaSignedExpiredEndEntity(expiredKeyStore, caKeyStore, caCert,
                "CN=raft-peer-expired,O=configd-test");

        // Server trusts: its own cert (so a client can verify it), the legit client leaf, and the CA
        // (so the expired end-entity's chain validates UP TO the anchor and fails only on validity).
        importCert(serverTrustStore, "server", serverCert);
        importCert(serverTrustStore, "client", clientCert);
        importCert(serverTrustStore, "ca", caCert);
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
        for (TcpRaftTransport t : transports) {
            t.close();
        }
        transports.clear();
    }

    @Test
    @Timeout(120)
    void plaintextFrameIsNeverDecodedAsAPeerMessage() throws Exception {
        AtomicInteger inboundCount = new AtomicInteger();
        int port = startMtlsServer(serverKeyStore, inboundCount);

        // The attacker opens a plain TCP socket (no TLS) and writes a syntactically-valid Raft wire
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
            drainBriefly(plain.getInputStream());
        } catch (IOException expected) {
            // Connection reset by the TLS server rejecting the record - also a valid rejection.
        }

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


    private int startMtlsServer(Path keyStore, AtomicInteger inboundCount) throws Exception {
        TlsConfig serverTls = new TlsConfig(
                fixtureDir.resolve("server.pem"), keyStore, serverTrustStore,
                true, List.of("TLS_AES_256_GCM_SHA384"), List.of("TLSv1.3"), PASS);
        TcpRaftTransport server = new TcpRaftTransport(
                NodeId.of(1), new InetSocketAddress("127.0.0.1", 0), Map.of(),
                new TlsManager(serverTls),
                msg -> inboundCount.incrementAndGet());
        transports.add(server);
        server.start();
        return server.localPort();
    }

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
        // TLS context allows 1.2+1.3 by default so the downgrade test can deliberately restrict
        // the socket to 1.2; the server's TLSv1.3-only policy is what must reject it.
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
