package io.configd.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TlsManagerTest {

    private static final char[] TEST_PASSWORD = "changeit".toCharArray();

    @TempDir
    Path tempDir;

    /**
     * Generates a PKCS12 keystore with a self-signed certificate using
     * the JDK's keytool command, then exports the cert into a separate
     * trust store. Returns a TlsConfig pointing at both files.
     */
    private TlsConfig generateSelfSignedConfig() throws Exception {
        Path keyStorePath = tempDir.resolve("keystore.p12");
        Path trustStorePath = tempDir.resolve("truststore.p12");
        Path certFile = tempDir.resolve("cert.pem");

        // Generate a self-signed cert + key in a PKCS12 keystore via keytool
        run("keytool",
                "-genkeypair",
                "-alias", "configd-test",
                "-keyalg", "EC",
                "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA",
                "-validity", "1",
                "-dname", "CN=configd-test,O=test",
                "-storetype", "PKCS12",
                "-keystore", keyStorePath.toString(),
                "-storepass", "changeit",
                "-keypass", "changeit");

        // Export the certificate
        run("keytool",
                "-exportcert",
                "-alias", "configd-test",
                "-keystore", keyStorePath.toString(),
                "-storepass", "changeit",
                "-rfc",
                "-file", certFile.toString());

        // Import into a separate trust store
        run("keytool",
                "-importcert",
                "-alias", "configd-test",
                "-file", certFile.toString(),
                "-keystore", trustStorePath.toString(),
                "-storepass", "changeit",
                "-storetype", "PKCS12",
                "-noprompt");

        return new TlsConfig(certFile, keyStorePath, trustStorePath, true,
                List.of("TLS_AES_256_GCM_SHA384"),
                List.of("TLSv1.3"),
                TEST_PASSWORD);
    }

    private static void run(String... command) throws Exception {
        int rc = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .inheritIO()
                .start()
                .waitFor();
        assertEquals(0, rc, "Command failed: " + command[0]);
    }

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    @Test
    void createsSslContextFromSelfSignedCerts() throws Exception {
        TlsConfig config = generateSelfSignedConfig();
        TlsManager manager = new TlsManager(config);

        SSLContext ctx = manager.currentContext();
        assertNotNull(ctx);
        assertEquals("TLSv1.3", ctx.getProtocol());
    }

    @Test
    void currentContextIsNeverNull() throws Exception {
        TlsConfig config = generateSelfSignedConfig();
        TlsManager manager = new TlsManager(config);

        assertNotNull(manager.currentContext());
    }

    // -----------------------------------------------------------------------
    // Reload
    // -----------------------------------------------------------------------

    @Test
    void reloadProducesNewContext() throws Exception {
        TlsConfig config = generateSelfSignedConfig();
        TlsManager manager = new TlsManager(config);

        SSLContext before = manager.currentContext();
        manager.reload();
        SSLContext after = manager.currentContext();

        assertNotNull(after);
        assertNotSame(before, after);
    }

    @Test
    void reloadUnderConcurrentAccessIsVisible() throws Exception {
        TlsConfig config = generateSelfSignedConfig();
        TlsManager manager = new TlsManager(config);

        SSLContext initial = manager.currentContext();
        assertNotNull(initial);

        // Perform multiple concurrent reads interleaved with a reload
        var readResults = new java.util.concurrent.ConcurrentLinkedQueue<SSLContext>();
        int readerCount = 4;
        var latch = new java.util.concurrent.CountDownLatch(readerCount);

        for (int i = 0; i < readerCount; i++) {
            Thread.startVirtualThread(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        SSLContext ctx = manager.currentContext();
                        assertNotNull(ctx, "currentContext() must never return null during reload");
                        readResults.add(ctx);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Reload while readers are active
        manager.reload();
        SSLContext afterReload = manager.currentContext();
        assertNotSame(initial, afterReload, "Reload must produce a new SSLContext");

        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);

        // All reads returned a non-null context — no torn reads
        assertFalse(readResults.isEmpty());
        for (SSLContext ctx : readResults) {
            assertNotNull(ctx);
        }
    }

    @Test
    void repeatedReloadsAllProduceDistinctContexts() throws Exception {
        TlsConfig config = generateSelfSignedConfig();
        TlsManager manager = new TlsManager(config);

        var contexts = new java.util.HashSet<SSLContext>();
        contexts.add(manager.currentContext());

        for (int i = 0; i < 5; i++) {
            manager.reload();
            contexts.add(manager.currentContext());
        }

        // Each reload should produce a distinct SSLContext instance
        assertEquals(6, contexts.size(),
                "Each reload must produce a distinct SSLContext");
    }

    // -----------------------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------------------

    @Test
    void constructorWithMissingFileThrows() {
        Path missing = tempDir.resolve("nonexistent.p12");
        TlsConfig config = new TlsConfig(missing, missing, missing, true,
                List.of(), List.of("TLSv1.3"));

        assertThrows(Exception.class, () -> new TlsManager(config));
    }

    @Test
    void nullConfigThrows() {
        assertThrows(NullPointerException.class, () -> new TlsManager(null));
    }

    // -----------------------------------------------------------------------
    // TlsConfig record
    // -----------------------------------------------------------------------

    @Test
    void tlsConfigMtlsFactory() {
        Path cert = Path.of("/tmp/cert.pem");
        Path key = Path.of("/tmp/key.pem");
        Path trust = Path.of("/tmp/trust.p12");

        TlsConfig config = TlsConfig.mtls(cert, key, trust);
        assertEquals(cert, config.certPath());
        assertEquals(key, config.keyPath());
        assertEquals(trust, config.trustStorePath());
        assertTrue(config.requireClientAuth());
        assertFalse(config.ciphers().isEmpty());
        assertEquals(List.of("TLSv1.3"), config.protocols());
    }

    @Test
    void tlsConfigNullCertPathThrows() {
        assertThrows(NullPointerException.class,
                () -> new TlsConfig(null, Path.of("/k"), Path.of("/t"),
                        false, List.of(), List.of()));
    }

    @Test
    void tlsConfigNullKeyPathThrows() {
        assertThrows(NullPointerException.class,
                () -> new TlsConfig(Path.of("/c"), null, Path.of("/t"),
                        false, List.of(), List.of()));
    }

    @Test
    void tlsConfigNullTrustStorePathThrows() {
        assertThrows(NullPointerException.class,
                () -> new TlsConfig(Path.of("/c"), Path.of("/k"), null,
                        false, List.of(), List.of()));
    }

    @Test
    void tlsConfigDefaultsToEmptyPassword() {
        TlsConfig config = new TlsConfig(Path.of("/c"), Path.of("/k"), Path.of("/t"),
                false, List.of(), List.of());
        assertEquals(0, config.storePassword().length);
    }

    @Test
    void tlsConfigNullPasswordDefaultsToEmpty() {
        TlsConfig config = new TlsConfig(Path.of("/c"), Path.of("/k"), Path.of("/t"),
                false, List.of(), List.of(), null);
        assertNotNull(config.storePassword());
        assertEquals(0, config.storePassword().length);
    }
}
