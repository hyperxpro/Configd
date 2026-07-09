package io.configd.client.edge;

import io.configd.client.ConfigdClientConfig;
import io.configd.client.edge.session.EdgeConnectionState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runner II (client-conforms) for the §06 F9-2 TLS profile: the reference client completes a real mTLS
 * handshake against a server that enables <b>only</b> {@code TLSv1.3} and <b>only</b> the two AEAD cipher
 * suites {@code TLS_AES_256_GCM_SHA384} / {@code TLS_AES_128_GCM_SHA256} — so a successful handshake proves the
 * client speaks exactly that profile (it offers TLSv1.3 and at least one of the two suites; a client that
 * offered neither, or expected TLS 1.2, could not negotiate). The client's profile is additionally code-pinned
 * to TLSv1.3-only in {@code ClientTls} (PROTOCOLS = {"TLSv1.3"}, CIPHERS = the two AEAD suites), matching the
 * server's {@code TlsConfig}.
 *
 * <p>The §06 F9-4 SAN endpoint check and the F6A-4 no-AUTH-frame property are proven in the edge module's
 * {@code EdgeTlsTest}; this class re-expresses the F9-2 profile assertion into the conformance module so the
 * coverage audit maps the clause. F9-3 (identity = the verified client-cert Subject DN; {@code edgeId}
 * advisory) is a server-side identity override with no client-observable wire signal — reported as a skip.
 */
@Timeout(60)
class ClauseTlsProfileTest {

    @TempDir
    static Path certDir;
    static CertFixtures certs;

    @BeforeAll
    static void generateCerts() throws Exception {
        certs = new CertFixtures(certDir);
    }

    @Test
    @Tag("clause:F9-2")
    void mtlsHandshakeUsesTheTlsV13AeadProfile() throws Exception {
        // needClientAuth=true: the server requires the client certificate (the mTLS posture). The server socket
        // enables ONLY TLSv1.3 + the two AEAD suites (MockEdgeServer.startTls), so reaching AUTHENTICATED is
        // proof the client negotiated exactly the F9-2 profile.
        try (MockEdgeServer server = MockEdgeServer.startTls(
                certs.serverContext(false), true, false, MockEdgeServer.Conn::parkUntilClosed)) {
            ConfigdClientConfig config = ConfigdClientConfig.builder()
                    .endpoint("localhost", server.port())
                    .tls(certs.clientMutualTls())
                    .build();
            try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
                assertEquals(AuthMode.MTLS, client.authMode());
                client.connectAndAuthenticate().get(30, TimeUnit.SECONDS);
                assertEquals(EdgeConnectionState.AUTHENTICATED, client.state(),
                        "the client completed the TLSv1.3 + AEAD-suite mTLS handshake (F9-2)");
                assertEquals(0, server.authFrameCount(), "mTLS authenticates at the handshake — no AUTH frame");
            }
        }
    }
}
