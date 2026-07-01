package io.configd.edge.node;

import io.configd.common.NodeId;
import io.configd.server.ConfigdServer;
import io.configd.server.ServerConfig;
import io.configd.store.SigningKeyStore;
import io.configd.store.VerifyKeyExporter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Process-level test for the strong-read fail-closed contract: {@code secure/} keys are
 * <b>store-and-never-serve</b>. The signed chain delivers them to the edge (keeping the
 * snapshot-delta equivalence invariant), and the serving path fail-closes: 503 +
 * {@code X-Fail-Closed: strong-read} — clients must go to the control plane's linearizable
 * path. Non-secure keys serve normally.
 *
 * <p>Also pins the data-dir boundary: the edge's {@code --data-dir} holds only
 * {@code epoch.lock} metadata — no {@code secure/} VALUE bytes ever land on the edge's disk.
 */
@Timeout(120)
class EdgeStrongReadFailClosedTest {

    private static final Pattern COMMITTED_SEQ = Pattern.compile("seq=(\\d+)");
    private static final Duration DEADLINE = Duration.ofSeconds(45);
    private static final String SECRET_VALUE = "SECRET-armed-7f3a";

    @TempDir
    Path tempDir;

    private ConfigdServer server;
    private EdgeNodeMain edge;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void tearDown() {
        if (edge != null) {
            edge.shutdown();
        }
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void secureKeyIsStoredButNeverServedWhileNormalKeysServe() throws Exception {
        Path signingKey = tempDir.resolve("signing-key.bin");
        SigningKeyStore.loadOrCreate(signingKey);
        server = ConfigdServer.start(new ServerConfig(
                NodeId.of(0), tempDir.resolve("server-data"), Set.of(), "127.0.0.1",
                0, 0, null, null, null, null, Map.of(), signingKey, Set.of("secure/"), 0));
        Path verifyKey = tempDir.resolve("verify-key.der");
        VerifyKeyExporter.export(signingKey, verifyKey);

        Path edgeDataDir = tempDir.resolve("edge-data");
        edge = EdgeNodeMain.start(new EdgeNodeConfig(
                "edge-ct37",
                List.of(InetSocketAddress.createUnresolved(
                        "127.0.0.1", server.fanOutServer().localPort())),
                0, edgeDataDir, verifyKey, List.of(), null, null, null,
                50L, EdgeNodeConfig.DEFAULT_HEARTBEAT_SILENCE_FACTOR, EdgeNodeConfig.DEFAULT_POISON_MAX_RETRIES));

        String serverBase = "http://127.0.0.1:" + server.apiPort();
        String edgeBase = "http://127.0.0.1:" + edge.apiPort();

        // Commit one strong-read key and one ordinary key through the control plane.
        long secureSeq = putCommitted(serverBase, "secure/kill-switch", SECRET_VALUE);
        long normalSeq = putCommitted(serverBase, "app/flag", "on");
        long target = Math.max(secureSeq, normalSeq);

        // Wait for the chain to reach the edge (core cursor — not the serving path, which
        // must NEVER answer for the secure key).
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (edge.core().currentVersion() < target) {
            if (System.nanoTime() > deadline) {
                fail("edge did not catch up to seq " + target
                        + " (at " + edge.core().currentVersion() + ")");
            }
            Thread.onSpinWait();
        }

        // STORED: the chain delivered it and the store kept it (always-store for snapshot-delta equivalence).
        assertTrue(edge.core().get("secure/kill-switch").found(),
                "the secure/ key must be STORED at the edge");
        assertEquals(SECRET_VALUE,
                new String(edge.core().get("secure/kill-switch").value(), StandardCharsets.UTF_8));

        // NEVER SERVED: 503 + X-Fail-Closed, no value leak — with or without a cursor.
        for (String[] headers : new String[][]{{}, {EdgeHttpServer.HDR_CURSOR, String.valueOf(secureSeq)}}) {
            HttpResponse<String> refused = get(edgeBase + "/v1/config/secure/kill-switch", headers);
            assertEquals(503, refused.statusCode(), "strong-read serving must fail closed");
            assertEquals("strong-read",
                    refused.headers().firstValue(EdgeHttpServer.HDR_FAIL_CLOSED).orElseThrow());
            assertFalse(refused.body().contains(SECRET_VALUE), "no value leak in the refusal");
        }

        // Non-secure keys serve normally from the same store.
        HttpResponse<String> served = get(edgeBase + "/v1/config/app/flag",
                EdgeHttpServer.HDR_CURSOR, String.valueOf(normalSeq));
        assertEquals(200, served.statusCode());
        assertEquals("on", served.body());

        // The refusal metric moved.
        String metrics = get(edgeBase + "/metrics").body();
        assertTrue(metrics.lines().anyMatch(l ->
                        l.startsWith("edge_read_refusals_strong_read_total ") && !l.endsWith(" 0")),
                "edge_read_refusals_strong_read_total must have moved");

        // --data-dir carries epoch metadata only — the secure/ VALUE bytes must never land
        // on the edge's disk.
        try (var paths = Files.walk(edgeDataDir)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                byte[] content = Files.readAllBytes(p);
                assertFalse(new String(content, StandardCharsets.ISO_8859_1).contains(SECRET_VALUE),
                        "secure/ value bytes found on disk at " + p + " (RR-098 violation)");
            }
        }
    }

    // Helpers

    private HttpResponse<String> get(String url, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url)).GET().timeout(Duration.ofSeconds(10));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private long putCommitted(String base, String key, String body) throws Exception {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/v1/config/" + key))
                    .timeout(DEADLINE)
                    .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                Matcher m = COMMITTED_SEQ.matcher(resp.body());
                if (m.find()) {
                    return Long.parseLong(m.group(1));
                }
            }
            // 503/504 leader churn — retry.
        }
        throw new IllegalStateException("write '" + key + "' not committed in time");
    }
}
