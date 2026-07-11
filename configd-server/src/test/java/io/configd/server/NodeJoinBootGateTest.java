package io.configd.server;

import io.configd.transport.PeerIdentityPolicy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node-join fail-closed default at the server boot level: an authenticated cluster with TLS on the
 * Raft interior must enumerate its peers, else the server refuses to start. Without an allow-list, any
 * client certificate the CA trusts could forge a peer's {@code senderId} and join consensus - exactly
 * the hole this gate closes.
 *
 * <p>Proves both directions at the real {@code ConfigdServer.start} boot:
 * <ul>
 *   <li>auth-enabled + TLS + <b>no</b> allow-list &rarr; boot error (the gate fires before the transport
 *       binds);</li>
 *   <li>auth-enabled + TLS + an enumerated allow-list &rarr; boots (the intended production posture);</li>
 *   <li>auth-<b>disabled</b> + TLS + no allow-list &rarr; boots (the legacy loud-warning escape stays
 *       byte-identical - dev/test/shared-cert fleets are unchanged).</li>
 * </ul>
 * The predicate itself is additionally unit-proven by {@code PeerIdentityPolicyTest.bootGate*}.
 */
class NodeJoinBootGateTest {

    private static final String ALLOWED = PeerIdentityPolicy.ALLOWED_NODES_PROP;

    private static Path fixtureDir;
    private static Path keyStorePath;
    private static Path trustStorePath;
    private static Path certFile;

    private String savedAllowed;

    @BeforeAll
    static void generateTlsFixture() throws Exception {
        fixtureDir = Files.createTempDirectory("configd-nodejoin-boot-");
        keyStorePath = fixtureDir.resolve("keystore.p12");
        trustStorePath = fixtureDir.resolve("truststore.p12");
        certFile = fixtureDir.resolve("cert.pem");

        runKeytool("keytool", "-genkeypair", "-alias", "configd-test",
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-validity", "1", "-dname", "CN=configd-test,O=test",
                "-storetype", "PKCS12", "-keystore", keyStorePath.toString(),
                "-storepass", "changeit", "-keypass", "changeit");
        runKeytool("keytool", "-exportcert", "-alias", "configd-test",
                "-keystore", keyStorePath.toString(), "-storepass", "changeit",
                "-rfc", "-file", certFile.toString());
        runKeytool("keytool", "-importcert", "-alias", "configd-test",
                "-file", certFile.toString(), "-keystore", trustStorePath.toString(),
                "-storepass", "changeit", "-storetype", "PKCS12", "-noprompt");

        // ConfigdServer.start builds its TlsManager via TlsConfig.mtls, which hardcodes an EMPTY store
        // password; keytool cannot emit an empty-password PKCS12 directly, so repack both stores under an
        // empty password (the same repack the compose E2E does). Without this, TLS init fails before the
        // node-join gate is ever reached.
        repackKeyStoreEmptyPassword(keyStorePath, "configd-test");
        repackTrustStoreEmptyPassword(trustStorePath);
    }

    private static void repackKeyStoreEmptyPassword(Path p12, String alias) throws Exception {
        KeyStore src = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p12)) {
            src.load(in, "changeit".toCharArray());
        }
        Key key = src.getKey(alias, "changeit".toCharArray());
        Certificate[] chain = src.getCertificateChain(alias);
        KeyStore dst = KeyStore.getInstance("PKCS12");
        dst.load(null, null);
        dst.setKeyEntry(alias, key, new char[0], chain); // key protected by the empty store password
        try (OutputStream out = Files.newOutputStream(p12)) {
            dst.store(out, new char[0]);
        }
    }

    private static void repackTrustStoreEmptyPassword(Path p12) throws Exception {
        KeyStore src = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p12)) {
            src.load(in, "changeit".toCharArray());
        }
        try (OutputStream out = Files.newOutputStream(p12)) {
            src.store(out, new char[0]); // trust entries only; re-MAC under the empty password
        }
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

    @BeforeEach
    void saveAllowList() {
        savedAllowed = System.getProperty(ALLOWED);
        System.clearProperty(ALLOWED);
    }

    @AfterEach
    void restoreAllowList() {
        if (savedAllowed == null) {
            System.clearProperty(ALLOWED);
        } else {
            System.setProperty(ALLOWED, savedAllowed);
        }
    }

    @Test
    @Timeout(60)
    void authEnabledTlsWithoutAllowListRefusesToStart(@TempDir Path dataDir) {
        // auth on (--auth-token) + TLS on + peer addresses configured + no allow-list -> boot error. The
        // gate fires at the transport wiring, before the Raft transport binds, so no socket is opened.
        ServerConfig config = tlsConfig(dataDir, /*auth=*/true);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ConfigdServer.start(config),
                "an authenticated TLS cluster with no peer allow-list must refuse to start");
        assertTrue(ex.getMessage().contains(ALLOWED),
                "the boot error must name the allow-list key operators need to set: " + ex.getMessage());
    }

    @Test
    @Timeout(60)
    void authEnabledTlsWithAllowListBoots(@TempDir Path dataDir) {
        // The intended production posture: an enumerated allow-list makes the gate pass; the server boots.
        System.setProperty(ALLOWED, "configd-test=0,peer=1");
        ServerConfig config = tlsConfig(dataDir, /*auth=*/true);
        ConfigdServer server = ConfigdServer.start(config);
        try {
            assertNotNull(server.driver(), "an authenticated TLS cluster with an allow-list must boot");
        } finally {
            server.shutdown();
        }
    }

    @Test
    @Timeout(60)
    void authDisabledTlsWithoutAllowListStillBoots(@TempDir Path dataDir) {
        // Byte-identity of the legacy escape: with auth disabled, an empty allow-list keeps the loud-warning
        // open gate (dev/test/shared-cert fleets are unchanged). The gate must not fire here.
        ServerConfig config = tlsConfig(dataDir, /*auth=*/false);
        ConfigdServer server = ConfigdServer.start(config);
        try {
            assertNotNull(server.driver(),
                    "auth-disabled deployments must keep booting with an empty allow-list (byte-identical)");
        } finally {
            server.shutdown();
        }
    }

    /**
     * A TLS-enabled config with a configured peer address (so the Raft transport wiring - and the node-join
     * gate - is exercised) and an ephemeral bind/API port. The peer never connects; boot only needs the
     * transport wiring to run. {@code auth} toggles the legacy {@code --auth-token}.
     */
    private ServerConfig tlsConfig(Path dataDir, boolean auth) {
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                "--node-id", "0",
                "--data-dir", dataDir.toString(),
                "--peers", "0,1",
                "--peer-addresses", "1=127.0.0.1:1",   // parses; the peer is never reached at boot
                "--bind-port", "0",                      // ephemeral Raft listen port
                "--api-port", "0",
                "--tls-cert", certFile.toString(),
                "--tls-key", keyStorePath.toString(),
                "--tls-trust-store", trustStorePath.toString()));
        if (auth) {
            args.add("--auth-token");
            args.add("s3cr3t");
        }
        return ServerConfig.parse(args.toArray(String[]::new));
    }

    private static void runKeytool(String... command) throws Exception {
        int rc = new ProcessBuilder(command).redirectErrorStream(true).inheritIO().start().waitFor();
        assertTrue(rc == 0, "keytool failed: " + String.join(" ", command));
    }
}
