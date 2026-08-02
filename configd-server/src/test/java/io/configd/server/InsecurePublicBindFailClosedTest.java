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

@Timeout(30)
class InsecurePublicBindFailClosedTest {

    private static final String OVERRIDE = "configd.security.allowInsecurePublicBind";
    private static final String AUTH_MODE = "configd.auth.mode";

    @TempDir
    Path tempDir;


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


    @Test
    void serverStartupRefusedOnPublicBindWithAuthOff() {
        String savedOverride = System.getProperty(OVERRIDE);
        String savedAuth = System.getProperty(AUTH_MODE);
        try {
            System.setProperty(OVERRIDE, "false");
            System.clearProperty(AUTH_MODE);
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
    void modeNoneWithLeftoverAuthTokenStillRefusesPublicBind() {
        // The bypass this guards against: configd.auth.mode=none disables auth entirely (the open gate
        // wires no chain/interceptor/ACL) and supersedes the legacy --auth-token. If isAuthEnabled counted
        // the now-inert token, the guard would believe the store is authenticated and permit an
        // unauthenticated public bind. mode=none is auth-off regardless of the token, so the guard must
        // still refuse.
        String savedOverride = System.getProperty(OVERRIDE);
        String savedAuth = System.getProperty(AUTH_MODE);
        try {
            System.setProperty(OVERRIDE, "false");
            System.setProperty(AUTH_MODE, "none");
            ServerConfig config = ServerConfig.parse(new String[]{
                    "--node-id", "0", "--data-dir", tempDir.resolve("modenone").toString(),
                    "--peers", "1,2", "--api-port", "0",
                    "--auth-token", "leftover-inert-token", // superseded by mode=none; must NOT count as auth
                    "--bind-address", "0.0.0.0"
            });
            SecurityException ex = assertThrows(SecurityException.class,
                    () -> ConfigdServer.start(config));
            assertTrue(ex.getMessage().contains("B5"),
                    "mode=none plus a leftover --auth-token on a public bind must still be refused by B5: "
                            + ex.getMessage());
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
            System.setProperty(OVERRIDE, "true");
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
