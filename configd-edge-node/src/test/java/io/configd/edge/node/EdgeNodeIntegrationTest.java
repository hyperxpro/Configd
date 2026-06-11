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
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end process-level test for the C2 edge node (CT-23 wire-level signed chain,
 * CT-35 RYW through the real path, CT-03 stale header, CT-05 readiness): a REAL
 * single-node {@link ConfigdServer} (self-elects; signs every delta with its persistent
 * Ed25519 key) with its live {@code --edge-port} fan-out endpoint, and a REAL
 * {@link EdgeNodeMain} edge node on loopback whose verify key was produced by the REAL
 * distribution path ({@link VerifyKeyExporter} over {@code signing-key.bin} — C2 design
 * §3.6). Writes are driven through the server's HTTP API; reads through the edge's.
 *
 * <p>Deadline-polling only — no sleep-as-synchronization (the FanOutServerIntegrationTest
 * discipline); the per-method {@link Timeout} is pure hang detection, generous for the
 * throttled 2-vCPU box (RR-094).
 */
@Timeout(120)
class EdgeNodeIntegrationTest {

    private static final Pattern COMMITTED_SEQ = Pattern.compile("seq=(\\d+)");
    private static final Duration WRITE_DEADLINE = Duration.ofSeconds(30);
    private static final Duration POLL_DEADLINE = Duration.ofSeconds(45);

    @TempDir
    Path tempDir;

