package io.configd.client.edge;

import io.configd.client.AuthFailedException;
import io.configd.client.ConfigdClientConfig;
import io.configd.client.ConfigdException;
import io.configd.client.HostileServerLimits;
import io.configd.client.edge.session.EdgeConnectionState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The TLS contract over a real {@code SSLSocket}: mTLS authenticates at the handshake (no {@code AUTH}
 * frame), the server endpoint is verified ({@code HTTPS} SAN — a mismatch fails closed), and the client never
 * hangs on nor interprets pre-handshake bytes (the libpq CVE lesson). These are the few tests that pay for
 * real TLS; the auth/framing logic is covered over plaintext elsewhere.
 */
@Timeout(60)
class EdgeTlsTest {

    @TempDir
    static Path certDir;
    static CertFixtures certs;

    @BeforeAll
    static void generateCerts() throws Exception {
        certs = new CertFixtures(certDir);
    }

    @Test
    void mtlsConnectReachesAuthenticatedWithNoAuthFrame() throws Exception {
        try (MockEdgeServer server = MockEdgeServer.startTls(
                certs.serverContext(false), true, false, MockEdgeServer.Conn::parkUntilClosed)) {
            ConfigdClientConfig config = ConfigdClientConfig.builder()
                    .endpoint("localhost", server.port())
                    .tls(certs.clientMutualTls())
                    .build();
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                assertEquals(AuthMode.MTLS, client.authMode());
                client.connectAndAuthenticate().get(30, TimeUnit.SECONDS);
                assertEquals(EdgeConnectionState.AUTHENTICATED, client.state());
                assertEquals(0, server.authFrameCount(), "mTLS presents no AUTH frame (F6A-4)");
            }
        }
    }

    @Test
    void serverEndpointIdentityMismatchFailsClosed() throws Exception {
        // The server presents a cert whose SAN is "wronghost", but the client connects to "localhost": the
        // HTTPS endpoint check must reject it even though the cert chains to a trusted anchor.
        try (MockEdgeServer server = MockEdgeServer.startTls(
                certs.serverContext(true), false, false, MockEdgeServer.Conn::parkUntilClosed)) {
            ConfigdClientConfig config = ConfigdClientConfig.builder()
                    .endpoint("localhost", server.port())
                    .tls(certs.clientTrustOnly())
                    .build();
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                ExecutionException ee = assertThrows(ExecutionException.class,
                        () -> client.connect().get(30, TimeUnit.SECONDS));
                assertInstanceOf(AuthFailedException.class, ee.getCause());
                assertNotEquals(EdgeConnectionState.AUTHENTICATED, client.state());
            }
        }
    }

    @Test
    void handshakeSlowLorisTimesOutDoesNotHang() throws Exception {
        // A peer that accepts TCP then never speaks TLS: the client's bounded handshake must time out.
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> holdOpen())) {
            ConfigdClientConfig config = ConfigdClientConfig.builder()
                    .endpoint("localhost", server.port())
                    .tls(certs.clientTrustOnly())
                    .limits(shortHandshakeLimits())
                    .build();
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                ExecutionException ee = assertThrows(ExecutionException.class,
                        () -> client.connect().get(30, TimeUnit.SECONDS));
                assertInstanceOf(ConfigdException.class, ee.getCause(), "a clean, classified failure — not a hang");
                assertNotEquals(EdgeConnectionState.AUTHENTICATED, client.state());
            }
        }
    }

    @Test
    void preHandshakeBytesAreNotInterpretedAsFrames() throws Exception {
        // A peer that writes plaintext garbage BEFORE any TLS: the client's TLS layer consumes it during the
        // handshake and fails closed — it is never decoded as an edge frame (libpq CVE-2021-23214/23222).
        byte[] garbage = "NOT-A-TLS-RECORD-just-some-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (MockEdgeServer server = MockEdgeServer.startPlaintext(conn -> {
            conn.sendRaw(garbage);
            holdOpen();
        })) {
            ConfigdClientConfig config = ConfigdClientConfig.builder()
                    .endpoint("localhost", server.port())
                    .tls(certs.clientTrustOnly())
                    .limits(shortHandshakeLimits())
                    .build();
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                ExecutionException ee = assertThrows(ExecutionException.class,
                        () -> client.connect().get(30, TimeUnit.SECONDS));
                // A malformed TLS record surfaces as a handshake (auth) failure, never a decoded frame.
                assertInstanceOf(ConfigdException.class, ee.getCause());
                assertNotEquals(EdgeConnectionState.AUTHENTICATED, client.state());
            }
        }
    }

    private static HostileServerLimits shortHandshakeLimits() {
        HostileServerLimits d = HostileServerLimits.defaults();
        return new HostileServerLimits(d.maxFrameBytes(), 1_000, 800, d.readIdleDeadlineMs(),
                d.maxSnapshotTotalBytes(), d.maxSnapshotChunks());
    }

    private static void holdOpen() {
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
