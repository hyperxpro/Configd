package io.configd.edge.node;

import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.server.ConfigdServer;
import io.configd.server.ServerConfig;
import io.configd.server.fanout.FanOutServer;
import io.configd.server.fanout.RegistryFanOutSessionMetrics;
import io.configd.observability.MetricsRegistry;
import io.configd.store.SigningKeyStore;
import io.configd.store.VerifyKeyExporter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CT-11 / CT-12 at process level — multi-endpoint failover with consistent refusal:
 * two fan-out endpoints over the SAME commit-notification boundary (the ADR-0034 seams a
 * second fan-out node would drain in production), the subscribed one killed mid-stream.
 * The edge reconnects to the other carrying its resume cursor (the §3
 * {@code failoverResumeCursor} reserved field); reads stay cursor-monotonic across the
 * reconnect; and during catch-up, cursor-behind reads REFUSE (404 +
 * {@code X-Configd-Refused: cursor-behind}) — the consistent-refusal semantics the
 * contract pass amended into §3 steps 3–4 (ADR-0035 + ADR-0039; NEVER
 * block-and-serve-stale).
 */
@Timeout(120)
class EdgeFailoverTest {

    private static final Pattern COMMITTED_SEQ = Pattern.compile("seq=(\\d+)");
    private static final Duration DEADLINE = Duration.ofSeconds(60);

    @TempDir
    Path tempDir;

    private ConfigdServer server;
    private FanOutServer endpointB;
    private EdgeNodeMain edge;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void tearDown() {
        if (edge != null) {
            edge.shutdown();
        }
        if (endpointB != null) {
            endpointB.close();
        }
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void killSubscribedEndpointMidStreamFailsOverCursorMonotonicallyWithRefusalsDuringCatchUp()
            throws Exception {
        // --- control plane + endpoint A (the server's own --edge-port fan-out) ---
        Path signingKey = tempDir.resolve("signing-key.bin");
        SigningKeyStore.loadOrCreate(signingKey);
        server = ConfigdServer.start(new ServerConfig(
                NodeId.of(0), tempDir.resolve("server-data"), Set.of(), "127.0.0.1",
                0, 0, null, null, null, null, Map.of(), signingKey, Set.of("secure/"), 0));
        Path verifyKey = tempDir.resolve("verify-key.der");
        VerifyKeyExporter.export(signingKey, verifyKey);
        int portA = server.fanOutServer().localPort();

        // Reserve a fixed port for endpoint B, NOT yet started — so the
        // dead-endpoint refusal window below is deterministic, not a race.
        int portB;
        try (ServerSocket reserve = new ServerSocket(0)) {
            portB = reserve.getLocalPort();
        }

        // --- the edge, configured with BOTH endpoints (A first) ---
        edge = EdgeNodeMain.start(new EdgeNodeConfig(
                "edge-failover",
                List.of(InetSocketAddress.createUnresolved("127.0.0.1", portA),
                        InetSocketAddress.createUnresolved("127.0.0.1", portB)),
                0, tempDir.resolve("edge-data"), verifyKey, List.of(), null, null, null,
                50L, EdgeNodeConfig.DEFAULT_HEARTBEAT_SILENCE_FACTOR, EdgeNodeConfig.DEFAULT_POISON_MAX_RETRIES));
        String serverBase = "http://127.0.0.1:" + server.apiPort();
        String edgeBase = "http://127.0.0.1:" + edge.apiPort();

        // --- steady state over A: write → propagate → cursor-bound read serves ---
        long seq1 = putCommitted(serverBase, "svc/x", "v1");
        HttpResponse<String> steady = pollUntilServed(edgeBase, "svc/x", seq1);
        assertEquals("v1", steady.body());

        // --- kill the subscribed endpoint MID-STREAM ---
        server.fanOutServer().close();

        // A write that commits at the control plane while the edge has no live stream.
        long seq2 = putCommitted(serverBase, "svc/x", "v2");

        // CT-12 consistent refusal, deterministic window (no endpoint is reachable):
        // cursor-behind reads REFUSE — never block, never serve the stale v1 under a
        // seq2 cursor. The cursorless read still serves (stale serving is contract-legal;
        // only cursor-behind refuses).
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> refused = get(edgeBase + "/v1/config/svc/x",
                    EdgeHttpServer.HDR_CURSOR, String.valueOf(seq2));
            assertEquals(404, refused.statusCode(),
                    "during catch-up a cursor-behind read must refuse, never serve stale");
            assertEquals("cursor-behind",
                    refused.headers().firstValue(EdgeHttpServer.HDR_REFUSED).orElseThrow());
        }
        HttpResponse<String> cursorless = get(edgeBase + "/v1/config/svc/x");
        assertEquals(200, cursorless.statusCode());
        assertEquals("v1", cursorless.body(), "cursorless reads may serve the old value");

        // --- bring up endpoint B over the SAME ADR-0034 seams; the edge fails over ---
        endpointB = new FanOutServer(
                new InetSocketAddress("127.0.0.1", portB), null,
                server.commitNotificationSource(), server.replaySource(),
                FanOutConfig.defaults(), FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                new RegistryFanOutSessionMetrics(new MetricsRegistry()), Clock.system());
        endpointB.start();

        // The edge reconnects to B carrying its cursor and catches up; the read at the
        // seq2 cursor is served — and every response on the way was either the refusal
        // or a version >= cursor (cursor-monotonic across the reconnect, enforced in
        // pollUntilServed).
        HttpResponse<String> resumed = pollUntilServed(edgeBase, "svc/x", seq2);
        assertEquals("v2", resumed.body());
        long version = Long.parseLong(
                resumed.headers().firstValue(EdgeHttpServer.HDR_VERSION).orElseThrow());
        assertTrue(version >= seq2, "post-failover read is at or past the failover cursor");

        // The reconnect machinery actually ran and is observable.
        String metrics = get(edgeBase + "/metrics").body();
        assertTrue(metrics.lines().anyMatch(l -> l.startsWith("edge_reconnects_total ")
                        && !l.endsWith(" 0")),
                "edge_reconnects_total must have moved across the failover:\n"
                        + metrics.lines().filter(l -> l.startsWith("edge_"))
                                .reduce("", (a, b) -> a + b + "\n"));

        // And the failover never regressed the store (monotonic cursor, INV-M1).
        assertTrue(edge.core().currentVersion() >= seq2);
    }

    // -----------------------------------------------------------------------
    // Helpers (deadline-polling; no sleep-as-sync)
    // -----------------------------------------------------------------------

    /** Polls until served at the cursor; every non-200 must be the consistent refusal. */
    private HttpResponse<String> pollUntilServed(String edgeBase, String key, long seq)
            throws Exception {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
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
            assertEquals(404, r.statusCode());
            assertEquals("cursor-behind",
                    r.headers().firstValue(EdgeHttpServer.HDR_REFUSED).orElse("missing"),
                    "catch-up responses are refusals (consistent-refusal semantics)");
            Thread.onSpinWait();
        }
        fail("edge did not serve " + key + " at cursor " + seq + " within the deadline");
        return null;
    }

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
        }
        throw new IllegalStateException("write '" + key + "' not committed in time");
    }
}
