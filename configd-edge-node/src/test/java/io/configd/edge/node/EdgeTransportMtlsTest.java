package io.configd.edge.node;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.observability.MetricsRegistry;
import io.configd.server.fanout.FanOutServer;
import io.configd.server.fanout.RegistryFanOutSessionMetrics;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
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
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Edge-CLIENT mTLS tests (the server half is {@code FanOutServerMtlsTest}): the edge node
 * presents its client certificate over the shared {@code TlsConfig}/{@code TlsManager} stack
 * against a live mTLS {@link FanOutServer}.
 *
 * <ul>
 *   <li><b>trusted client cert</b> - handshake + SUBSCRIBE complete; the edge applies a
 *       published notification (functional, not just connected);</li>
 *   <li><b>untrusted (rogue) client cert</b> - the server rejects; the edge never subscribes
 *       (no SUBSCRIBE_OK, no heartbeats, store never advances) while its reconnect machinery
 *       demonstrably keeps trying;</li>
 *   <li><b>untrusted SERVER cert</b> - the CLIENT side rejects (trust-store verification +
 *       endpoint identification); the edge never subscribes.</li>
 * </ul>
 *
 * <p>Keytool keystore generation is hoisted into a once-per-class {@code @BeforeAll} fixture;
 * each test carries a generous method {@link Timeout} for pure hang detection (TLS-1.3
 * rejections may surface on first I/O rather than at handshake, so the negative cases assert
 * "never subscribed within a bounded observation").
 */
class EdgeTransportMtlsTest {

    private static Path fixtureDir;
    private static Path serverKeyStore;
    private static Path serverTrustStore;
    private static Path clientKeyStore;
    private static Path rogueKeyStore;
    private static Path serverCert;
    private static Path clientCert;
    private static final char[] PASS = "changeit".toCharArray();

    @TempDir
    Path tempDir;

    private FanOutServer server;
    private FanOutBuffer buffer;
    private EdgeNodeMain edge;
    private ServerSocket blackhole; // A3-1: accept-then-black-hole endpoint

    @BeforeAll
    static void generateTlsFixture() throws Exception {
        fixtureDir = Files.createTempDirectory("configd-edge-mtls-c2-");
        serverKeyStore = fixtureDir.resolve("server-ks.p12");
        serverTrustStore = fixtureDir.resolve("server-ts.p12");
        clientKeyStore = fixtureDir.resolve("client-ks.p12");
        rogueKeyStore = fixtureDir.resolve("rogue-ks.p12");
        serverCert = fixtureDir.resolve("server.pem");
        clientCert = fixtureDir.resolve("client.pem");

        genKeyPair(serverKeyStore, "server", "CN=localhost,O=configd-test");
        genKeyPair(clientKeyStore, "client", "CN=edge-client-1,O=configd-test");
        genKeyPair(rogueKeyStore, "rogue", "CN=rogue-edge,O=attacker");
        exportCert(serverKeyStore, "server", serverCert);
        exportCert(clientKeyStore, "client", clientCert);
        // The shared trust store: trusts the legit client (server-side admission) and the
        // server (client-side verification). The rogue cert is in NEITHER direction.
        importCert(serverTrustStore, "client", clientCert);
        importCert(serverTrustStore, "server", serverCert);
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
        if (blackhole != null) {
            try {
                blackhole.close(); // the accept thread exits on the resulting SocketException
            } catch (IOException ignored) {
                // best-effort
            }
            blackhole = null;
        }
    }

    // Tests

    @Test
    @Timeout(120)
    void trustedClientCertSubscribesAndApplies() throws Exception {
        int port = startMtlsServer(serverKeyStore);
        edge = startEdge(port, tlsManager(clientCert, clientKeyStore, serverTrustStore));

        // Functional proof, not just a completed handshake: a published notification
        // reaches the edge store through the mTLS stream.
        buffer.publish(notification(1, "svc/k", "v1"));
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (edge.core().currentVersion() < 1) {
            if (System.nanoTime() > deadline) {
                fail("trusted mTLS edge did not apply the published notification");
            }
            Thread.onSpinWait();
        }
        assertEquals("v1", new String(edge.core().get("svc/k").value(), StandardCharsets.UTF_8));
    }

    @Test
    @Timeout(120)
    void untrustedClientCertIsRejectedByTheServer() throws Exception {
        int port = startMtlsServer(serverKeyStore);
        // The rogue presents a cert the server does not trust. The edge must never
        // complete a SUBSCRIBE — observed as: reconnect attempts accumulate while the
        // session never sees SUBSCRIBE_OK (mode stays null), no heartbeats, store at 0.
        edge = startEdge(port, tlsManager(serverCert, rogueKeyStore, serverTrustStore));

        awaitReconnectAttempts(edge, 3);
        assertNull(edge.core().mode(), "a rejected client must never receive SUBSCRIBE_OK");
        assertEquals(0, edge.core().heartbeatsObserved());
        assertEquals(0, edge.core().currentVersion());
    }

