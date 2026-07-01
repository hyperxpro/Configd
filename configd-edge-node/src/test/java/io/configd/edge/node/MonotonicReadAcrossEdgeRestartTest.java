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

import java.io.IOException;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Per-session monotonic reads <b>across a REAL edge process restart</b>. The sim counterpart
 * in configd-testkit pins the property at the engine seam; this leg restarts a real
 * {@link EdgeNodeMain} — the cache is lost (an edge is a cache; only the {@code epoch.lock}
 * sidecar survives in {@code --data-dir}) — while a client HOLDS its pre-restart cursor:
 * <ul>
 *   <li>every read at the held cursor during the restart/re-bootstrap window is the consistent
 *       refusal ({@code 404 + X-Configd-Refused: cursor-behind}) — the edge NEVER serves
 *       pre-crash data and never serves below the cursor;</li>
 *   <li>the re-bootstrap (fresh SUBSCRIBE at cursor 0; the server's TAIL/SNAPSHOT_FIRST
 *       decision) catches the fresh process up; the held-cursor read is then served at a
 *       version >= the cursor with the post-restart authoritative bytes.</li>
 * </ul>
 */
@Timeout(120)
class MonotonicReadAcrossEdgeRestartTest {

    private static final Pattern COMMITTED_SEQ = Pattern.compile("seq=(\\d+)");
    private static final Duration DEADLINE = Duration.ofSeconds(45);

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
    void heldCursorRefusesAcrossRestartUntilReBootstrapThenServesPostRestartState()
            throws Exception {
        Path signingKey = tempDir.resolve("signing-key.bin");
        SigningKeyStore.loadOrCreate(signingKey);
        server = ConfigdServer.start(new ServerConfig(
                NodeId.of(0), tempDir.resolve("server-data"), Set.of(), "127.0.0.1",
                0, 0, null, null, null, null, Map.of(), signingKey, Set.of("secure/"), 0));
        Path verifyKey = tempDir.resolve("verify-key.der");
        VerifyKeyExporter.export(signingKey, verifyKey);
        String serverBase = "http://127.0.0.1:" + server.apiPort();
        Path edgeDataDir = tempDir.resolve("edge-data"); // SAME dir across the restart

        edge = startEdge(edgeDataDir, verifyKey);
        String edgeBase = "http://127.0.0.1:" + edge.apiPort();

        // Converge incarnation #1 and let the CLIENT capture its cursor from a real read.
        putCommitted(serverBase, "svc/k", "v1");
        long seq2 = putCommitted(serverBase, "svc/k", "v2");
        HttpResponse<String> served = pollUntilServed(edgeBase, "svc/k", seq2);
        long heldCursor = Long.parseLong(
                served.headers().firstValue(EdgeHttpServer.HDR_CURSOR).orElseThrow());
        assertTrue(heldCursor >= seq2);
        assertEquals("v2", served.body());

        // KILL the edge process (cache gone) and restart it on the SAME data dir. The
        // data dir may hold ONLY the epoch sidecar — never values (the disk sweep is
        // pinned by EdgeStrongReadFailClosedTest).
        edge.shutdown();
        try (var entries = Files.list(edgeDataDir)) {
            assertTrue(entries.allMatch(p -> p.getFileName().toString().startsWith("epoch.lock")),
                    "nothing but the epoch sidecar may persist across an edge restart");
        }
        edge = startEdge(edgeDataDir, verifyKey);
        String edgeBase2 = "http://127.0.0.1:" + edge.apiPort();

        // The fresh incarnation starts at version 0: the FIRST held-cursor read in the
        // window (before re-bootstrap completes) must be the consistent refusal — and
        // pollUntilServed asserts EVERY response on the way is either that refusal or a
        // version >= the held cursor ("never serve pre-crash old data" is enforced on each iteration).
        HttpResponse<String> afterRestart = pollUntilServed(edgeBase2, "svc/k", heldCursor);
        assertEquals("v2", afterRestart.body(),
                "post-re-bootstrap state serves the authoritative bytes");
        long version = Long.parseLong(
                afterRestart.headers().firstValue(EdgeHttpServer.HDR_VERSION).orElseThrow());
        assertTrue(version >= seq2, "served at or past the held cursor, never below");

        // The re-bootstrap really ran through the fresh process (applied from scratch).
        assertTrue(edge.core().currentVersion() >= heldCursor,
                "incarnation #2 re-bootstrapped to (at least) the held cursor");

        // And the recovered edge is live for NEW writes (full recovery, not a one-off).
        long seq3 = putCommitted(serverBase, "svc/k", "v3");
        assertEquals("v3", pollUntilServed(edgeBase2, "svc/k", seq3).body());
    }

    // Helpers (deadline-polling; no sleep-as-sync)

    private EdgeNodeMain startEdge(Path dataDir, Path verifyKey) {
        return EdgeNodeMain.start(new EdgeNodeConfig(
                "edge-restart",
                List.of(InetSocketAddress.createUnresolved(
                        "127.0.0.1", server.fanOutServer().localPort())),
                0, dataDir, verifyKey, List.of(),
                null, null, null, 50L, EdgeNodeConfig.DEFAULT_HEARTBEAT_SILENCE_FACTOR,
                EdgeNodeConfig.DEFAULT_POISON_MAX_RETRIES));
    }

    /** Polls a held-cursor read; every non-200 must be the cursor-behind refusal. */
    private HttpResponse<String> pollUntilServed(String edgeBase, String key, long cursor)
            throws Exception {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> r;
            try {
                r = get(edgeBase + "/v1/config/" + key,
                        EdgeHttpServer.HDR_CURSOR, String.valueOf(cursor));
            } catch (RuntimeException transientChurn) {
                continue; // restart window: connection refused — keep polling
            }
            if (r.statusCode() == 200) {
                long version = Long.parseLong(
                        r.headers().firstValue(EdgeHttpServer.HDR_VERSION).orElseThrow());
                assertTrue(version >= cursor,
                        "NEVER serve below the held cursor: " + version + " < " + cursor);
                return r;
            }
            assertEquals(404, r.statusCode(),
                    "the catch-up window answers with refusals, never errors");
            assertEquals("cursor-behind",
                    r.headers().firstValue(EdgeHttpServer.HDR_REFUSED).orElse("missing"),
                    "a held-cursor read across the restart must refuse, never serve stale");
            Thread.onSpinWait();
        }
        fail("edge did not serve " + key + " at held cursor " + cursor + " within deadline");
        return null;
    }

    private HttpResponse<String> get(String url, String... headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url)).GET().timeout(Duration.ofSeconds(10));
            for (int i = 0; i < headers.length; i += 2) {
                b.header(headers[i], headers[i + 1]);
            }
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("GET " + url + " failed: " + e.getMessage(), e);
        }
    }

    private long putCommitted(String base, String key, String body) throws Exception {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                                .uri(URI.create(base + "/v1/config/" + key))
                                .timeout(DEADLINE)
                                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    Matcher m = COMMITTED_SEQ.matcher(resp.body());
                    if (m.find()) {
                        return Long.parseLong(m.group(1));
                    }
                }
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IllegalStateException("write '" + key + "' not committed in time", last);
    }
}
