package io.configd.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-1 (P1) fail-closed: the cluster signing key must not be co-located inside the data directory it
 * protects (PA-2021 - the at-rest integrity key is derived from it; a storage-tampering adversary who
 * can read the co-located key forges a valid MAC). Default behavior is to REFUSE TO START; the
 * {@code configd.security.allowColocatedSigningKey} opt-out downgrades to a warning for dev/test.
 * <p>
 * The module-wide surefire opt-out (parent pom) lets the OTHER server-boot tests run with the
 * co-located default; these tests bypass it by calling the guard directly with an explicit flag, or
 * by setting the property false to exercise the real startup refusal.
 */
class D1FailClosedTest {

    private static final String PROP = "configd.security.allowColocatedSigningKey";

    @TempDir
    Path tempDir;

    // ---- direct enforcement (independent of the module-wide test opt-out) ----

    @Test
    void coLocatedKeyWithoutOptOutThrows() {
        Path dataDir = tempDir.resolve("data");
        Path coLocated = dataDir.resolve("signing-key.bin");
        SecurityException ex = assertThrows(SecurityException.class,
                () -> ConfigdServer.enforceSigningKeyNotColocated(coLocated, dataDir, false));
        assertTrue(ex.getMessage().toLowerCase().contains("co-located"),
                "the refusal must name the co-location: " + ex.getMessage());
    }

    @Test
    void nestedKeyInsideDataDirIsRefused() {
        Path dataDir = tempDir.resolve("data");
        Path nested = dataDir.resolve("sub").resolve("key.bin"); // still inside dataDir
        assertThrows(SecurityException.class,
                () -> ConfigdServer.enforceSigningKeyNotColocated(nested, dataDir, false));
    }

    @Test
    void coLocatedKeyWithOptOutIsAllowed() {
        Path dataDir = tempDir.resolve("data");
        Path coLocated = dataDir.resolve("signing-key.bin");
        assertDoesNotThrow(
                () -> ConfigdServer.enforceSigningKeyNotColocated(coLocated, dataDir, true));
    }

    @Test
    void separateKeyIsAlwaysAllowed() {
        Path dataDir = tempDir.resolve("data");
        Path separate = tempDir.resolve("secrets").resolve("signing-key.bin");
        assertDoesNotThrow(
                () -> ConfigdServer.enforceSigningKeyNotColocated(separate, dataDir, false));
    }

    // ---- end-to-end: startup is REFUSED with a co-located key (the section 2 deliverable) ----

    @Test
    void serverStartupRefusedWithCoLocatedKey() {
        String saved = System.getProperty(PROP);
        try {
            System.setProperty(PROP, "false"); // override the module-wide dev opt-out
            ServerConfig config = ServerConfig.parse(new String[]{
                    "--node-id", "0", "--data-dir", tempDir.resolve("boot").toString(),
                    "--peers", "1,2", "--api-port", "0"
            });
            // co-located default key (dataDir/signing-key.bin) -> start() must refuse
            SecurityException ex = assertThrows(SecurityException.class,
                    () -> ConfigdServer.start(config));
            assertTrue(ex.getMessage().contains("D-1")
                            || ex.getMessage().toLowerCase().contains("co-located"),
                    "startup refusal must be the D-1 co-location SecurityException: " + ex.getMessage());
        } finally {
            if (saved == null) System.clearProperty(PROP); else System.setProperty(PROP, saved);
        }
    }

    @Test
    void serverStartsWithSeparateKey() {
        String saved = System.getProperty(PROP);
        try {
            System.setProperty(PROP, "false"); // opt-out OFF, yet a separate key must still start
            Path key = tempDir.resolve("secrets").resolve("signing-key.bin"); // outside the data dir
            ServerConfig config = ServerConfig.parse(new String[]{
                    "--node-id", "0", "--data-dir", tempDir.resolve("boot2").toString(),
                    "--peers", "1,2", "--api-port", "0", "--signing-key-file", key.toString()
            });
            ConfigdServer server = ConfigdServer.start(config);
            assertNotNull(server, "a signing key mounted on separate storage is the correct layout");
            server.shutdown();
        } finally {
            if (saved == null) System.clearProperty(PROP); else System.setProperty(PROP, saved);
        }
    }
}
