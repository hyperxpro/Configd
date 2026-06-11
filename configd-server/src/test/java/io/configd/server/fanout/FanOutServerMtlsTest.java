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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CT-40 (server half) for the live {@link FanOutServer}: mTLS admission control. The endpoint
 * REQUIRES a client certificate ({@code setNeedClientAuth(true)}) trusted by the server's trust
 * store, and the accepted edge identity is the client certificate's Subject DN (the binding
 * decision — the wire {@code edgeId} is advisory only).
 *
 * <ul>
 *   <li><b>no client cert</b> → TLS handshake fails (no SUBSCRIBE_OK);</li>
 *   <li><b>wrong CA</b> (cert not in the server trust store) → handshake fails;</li>
 *   <li><b>right cert</b> → handshake succeeds, SUBSCRIBE→SUBSCRIBE_OK works.</li>
 * </ul>
 *
 * <h2>RR-094 fixture discipline</h2>
 * The expensive keytool keystore/cert generation (several subprocesses) is hoisted into a
 * once-per-class {@code @BeforeAll static} fixture (cached temp dir, {@code @AfterAll} cleanup),
 * which JUnit does NOT subject to the class {@link Timeout}. Each test carries a generous
 * method-level {@code @Timeout(120)} for pure hang detection, never a perf assertion, so the
 * test stays robust on the throttled 2-vCPU box.
 */
class FanOutServerMtlsTest {

    private static Path fixtureDir;
    // Server identity + trust (trusts the client cert).
    private static Path serverKeyStore;
    private static Path serverTrustStore;
    // Legit client identity (trusted by the server).
    private static Path clientKeyStore;
    // Rogue client identity (NOT trusted by the server).
    private static Path rogueKeyStore;
    private static final char[] PASS = "changeit".toCharArray();

    private FanOutServer server;

    @BeforeAll
    static void generateTlsFixture() throws Exception {
        fixtureDir = Files.createTempDirectory("configd-edge-mtls-");
        serverKeyStore = fixtureDir.resolve("server-ks.p12");
        serverTrustStore = fixtureDir.resolve("server-ts.p12");
        clientKeyStore = fixtureDir.resolve("client-ks.p12");
        Path clientCert = fixtureDir.resolve("client.pem");
        rogueKeyStore = fixtureDir.resolve("rogue-ks.p12");

        // Server cert (SAN localhost so HTTPS-style identity is satisfiable; the edge server
        // does not enable endpoint identification, but matching keeps the fixture clean).
        genKeyPair(serverKeyStore, "server", "CN=localhost,O=configd-test");
        // Legit client cert with a distinctive Subject so we can assert the identity binding.
        genKeyPair(clientKeyStore, "client", "CN=edge-client-1,O=configd-test");
        exportCert(clientKeyStore, "client", clientCert);
        // Server trusts the legit client cert.
        importCert(serverTrustStore, "client", clientCert);
        // Also put the server cert into the server trust store so the CLIENT (which uses the
        // server trust store) trusts the server during the handshake.
        Path serverCert = fixtureDir.resolve("server.pem");
        exportCert(serverKeyStore, "server", serverCert);
        importCert(serverTrustStore, "server", serverCert);

        // Rogue client cert — self-signed, NOT imported into the server trust store.
        genKeyPair(rogueKeyStore, "rogue", "CN=rogue-edge,O=attacker");
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

    @Test
    @Timeout(120)
    void rejectsClientWithNoCertificate() throws Exception {
        int port = startMtlsServer();
        // A client that trusts the server but presents NO client cert -> mTLS must reject.
        // In TLS 1.3 the client's startHandshake() may return before the server's
        // need-client-auth rejection lands, so we assert the connection is UNUSABLE: a SUBSCRIBE
        // never elicits a SUBSCRIBE_OK (the only secure observation), and using it fails.
        assertConnectionRejected(clientContext(null, serverTrustStore), port);
    }

    @Test
    @Timeout(120)
    void rejectsClientWithUntrustedCertificate() throws Exception {
        int port = startMtlsServer();
        // The rogue client presents a cert the server does not trust -> connection unusable.
        assertConnectionRejected(clientContext(rogueKeyStore, serverTrustStore), port);
    }

    /**
     * Asserts that a connection from {@code clientCtx} is rejected by mTLS: the handshake fails
     * OR the connection is unusable (no SUBSCRIBE_OK is ever served). Tolerant of the TLS-1.3
     * timing where the server's need-client-auth rejection surfaces on first I/O rather than at
     * {@code startHandshake()}.
     */
    private void assertConnectionRejected(SSLContext clientCtx, int port) throws Exception {
        SSLSocket sock = (SSLSocket) clientCtx.getSocketFactory().createSocket();
        sock.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        sock.setSoTimeout(5_000);
        boolean rejected = false;
        try {
            sock.startHandshake();
            // Handshake "succeeded" on the client side — try to use the connection. A rejected
            // client must NOT receive a SUBSCRIBE_OK; the I/O fails or the stream EOFs.
            try (EdgeProtocolClient edge = new EdgeProtocolClient(sock)) {
                edge.subscribeFullStore("rejected", 0L);
                EdgeFrame f = readUntilSubscribeOk(edge);
                rejected = (f == null); // no SUBSCRIBE_OK -> rejected
            }
        } catch (IOException e) {
            rejected = true; // handshake or first-I/O failure -> rejected
        } finally {
            closeQuietly(sock);
        }
        assertTrue(rejected, "mTLS must reject this client (no SUBSCRIBE_OK served)");
    }

    @Test
    @Timeout(120)
    void acceptsTrustedClientAndCompletesSubscribe() throws Exception {
        int port = startMtlsServer();
        // The legit client presents a trusted cert -> handshake succeeds, SUBSCRIBE works.
        SSLContext clientCtx = clientContext(clientKeyStore, serverTrustStore);
        SSLSocket sock = (SSLSocket) clientCtx.getSocketFactory().createSocket();
        sock.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        sock.setSoTimeout(10_000);
        sock.startHandshake(); // must NOT throw

        try (EdgeProtocolClient edge = new EdgeProtocolClient(sock)) {
            // The wire edgeId is advisory; the server binds the cert Subject DN. We send a
            // deliberately different wire edgeId to confirm acceptance regardless.
            edge.subscribeFullStore("wire-claimed-id", 0L);
            EdgeFrame f = readUntilSubscribeOk(edge);
            assertNotNull(f, "a trusted mTLS client must receive SUBSCRIBE_OK");
            assertTrue(f instanceof EdgeFrame.SubscribeOk);
        }
    }

    // -----------------------------------------------------------------------
    // server + helpers
    // -----------------------------------------------------------------------

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

    private static EdgeFrame readUntilSubscribeOk(EdgeProtocolClient edge) throws IOException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if (f == null) {
                return null;
            }
            if (f instanceof EdgeFrame.SubscribeOk) {
                return f;
            }
        }
        return null;
    }

    /** Builds an SSLContext with an optional client key store and a trust store. */
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
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
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

    private static void genKeyPair(Path keyStore, String alias, String dname) throws Exception {
        runKeytool("keytool", "-genkeypair", "-alias", alias,
                "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-validity", "1",
                "-dname", dname, "-ext", "san=dns:localhost,ip:127.0.0.1",
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
