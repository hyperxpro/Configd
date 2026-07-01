package io.configd.edge.node;

import io.configd.common.Clock;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.observability.MetricsRegistry;
import io.configd.server.fanout.FanOutServer;
import io.configd.server.fanout.RegistryFanOutSessionMetrics;
import io.configd.store.ConfigSnapshot;
import io.configd.transport.TlsConfig;
import io.configd.transport.TlsManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Negative mTLS test for the DATA plane: wrong-SAN / identity rejection on the CLIENT side. This
 * complements {@code EdgeTransportMtlsTest.untrustedServerCertIsRejectedByTheClient} (which
 * rejects a server cert NOT in the trust store): here the server cert IS trusted, but its SAN does
 * NOT cover the host the edge connects to. HTTPS endpoint identification in
 * {@code EdgeStreamClient.createClientSocket} must reject it — a trusted CA alone is not enough.
 *
 * <p>Data-plane analogue of
 * {@code TcpRaftTransportTest.find0051_clientHandshakeRejectsCertWithWrongHostname}; together
 * they prove SAN/identity verification on both planes.
 *
 * <p>The edge connects to {@code 127.0.0.1}. The server presents a cert whose SAN is
 * {@code dns:other-host.invalid} only — no {@code ip:127.0.0.1}, no {@code dns:localhost}. The
 * cert is imported into the edge's trust store so trust-anchor verification PASSES and the ONLY
 * remaining gate is endpoint identification.
 *
 * <p>The rejection surfaces on first I/O under TLS 1.3, so we assert the edge never subscribes
 * (mode stays null, store never advances) while its reconnect machinery keeps trying.
 */
class EdgeTransportSanMismatchTest {

    private static Path fixtureDir;
    private static Path serverKeyStore;   // SAN = other-host.invalid only (does NOT cover 127.0.0.1)
    private static Path clientKeyStore;
    private static Path serverTrustStore; // trusts the server cert AND the legit client cert
    private static Path serverCert;
    private static Path clientCert;
    private static final char[] PASS = "changeit".toCharArray();

    @TempDir
    Path tempDir;

    private FanOutServer server;
    private FanOutBuffer buffer;
    private EdgeNodeMain edge;

    @BeforeAll
    static void generateTlsFixture() throws Exception {
        fixtureDir = Files.createTempDirectory("configd-edge-san-mismatch-");
        serverKeyStore = fixtureDir.resolve("server-ks.p12");
        clientKeyStore = fixtureDir.resolve("client-ks.p12");
        serverTrustStore = fixtureDir.resolve("server-ts.p12");
        serverCert = fixtureDir.resolve("server.pem");
        clientCert = fixtureDir.resolve("client.pem");

        // Server SAN deliberately does NOT cover 127.0.0.1 (the host the edge connects to) — the
        // ONLY thing that should fail is HTTPS endpoint identification on the client.
        genKeyPair(serverKeyStore, "server", "CN=other-host.invalid,O=configd-test",
                "san=dns:other-host.invalid");
        // Legit client cert (so server-side need-client-auth admits the edge; the rejection under
        // test is client-side identity, not the client credential).
        genKeyPair(clientKeyStore, "client", "CN=edge-client-1,O=configd-test",
                "san=dns:localhost,ip:127.0.0.1");

        exportCert(serverKeyStore, "server", serverCert);
        exportCert(clientKeyStore, "client", clientCert);
        // The shared trust store trusts the SERVER cert (so trust-anchor verification PASSES and the
        // SAN check is the sole gate) and the legit client (server-side admission).
        importCert(serverTrustStore, "server", serverCert);
        importCert(serverTrustStore, "client", clientCert);
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
        if (edge != null) {
            edge.shutdown();
            edge = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @Test
    @Timeout(120)
    void serverCertWithWrongSanIsRejectedByTheClient() throws Exception {
        // The server presents a TRUSTED cert whose SAN does not cover 127.0.0.1; the edge connects
        // to 127.0.0.1. Endpoint identification must reject it -> the edge never subscribes,
        // while its reconnect machinery demonstrably keeps trying (proving the rejection is at the
        // handshake, not a config that simply never attempts a connection).
        int port = startMtlsServer();
        edge = startEdge(port, tlsManager(clientCert, clientKeyStore, serverTrustStore));

        awaitReconnectAttempts(edge, 3);
        assertNull(edge.core().mode(),
                "an edge must not subscribe to a server whose cert SAN does not cover the connect host");
        assertEquals(0, edge.core().heartbeatsObserved());
        assertEquals(0, edge.core().currentVersion());
    }

    // Fixture plumbing

    private int startMtlsServer() throws Exception {
        TlsConfig serverTls = new TlsConfig(
                serverCert, serverKeyStore, serverTrustStore,
                true, List.of("TLS_AES_256_GCM_SHA384"), List.of("TLSv1.3"), PASS);
        buffer = new FanOutBuffer(10_000);
        server = new FanOutServer(
                new InetSocketAddress("127.0.0.1", 0), new TlsManager(serverTls),
                buffer, new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY),
                FanOutConfig.defaults(), FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                new RegistryFanOutSessionMetrics(new MetricsRegistry()), Clock.system());
        server.start();
        return server.localPort();
    }

    private EdgeNodeMain startEdge(int port, TlsManager tls) {
        EdgeNodeConfig cfg = new EdgeNodeConfig(
                "wire-claimed-id",
                List.of(InetSocketAddress.createUnresolved("127.0.0.1", port)),
                0, tempDir.resolve("edge-" + port), null /* unsigned notifications */,
                List.of(), null, null, null,
                50L, EdgeNodeConfig.DEFAULT_HEARTBEAT_SILENCE_FACTOR, EdgeNodeConfig.DEFAULT_POISON_MAX_RETRIES);
        return EdgeNodeMain.start(cfg, tls);
    }

    private static TlsManager tlsManager(Path cert, Path keyStore, Path trustStore) throws Exception {
        return new TlsManager(new TlsConfig(cert, keyStore, trustStore,
                true, List.of("TLS_AES_256_GCM_SHA384"), List.of("TLSv1.3"), PASS));
    }

    /** Waits until the edge's reconnect counter shows >= n attempts (it IS retrying). */
    private static void awaitReconnectAttempts(EdgeNodeMain edge, int n) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            var metric = edge.metricsRegistry().counter("edge.reconnects");
            if (metric.get() >= n) {
                return;
            }
            Thread.onSpinWait();
        }
        fail("edge did not attempt " + n + " reconnects within the deadline");
    }

    // Keytool fixture builders (SAN passed per cert)

    private static void genKeyPair(Path keyStore, String alias, String dname, String sanExt)
            throws Exception {
        runKeytool("keytool", "-genkeypair", "-alias", alias,
                "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-validity", "1",
                "-dname", dname, "-ext", sanExt,
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
