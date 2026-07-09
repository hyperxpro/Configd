package io.configd.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B5 (Class-B) footgun guard: an UNAUTHENTICATED store must not bind a NON-LOOPBACK interface SILENTLY
 * (the Redis/etcd "default-open" class). This is deliberately NOT "auth required by default": a no-auth
 * deployment stays possible via the {@code configd.security.allowInsecurePublicBind} override - the guard
 * only refuses the accidental, unacknowledged case. Default behavior is REFUSE TO START.
 * <p>
 * Mirrors {@link D1FailClosedTest}: the direct-enforcement tests drive
 * {@link ConfigdServer#enforceBindNotSilentlyPublic} with plain values (immune to ambient config), and the
 * end-to-end tests set the override explicitly to exercise the real startup refusal / the acknowledged
 * warn-and-proceed path.
 */
@Timeout(30)
class InsecurePublicBindFailClosedTest {

    private static final String OVERRIDE = "configd.security.allowInsecurePublicBind";
    private static final String AUTH_MODE = "configd.auth.mode";

    @TempDir
    Path tempDir;

    // ---- direct enforcement (independent of ambient system properties) ----

    @Test
    void publicBindWithAuthOffAndNoOverrideThrows() {
        SecurityException ex = assertThrows(SecurityException.class,
                () -> ConfigdServer.enforceBindNotSilentlyPublic("0.0.0.0", false, false));
        assertTrue(ex.getMessage().contains("B5")
                        && ex.getMessage().toLowerCase().contains("unauthenticated"),
                "the refusal must name B5 + the unauthenticated public bind: " + ex.getMessage());
    }

    @Test
    void nonLoopbackLiteralWithAuthOffThrows() {
        // A specific routable interface (not the wildcard) is still off-box reachable -> refuse.
        assertThrows(SecurityException.class,
                () -> ConfigdServer.enforceBindNotSilentlyPublic("192.168.1.10", false, false));
    }

    @Test
    void unresolvableAddressFailsClosed() {
        // Cannot prove it is loopback -> treat as public -> refuse (fail closed). ".invalid" never resolves.
        assertThrows(SecurityException.class,
                () -> ConfigdServer.enforceBindNotSilentlyPublic("no-such-host.invalid", false, false));
    }

    @Test
    void loopbackWithAuthOffIsAllowed() {
        assertDoesNotThrow(
                () -> ConfigdServer.enforceBindNotSilentlyPublic("127.0.0.1", false, false));
    }

    @Test
    void ipv6LoopbackWithAuthOffIsAllowed() {
        assertDoesNotThrow(
                () -> ConfigdServer.enforceBindNotSilentlyPublic("::1", false, false));
    }

    @Test
    void publicBindWithAuthOnIsAllowed() {
        // An authenticated store may bind any interface - the footgun is unauthenticated exposure only.
        assertDoesNotThrow(
                () -> ConfigdServer.enforceBindNotSilentlyPublic("0.0.0.0", true, false));
    }

    @Test
    void publicBindWithOverrideProceedsButWarnsLoudly() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBuffer, true, StandardCharsets.UTF_8));
        try {
            assertDoesNotThrow(
                    () -> ConfigdServer.enforceBindNotSilentlyPublic("0.0.0.0", false, true));
        } finally {
            System.setErr(originalErr);
        }
        String stderr = errBuffer.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("WARNING") && stderr.contains(OVERRIDE),
                "the override path must warn loudly and name the override; stderr was:\n" + stderr);
    }

    // ---- end-to-end: startup is REFUSED (or proceeds under the override) at the real boot ----

    @Test
    void serverStartupRefusedOnPublicBindWithAuthOff() {
        String savedOverride = System.getProperty(OVERRIDE);
        String savedAuth = System.getProperty(AUTH_MODE);
        try {
            System.setProperty(OVERRIDE, "false"); // no acknowledgement
            System.clearProperty(AUTH_MODE);       // auth off (no SPI chain, no --auth-token)
            ServerConfig config = ServerConfig.parse(new String[]{
                    "--node-id", "0", "--data-dir", tempDir.resolve("boot").toString(),
                    "--peers", "1,2", "--api-port", "0",
                    "--bind-address", "0.0.0.0"
            });
            SecurityException ex = assertThrows(SecurityException.class,
                    () -> ConfigdServer.start(config));
            assertTrue(ex.getMessage().contains("B5"),
                    "startup refusal must be the B5 insecure-public-bind SecurityException: " + ex.getMessage());
        } finally {
            restore(OVERRIDE, savedOverride);
            restore(AUTH_MODE, savedAuth);
        }
    }

    @Test
    void serverStartsOnPublicBindWithExplicitOverride() {
        String savedOverride = System.getProperty(OVERRIDE);
        String savedAuth = System.getProperty(AUTH_MODE);
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBuffer, true, StandardCharsets.UTF_8));
        ConfigdServer server = null;
        try {
            System.setProperty(OVERRIDE, "true"); // explicit acknowledgement -> proceed, warn loudly
            System.clearProperty(AUTH_MODE);
            ServerConfig config = ServerConfig.parse(new String[]{
                    "--node-id", "1", "--data-dir", tempDir.resolve("boot2").toString(),
                    "--peers", "", "--api-port", "0",
                    "--bind-address", "0.0.0.0"
            });
            server = ConfigdServer.start(config);
            System.setErr(originalErr); // restore before assertions so failures are visible
            assertNotNull(server, "an acknowledged unauthenticated public bind is permitted to start");
            String stderr = errBuffer.toString(StandardCharsets.UTF_8);
            assertTrue(stderr.contains("NON-LOOPBACK") && stderr.contains(OVERRIDE),
                    "the acknowledged public bind must warn loudly at boot; stderr was:\n" + stderr);
        } finally {
            System.setErr(originalErr);
            if (server != null) {
                server.shutdown();
            }
            restore(OVERRIDE, savedOverride);
            restore(AUTH_MODE, savedAuth);
        }
    }

    private static void restore(String key, String saved) {
        if (saved == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, saved);
        }
    }
}
