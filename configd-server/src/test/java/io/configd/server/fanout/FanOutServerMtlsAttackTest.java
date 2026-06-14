package io.configd.server.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigSnapshot;
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
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session-7 NEGATIVE mTLS tests for the DATA-plane {@link FanOutServer}, complementing
 * {@link FanOutServerMtlsTest} (which covers no-cert / untrusted-CA / trusted-accept). Each test
 * performs an attack and asserts the edge endpoint REJECTS it (charter §2.1: verified only by a
 * passing attack test, never by reading config):
 *
 * <ul>
 *   <li><b>plaintext</b> — a plain {@link java.net.Socket} sending a syntactically-valid SUBSCRIBE
 *       to the TLS-only edge port never receives a {@code SUBSCRIBE_OK} (the bytes are a malformed
 *       TLS record, dropped before any edge-protocol decode);</li>
 *   <li><b>expired client cert</b> — a client whose certificate is already past {@code notAfter}
 *       (a CA-signed end-entity, {@code -gencert -startdate -2d -validity 1}) fails server-side PKIX
 *       path validation; the connection is unusable;</li>
 *   <li><b>version downgrade</b> — a client offering ONLY TLSv1.2 against the TLSv1.3-only server
 *       fails the handshake; nothing downgrades below TLSv1.3 (charter §5 version policy).</li>
 * </ul>
 *
 * <h2>Observation discipline</h2>
 * Reuses {@link FanOutServerMtlsTest}'s {@code assertConnectionRejected} pattern: the only secure
 * observation is "no SUBSCRIBE_OK is ever served", tolerant of the TLS-1.3 timing where the
 * server's rejection surfaces on first I/O rather than at {@code startHandshake()}.
 *
 * <h2>Why the expired-cert case needs a CA (S7 finding)</h2>
 * Configd's production trust model ({@code deploy/compose/setup-secrets.sh}) imports each peer's
 * self-signed leaf directly as a trust anchor; under RFC 5280 §6.1 an anchor's own validity is not
 * checked, so an expired self-signed leaf is accepted. To prove the stack does not <em>disable</em>
 * expiry validation, the expired client here is a CA-signed end-entity validated against a CA-only
 * anchor (where JSSE enforces {@code notAfter}). The leaf-as-anchor blind spot + its compensating
 * control are recorded in {@code docs/session-7/transport-security.md} for the S7.5 manifest.
 *
 * <h2>RR-094 fixture discipline</h2>
 * keytool subprocesses are hoisted into a once-per-class {@code @BeforeAll static} fixture; each
 * test carries a generous method {@code @Timeout(120)} for pure hang detection on the 2-vCPU box.
 */
class FanOutServerMtlsAttackTest {

    private static Path fixtureDir;
    private static Path serverKeyStore;
    private static Path serverTrustStore;
    private static Path clientKeyStore;   // legit, trusted — used by the downgrade test
    private static Path expiredKeyStore;  // CA-signed end-entity, expired validity window
    private static final char[] PASS = "changeit".toCharArray();

    private FanOutServer server;

