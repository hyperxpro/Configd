package io.configd.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class NoAuthModeBootTest {

    @TempDir
    Path tempDir;

    @Test
    void modeNoneOpensTheGateAndWarnsLoudly() throws Exception {
        String saved = System.getProperty("configd.auth.mode");
        System.setProperty("configd.auth.mode", "none");
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBuffer, true, StandardCharsets.UTF_8));

        ConfigdServer server = null;
        try {
            server = ConfigdServer.start(ServerConfig.parse(new String[]{
                    "--node-id", "0", "--data-dir", tempDir.toString(), "--peers", "1,2", "--api-port", "0"}));
            System.setErr(originalErr); // restore before assertions so any failure output is visible
            int port = server.apiPort();
            HttpClient http = HttpClient.newHttpClient();

            // A credential-less GET is ALLOWED: auth is off, so this is not a 401 (200/404 by store state).
            assertNotEquals(401, get(http, port, "/v1/config/app.x"),
                    "mode=none must NOT 401 a credential-less GET (the gate is open)");

            // THE DISCRIMINATOR: a credential-less reserved `_acl/` WRITE is refused as 403 (reserved write
            // while auth is off), NOT 401. If mode=none were wrongly routed through the chain, a missing
            // credential would be 401 here instead.
            assertEquals(403, put(http, port, "/v1/config/_acl/roles/x", "allow READ app."),
                    "a reserved _acl/ WRITE stays refused (403, not 401) under mode=none");

            // A credential-less ordinary PUT is ALLOWED past auth (it then fails downstream with no quorum,
            // e.g. 5xx - but never 401).
            assertNotEquals(401, put(http, port, "/v1/config/app.y", "value"),
                    "mode=none must NOT 401 a credential-less PUT");

            String stderr = errBuffer.toString(StandardCharsets.UTF_8);
            assertTrue(stderr.contains("configd.auth.mode=none") && stderr.contains("DISABLED"),
                    "mode=none must emit a loud auth-disabled warning; stderr was:\n" + stderr);
        } finally {
            System.setErr(originalErr);
            if (server != null) {
                server.shutdown();
            }
            if (saved == null) {
                System.clearProperty("configd.auth.mode");
            } else {
                System.setProperty("configd.auth.mode", saved);
            }
        }
    }

    private static int get(HttpClient http, int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static int put(HttpClient http, int port, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
                        .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
