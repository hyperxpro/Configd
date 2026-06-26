package io.configd.netty;

import io.configd.common.NodeId;
import io.configd.transport.RaftWireProtocol;
import io.configd.transport.TlsConfig;
import io.configd.transport.TlsManager;
import io.netty.handler.ssl.SslHandler;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Red-team finding (red/green regression): the Netty consensus transport must apply the SHARED bounded
 * TLS handshake timeout {@link RaftWireProtocol#HANDSHAKE_TIMEOUT_MS} the JDK {@code TcpRaftTransport}
 * enforces (via {@code setSoTimeout(...)} around {@code startHandshake()}) — DR-N16's "both transports
 * apply the same numbers". Before the fix, {@code new SslHandler(engine)} used Netty's default
 * (10000 ms), so a peer that completed the TCP connect but stalled mid-handshake held the slot ~10 s
 * instead of ~2 s. This test pins the value on BOTH the server-mode and client-mode handlers:
 * <ul>
 *   <li><b>RED</b> (pre-fix): {@code getHandshakeTimeoutMillis() == 10000} (Netty default).</li>
 *   <li><b>GREEN</b> (post-fix): {@code == RaftWireProtocol.HANDSHAKE_TIMEOUT_MS == 2000}.</li>
 * </ul>
 */
@Timeout(120) // RR-094: generous hang budget for the once-per-class keytool fixture on the 2-vCPU box
class NettyRaftTransportHandshakeTimeoutTest {

    private static Path dir;
    private static Path keyStore;
    private static Path trustStore;
    private static Path cert;

    @BeforeAll
    static void fixture() throws Exception {
        dir = Files.createTempDirectory("configd-raft-hs-timeout-");
        keyStore = dir.resolve("ks.p12");
        trustStore = dir.resolve("ts.p12");
        cert = dir.resolve("cert.pem");
        runKeytool("keytool", "-genkeypair", "-alias", "node", "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-validity", "1", "-dname", "CN=localhost,O=configd-test",
                "-ext", "san=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", keyStore.toString(), "-storepass", "changeit", "-keypass", "changeit");
        runKeytool("keytool", "-exportcert", "-alias", "node", "-keystore", keyStore.toString(),
                "-storepass", "changeit", "-rfc", "-file", cert.toString());
        runKeytool("keytool", "-importcert", "-alias", "node", "-file", cert.toString(),
                "-keystore", trustStore.toString(), "-storepass", "changeit", "-storetype", "PKCS12", "-noprompt");
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        }
    }

    @Test
    void bothSslHandlersUseTheSharedBoundedHandshakeTimeoutNotNettyDefault() throws Exception {
        // Full ctor with the fixture's "changeit" store password (TlsConfig.mtls() assumes an empty
        // password; our keytool keystores use changeit).
        TlsConfig cfg = new TlsConfig(cert, keyStore, trustStore, true,
                java.util.List.of("TLS_AES_256_GCM_SHA384"), java.util.List.of("TLSv1.3"),
                "changeit".toCharArray());
        TlsManager tls = new TlsManager(cfg);
        NettyRaftTransport transport = new NettyRaftTransport(
                NodeId.of(1), new InetSocketAddress("127.0.0.1", 0), Map.of(), tls, null);
        try {
            // Not started — the SslHandler factories only need the TlsManager, not a bound socket.
            SslHandler server = transport.newServerSslHandler();
            SslHandler client = transport.newClientSslHandler(new InetSocketAddress("127.0.0.1", 9999));

            assertEquals(RaftWireProtocol.HANDSHAKE_TIMEOUT_MS, server.getHandshakeTimeoutMillis(),
                    "server SslHandler must use the shared bounded handshake timeout (DR-N16), not Netty's 10s default");
            assertEquals(RaftWireProtocol.HANDSHAKE_TIMEOUT_MS, client.getHandshakeTimeoutMillis(),
                    "client SslHandler must use the shared bounded handshake timeout (DR-N16), not Netty's 10s default");
            // Belt-and-braces: the literal value the JDK transport applies (and NOT the 10000ms default).
            assertEquals(2_000L, server.getHandshakeTimeoutMillis());
            assertEquals(2_000L, client.getHandshakeTimeoutMillis());
        } finally {
            transport.close(); // no-op when never started; releases nothing harmful
        }
    }

    private static void runKeytool(String... command) throws Exception {
        int rc = new ProcessBuilder(command).redirectErrorStream(true).inheritIO().start().waitFor();
        assertEquals(0, rc, "keytool failed: " + String.join(" ", command));
    }
}