    private ConfigdServer server;
    private EdgeNodeMain edge;
    private EdgeNodeMain secondEdge;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void tearDown() {
        if (edge != null) {
            edge.shutdown();
        }
        if (secondEdge != null) {
            secondEdge.shutdown();
        }
        if (server != null) {
            server.shutdown();
        }
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    /** Starts a plaintext single-node server with an ephemeral edge port + signing key. */
    private ConfigdServer startServer() throws Exception {
        Path signingKey = tempDir.resolve("signing-key.bin");
        SigningKeyStore.loadOrCreate(signingKey); // deterministic pre-creation
        ServerConfig cfg = new ServerConfig(
                NodeId.of(0), tempDir.resolve("server-data"), Set.of(), "127.0.0.1",
                0, 0 /* api ephemeral */, null, null, null, null,
                Map.of(), signingKey, Set.of("secure/"), 0 /* edge ephemeral */);
        return ConfigdServer.start(cfg);
    }

    /** Exports the verify key via the REAL distribution path (VerifyKeyExporter). */
    private Path exportVerifyKey() throws Exception {
        Path verifyKey = tempDir.resolve("verify-key.der");
        VerifyKeyExporter.export(tempDir.resolve("signing-key.bin"), verifyKey);
        return verifyKey;
    }

    private EdgeNodeMain startEdge(String id, Path verifyKey, int... edgePorts) {
        List<InetSocketAddress> endpoints = java.util.Arrays.stream(edgePorts)
                .mapToObj(p -> InetSocketAddress.createUnresolved("127.0.0.1", p))
                .toList();
        EdgeNodeConfig cfg = new EdgeNodeConfig(id, endpoints, 0,
                tempDir.resolve(id + "-data"), verifyKey, List.of(),
                null, null, null, 50L, EdgeNodeConfig.DEFAULT_HEARTBEAT_SILENCE_FACTOR);
        return EdgeNodeMain.start(cfg);
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void writePropagatesOverSignedChainAndServesWithCursor() throws Exception {
        server = startServer();
        Path verifyKey = exportVerifyKey();
        edge = startEdge("edge-it-1", verifyKey, server.fanOutServer().localPort());
        String serverBase = "http://127.0.0.1:" + server.apiPort();
        String edgeBase = "http://127.0.0.1:" + edge.apiPort();

        // --- write → propagate → read with cursor (RYW through the real path, CT-35) ---
        long seq1 = putCommitted(serverBase, "svc/a", "v-a");
        HttpResponse<String> read1 = pollUntilServed(edgeBase, "svc/a", seq1);
        assertEquals("v-a", read1.body());
        assertEquals(String.valueOf(seq1),
                read1.headers().firstValue(EdgeHttpServer.HDR_VERSION).orElseThrow(),
                "the value carries its commit seq as the write version");
        long servedCursor = Long.parseLong(
                read1.headers().firstValue(EdgeHttpServer.HDR_CURSOR).orElseThrow());
        assertTrue(servedCursor >= seq1, "every read returns its cursor (>= the write seq)");

        // A second write: the carried-forward cursor stays monotonically satisfiable.
        long seq2 = putCommitted(serverBase, "svc/a", "v-a2");
        HttpResponse<String> read2 = pollUntilServed(edgeBase, "svc/a", seq2);
        assertEquals("v-a2", read2.body());

        // --- the chain really verified (CT-23 positive half) ---
        assertTrue(edge.core().appliedCount() >= 2, "deltas applied through the verifier");
        assertEquals(0, edge.core().verifyRejections(),
                "a correctly signed chain must not be rejected");

        // --- skew sanity (CT-08): same-host clocks, ordered stream → no implausible samples ---
        assertEquals(0, edge.metricsRegistry()
                        .counter(io.configd.edge.StalenessTracker.IMPLAUSIBLE_METRIC).get(),
                "no implausible frontier samples on an ordered same-clock stream");

        // --- the edge metric series moved ---
        String metrics = get(edgeBase + "/metrics").body();
        assertSeriesMoved(metrics, "edge_applied_total");
        assertSeriesMoved(metrics, "edge_reads_total");
        assertTrue(metrics.lines().anyMatch(l -> l.startsWith("edge_staleness_ms ")),
                "edge_staleness_ms gauge exported:\n" + grep(metrics, "edge_"));
    }

    @Test
    void edgeWithoutVerifyKeyRejectsTheSignedChainFailClosed() throws Exception {
        // CT-23 negative half (F-0052): the server signs every delta; an edge with NO
        // verify key configured must reject the signed chain fail-closed — never apply.
        server = startServer();
        edge = startEdge("edge-it-noverify", null, server.fanOutServer().localPort());
        String serverBase = "http://127.0.0.1:" + server.apiPort();

        putCommitted(serverBase, "svc/b", "v-b");
        await("signed delta rejected fail-closed",
                () -> edge.core().verifyRejections() > 0);
        assertEquals(0, edge.core().appliedCount(),
                "no signed delta may apply without verification");
        assertEquals(0, edge.core().currentVersion(), "the store never advanced");
    }

    @Test
    void staleHeaderAndReadinessDegradeAfterSourceLoss() throws Exception {
        server = startServer();
        Path verifyKey = exportVerifyKey();
        edge = startEdge("edge-it-stale", verifyKey, server.fanOutServer().localPort());
        String serverBase = "http://127.0.0.1:" + server.apiPort();
        String edgeBase = "http://127.0.0.1:" + edge.apiPort();

        long seq = putCommitted(serverBase, "svc/c", "v-c");
        pollUntilServed(edgeBase, "svc/c", seq);
        assertEquals(200, get(edgeBase + "/health/ready").statusCode(), "caught-up edge is ready");

        // Kill the source: heartbeats stop, the frontier freezes, staleness climbs.
        server.shutdown();
        server = null;

        // CT-03: >500ms behind → STALE → the header appears on served reads (the data is
        // still served — serve-stale-with-notification; only cursor-behind refuses).
        await("stale header appears on reads", () -> {
            HttpResponse<String> r = get(edgeBase + "/v1/config/svc/c");
            return r.statusCode() == 200
                    && r.headers().firstValue(EdgeHttpServer.HDR_STALE).isPresent();
        });

        // CT-04: the STALE transition incremented the contract-named counter — and it is
        // detected even though the edge has NO live stream (the disconnected-pump path).
        await("configd_edge_staleness_violation_total moved",
                () -> get(edgeBase + "/metrics").body().lines().anyMatch(l ->
                        l.startsWith("configd_edge_staleness_violation_total ")
                                && !l.endsWith(" 0")));

        // CT-05: >5s behind → DEGRADED → unhealthy to the load balancer; liveness stays up.
        await("readiness flips 503 at DEGRADED",
                () -> get(edgeBase + "/health/ready").statusCode() == 503);
        assertEquals(200, get(edgeBase + "/health/live").statusCode(),
                "liveness is process-liveness, not staleness");
    }

    // -----------------------------------------------------------------------
    // Helpers (deadline-polling; no sleep-as-sync)
    // -----------------------------------------------------------------------

    /**
     * Polls the edge read with {@code X-Configd-Cursor: seq} until served. Every non-200
     * along the way MUST be the consistent refusal (404 + cursor-behind) — never a stale
     * 200 below the cursor (the contract §3 refusal clause).
     */
    private HttpResponse<String> pollUntilServed(String edgeBase, String key, long seq)
            throws Exception {
        long deadline = System.nanoTime() + POLL_DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> r = get(edgeBase + "/v1/config/" + key,
                    EdgeHttpServer.HDR_CURSOR, String.valueOf(seq));
            if (r.statusCode() == 200) {
                long version = Long.parseLong(
                        r.headers().firstValue(EdgeHttpServer.HDR_VERSION).orElseThrow());
                assertTrue(version >= seq,
                        "NEVER serve below the cursor: version " + version + " < cursor " + seq);
                return r;
            }
            assertEquals(404, r.statusCode(), "catch-up responses are refusals, never errors");
            assertEquals("cursor-behind",
                    r.headers().firstValue(EdgeHttpServer.HDR_REFUSED).orElse("missing"),
                    "a cursor-ahead read during catch-up must carry the refusal header");
            Thread.onSpinWait();
        }
        fail("edge did not serve " + key + " at cursor " + seq + " within the deadline");
        return null;
    }

    private void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + POLL_DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // transient (e.g. connection refused during churn) — keep polling
            }
            Thread.onSpinWait();
        }
        fail("condition not reached within deadline: " + what);
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
        long deadline = System.nanoTime() + WRITE_DEADLINE.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(base + "/v1/config/" + key))
                        .timeout(WRITE_DEADLINE)
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
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IllegalStateException("write '" + key + "' not committed in time", last);
    }

    private static void assertSeriesMoved(String metrics, String name) {
        String line = metrics.lines().filter(l -> l.startsWith(name + " "))
                .findFirst().orElse(null);
        assertTrue(line != null && !line.endsWith(" 0"),
                name + " must be exported and have moved, was: " + line
                        + "\n" + grep(metrics, "edge_"));
    }

    private static String grep(String text, String needle) {
        StringBuilder sb = new StringBuilder();
        text.lines().filter(l -> l.contains(needle)).forEach(l -> sb.append(l).append('\n'));
        return sb.toString();
    }
}