    @Test
    @Timeout(120)
    void untrustedServerCertIsRejectedByTheClient() throws Exception {
        // The server presents the ROGUE identity; the edge's trust store does not contain
        // it -> the CLIENT side must refuse (trust verification + endpoint identification),
        // never subscribe.
        int port = startMtlsServer(rogueKeyStore);
        edge = startEdge(port, tlsManager(clientCert, clientKeyStore, serverTrustStore));

        awaitReconnectAttempts(edge, 3);
        assertNull(edge.core().mode(), "the client must not talk to an untrusted server");
        assertEquals(0, edge.core().currentVersion());
    }

    @Test
    @Timeout(120)
    void blackholedEndpointHandshakeTimesOutAndEdgeKeepsRetrying() throws Exception {
        // An endpoint that completes the TCP accept but NEVER performs the TLS handshake
        // (never reads/writes, holds the socket open). The ONLY mechanism that lets the edge
        // abandon such a peer is HANDSHAKE_TIMEOUT_MS (the setSoTimeout around
        // SSLSocket.startHandshake in EdgeStreamClient.createClientSocket). Without it,
        // startHandshake() blocks forever and the edge wedges on the first peer — never
        // failing over, never retrying.
        //
        // Proof the bound bites: the reconnect counter ADVANCES (the edge timed out of a
        // black-holed handshake and looped) while it never subscribes. If the timeout did not
        // bite, the session thread would be parked in startHandshake and the counter could not
        // advance — awaitReconnectAttempts would fail. Mutation: drop
        // setSoTimeout(HANDSHAKE_TIMEOUT_MS) -> this test hangs to its @Timeout.
        int port = startBlackholeServer();
        edge = startEdge(port, tlsManager(clientCert, clientKeyStore, serverTrustStore));

        awaitReconnectAttempts(edge, 2); // got PAST a black-holed handshake at least twice
        assertNull(edge.core().mode(), "a black-holed handshake must never reach SUBSCRIBE_OK");
        assertEquals(0, edge.core().heartbeatsObserved(), "no heartbeats from a peer that never handshakes");
        assertEquals(0, edge.core().currentVersion(), "store never advances behind a dead endpoint");
    }

    // Fixture plumbing

    /**
     * Binds a plain TCP listener whose accept loop holds every accepted connection OPEN without
     * ever performing the TLS handshake (no read, no write, no close), so the client blocks in
     * {@code startHandshake()} until {@code HANDSHAKE_TIMEOUT_MS} bites — rather than getting
     * an immediate EOF. Returns the listening port.
     */
    private int startBlackholeServer() throws IOException {
        blackhole = new ServerSocket();
        blackhole.bind(new InetSocketAddress("127.0.0.1", 0), 128);
        ServerSocket ss = blackhole;
        List<Socket> held = new CopyOnWriteArrayList<>(); // keep accepted sockets open so they aren't GC'd
        Thread.ofVirtual().name("a3-1-blackhole-accept").start(() -> {
            try {
                while (!ss.isClosed()) {
                    held.add(ss.accept()); // accept and HOLD: never read/write/close
                }
            } catch (IOException ignored) {
                // ServerSocket closed at teardown — exit the loop
            }
        });
        return blackhole.getLocalPort();
    }

    private int startMtlsServer(Path keyStore) throws Exception {
        TlsConfig serverTls = new TlsConfig(
                serverCert, keyStore, serverTrustStore,
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
        // The wire edgeId deliberately differs from the cert DN: the server binds the
        // cert principal authoritatively (the FanOutServer identity decision).
        EdgeNodeConfig cfg = new EdgeNodeConfig(
                "wire-claimed-id",
                List.of(InetSocketAddress.createUnresolved("127.0.0.1", port)),
                0, tempDir.resolve("edge-" + port), null /* unsigned notifications */,
                List.of(), null, null, null,
                50L, EdgeNodeConfig.DEFAULT_HEARTBEAT_SILENCE_FACTOR, EdgeNodeConfig.DEFAULT_POISON_MAX_RETRIES);
        return EdgeNodeMain.start(cfg, tls);
    }

    private static TlsManager tlsManager(Path cert, Path keyStore, Path trustStore)
            throws Exception {
        return new TlsManager(new TlsConfig(cert, keyStore, trustStore,
                true, List.of("TLS_AES_256_GCM_SHA384"), List.of("TLSv1.3"), PASS));
    }

    private static CommitNotification notification(long seq, String key, String value) {
        return new CommitNotification(seq, System.currentTimeMillis(),
                new ConfigDelta(seq - 1, seq, List.of(
                        new ConfigMutation.Put(key, value.getBytes(StandardCharsets.UTF_8)))));
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

    // ---- keytool fixture builders (the FanOutServerMtlsTest pattern) ----

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