    @BeforeAll
    static void generateTlsFixture() throws Exception {
        fixtureDir = Files.createTempDirectory("configd-fanout-mtls-attack-");
        serverKeyStore = fixtureDir.resolve("server-ks.p12");
        serverTrustStore = fixtureDir.resolve("server-ts.p12");
        clientKeyStore = fixtureDir.resolve("client-ks.p12");
        expiredKeyStore = fixtureDir.resolve("expired-ks.p12");
        Path caKeyStore = fixtureDir.resolve("ca-ks.p12");
        Path serverCert = fixtureDir.resolve("server.pem");
        Path clientCert = fixtureDir.resolve("client.pem");
        Path caCert = fixtureDir.resolve("ca.pem");

        genKeyPair(serverKeyStore, "server", "CN=localhost,O=configd-test", "-validity", "1");
        genKeyPair(clientKeyStore, "client", "CN=edge-client-1,O=configd-test", "-validity", "1");
        exportCert(serverKeyStore, "server", serverCert);
        exportCert(clientKeyStore, "client", clientCert);

        // CA + CA-signed expired END-ENTITY (see the class javadoc: a self-signed expired LEAF would
        // be accepted as a trust anchor, so the CA layer is what makes notAfter enforceable).
        genCa(caKeyStore, "CN=configd-test-ca,O=configd-test");
        exportCert(caKeyStore, "ca", caCert);
        genCaSignedExpiredEndEntity(expiredKeyStore, caKeyStore, caCert,
                "CN=edge-expired,O=configd-test");

        // Server trusts itself, the legit client leaf, and the CA (so the expired end-entity's chain
        // validates UP TO the anchor and fails only on validity).
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
                    // best-effort temp cleanup
                }
            });
        }
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    // -----------------------------------------------------------------------
    // GAP #1 — plaintext connection rejected (data plane)
    // -----------------------------------------------------------------------

    @Test
    @Timeout(120)
    void plaintextSubscribeIsNeverAcknowledged() throws Exception {
        int port = startMtlsServer();
        // A plain (non-TLS) socket speaking the edge protocol. Against the TLS-only edge port the
        // SUBSCRIBE bytes are a malformed TLS record; the server tears the connection down and never
        // serves SUBSCRIBE_OK. We assert exactly that, with bounded timeouts so a silent drop cannot
        // hang the test.
        boolean rejected = false;
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 3_000)) {
            edge.subscribeFullStore("plaintext-attacker", 0L);
            EdgeFrame f = readUntilSubscribeOk(edge, 3);
            rejected = (f == null); // no SUBSCRIBE_OK -> rejected
        } catch (IOException e) {
            rejected = true; // connection reset by the TLS server -> rejected
        }
        assertTrue(rejected,
                "a plaintext SUBSCRIBE must never receive SUBSCRIBE_OK from the TLS-only edge server");
    }

    // -----------------------------------------------------------------------
    // GAP #2 — expired client certificate rejected (data plane)
    // -----------------------------------------------------------------------

    @Test
    @Timeout(120)
    void expiredClientCertificateIsRejected() throws Exception {
        int port = startMtlsServer();
        // The expired client is a CA-signed end-entity; the CA is trusted, so the ONLY reason to
        // reject is the dead validity window. Reuses the FanOutServerMtlsTest unusable-connection
        // discipline.
        assertConnectionRejected(clientContext(expiredKeyStore, serverTrustStore), port, null);
    }

    // -----------------------------------------------------------------------
    // GAP #4 — TLS version downgrade rejected (data plane)
    // -----------------------------------------------------------------------

    @Test
    @Timeout(120)
    void tlsV12OnlyClientIsRejectedByTheTlsV13OnlyServer() throws Exception {
        int port = startMtlsServer();
        // A fully trusted client credential, but the socket offers ONLY TLSv1.2 against the
        // TLSv1.3-only server -> no common protocol -> handshake fails; nothing downgrades.
        assertConnectionRejected(clientContext(clientKeyStore, serverTrustStore), port,
                new String[]{"TLSv1.2"});
    }

    // -----------------------------------------------------------------------
    // Server + helpers (the FanOutServerMtlsTest pattern)
    // -----------------------------------------------------------------------

    /**
     * Asserts a connection from {@code clientCtx} is rejected: the handshake fails OR the connection
     * is unusable (no SUBSCRIBE_OK is ever served). If {@code enabledProtocols} is non-null the
     * socket is restricted to those protocols (the version-downgrade attack). Tolerant of the TLS-1.3
     * timing where the server's rejection surfaces on first I/O.
     */
    private void assertConnectionRejected(SSLContext clientCtx, int port, String[] enabledProtocols)
            throws Exception {
        SSLSocket sock = (SSLSocket) clientCtx.getSocketFactory().createSocket();
        if (enabledProtocols != null) {
            sock.setEnabledProtocols(enabledProtocols);
        }
        sock.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        sock.setSoTimeout(5_000);
        boolean rejected = false;
        try {
            sock.startHandshake();
            try (EdgeProtocolClient edge = new EdgeProtocolClient(sock)) {
                edge.subscribeFullStore("rejected", 0L);
                EdgeFrame f = readUntilSubscribeOk(edge, 4);
                rejected = (f == null);
            }
        } catch (IOException e) {
            rejected = true; // handshake or first-I/O failure -> rejected
        } finally {
            closeQuietly(sock);
        }
        assertTrue(rejected, "mTLS must reject this client (no SUBSCRIBE_OK served)");
    }

    private int startMtlsServer() throws Exception {
        TlsConfig serverTls = new TlsConfig(
                fixtureDir.resolve("server.pem"), serverKeyStore, serverTrustStore,
                true, List.of("TLS_AES_256_GCM_SHA384"), List.of("TLSv1.3"), PASS);
        TlsManager tlsManager = new TlsManager(serverTls);

        FanOutBuffer buffer = new FanOutBuffer(10_000);
        CommitNotificationSource source = buffer;
        ReplaySource replay = new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY);
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(new MetricsRegistry());

        server = new FanOutServer(
                new InetSocketAddress("127.0.0.1", 0), tlsManager, source, replay,
                FanOutConfig.defaults(), FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                metrics, Clock.system());
        server.start();
        return server.localPort();
    }

    /**
     * Reads up to {@code maxFrames} server frames looking for SUBSCRIBE_OK; null if none. Returns
     * null on EOF, SO_TIMEOUT, OR a codec exception: when the server rejects (e.g. responds with a
     * TLS alert record to a plaintext or downgraded client) the bytes the client reads are NOT a
     * valid edge frame, so {@code EdgeFrameCodec} throws — which is, definitionally, "no
     * SUBSCRIBE_OK was served". Catching it here keeps the assertion the secure observation: a
     * SUBSCRIBE_OK is the only thing that proves acceptance.
     */
    private static EdgeFrame readUntilSubscribeOk(EdgeProtocolClient edge, int maxFrames)
            throws IOException {
        for (int i = 0; i < maxFrames; i++) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                return null; // no reply within the SO_TIMEOUT -> not acknowledged
            } catch (EdgeFrameCodec.CodecException e) {
                return null; // server bytes (e.g. a TLS alert) did not decode -> not acknowledged
            }
            if (f == null) {
                return null; // EOF
            }
            if (f instanceof EdgeFrame.SubscribeOk) {
                return f;
            }
        }
        return null;
    }

    /** Builds an SSLContext with a client key store + trust store; TLS (1.2+1.3) context. */
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
        // "TLS" allows 1.2 so the downgrade test can deliberately restrict the socket to 1.2; the
        // server's TLSv1.3-only policy is what must reject it.
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

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // best-effort
        }
    }

    // ---- keytool fixture builders ----

    private static void genKeyPair(Path keyStore, String alias, String dname, String... validity)
            throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>(List.of(
                "keytool", "-genkeypair", "-alias", alias,
                "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA",
                "-dname", dname, "-ext", "san=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", "changeit", "-keypass", "changeit"));
        cmd.addAll(List.of(validity));
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
     * window is already PAST ({@code -gencert -startdate -2d -validity 1}), plus the CA in its chain.
     */
    private static void genCaSignedExpiredEndEntity(Path keyStore, Path caKeyStore, Path caCert,
                                                    String dname) throws Exception {
        Path csr = fixtureDir.resolve("expired.csr");
        Path signed = fixtureDir.resolve("expired-signed.pem");
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
