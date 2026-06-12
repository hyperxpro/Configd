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
 * The ADR-0040 §2 read-refusal taxonomy on a PREFIX-SUBSCRIBED edge (CT-32's
 * negative-caching descope premise; C3 design §3): within the subscribed slice a store
 * miss IS authoritative non-existence (plain 404, no refusal header — the lock-free HAMT
 * miss path is the "negative cache"); outside the slice the edge holds no authoritative
 * answer and refuses DISTINCTLY ({@code 404 + X-Configd-Refused: not-subscribed},
 * {@code edge_read_refusals_not_subscribed_total}) — never an ambiguous miss a client
 * could mistake for non-existence. Strong-read keys keep their own fail-close (503,
 * CT-37) with precedence over the subscription check.
 */
@Timeout(120)
class NotSubscribedReadTest {

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
    void missesInsideTheSliceAreAuthoritativeOutsideTheSliceRefusesDistinctly() throws Exception {
        Path signingKey = tempDir.resolve("signing-key.bin");
        SigningKeyStore.loadOrCreate(signingKey);
        server = ConfigdServer.start(new ServerConfig(
                NodeId.of(0), tempDir.resolve("server-data"), Set.of(), "127.0.0.1",
                0, 0, null, null, null, null, Map.of(), signingKey, Set.of("secure/"), 0));
        Path verifyKey = tempDir.resolve("verify-key.der");
        VerifyKeyExporter.export(signingKey, verifyKey);

        // Edge subscribed to svc/ ONLY (the ADR-0038 storage filter).
        edge = EdgeNodeMain.start(new EdgeNodeConfig(
                "edge-notsub",
                List.of(InetSocketAddress.createUnresolved(
                        "127.0.0.1", server.fanOutServer().localPort())),
                0, tempDir.resolve("edge-data"), verifyKey, List.of("svc/"),
                null, null, null, 50L, EdgeNodeConfig.DEFAULT_HEARTBEAT_SILENCE_FACTOR,
                EdgeNodeConfig.DEFAULT_POISON_MAX_RETRIES));
        String serverBase = "http://127.0.0.1:" + server.apiPort();
        String edgeBase = "http://127.0.0.1:" + edge.apiPort();

        // One in-slice write and one out-of-slice write; the chain still advances for
        // both at the edge (full signed chain, storage filter only).
        long seqIn = putCommitted(serverBase, "svc/present", "in-slice");
        long seqOut = putCommitted(serverBase, "other/hidden", "out-of-slice");
        await("edge caught up past both writes",
                () -> edge.core().currentVersion() >= Math.max(seqIn, seqOut));

        // (1) In-slice hit serves.
        HttpResponse<String> hit = get(edgeBase + "/v1/config/svc/present");
        assertEquals(200, hit.statusCode());
        assertEquals("in-slice", hit.body());

        // (2) In-slice MISS is authoritative non-existence: plain 404, NO refusal header
        //     — the negative-caching descope's load-bearing premise (ADR-0040 §2).
        HttpResponse<String> miss = get(edgeBase + "/v1/config/svc/absent");
        assertEquals(404, miss.statusCode());
        assertTrue(miss.headers().firstValue(EdgeHttpServer.HDR_REFUSED).isEmpty(),
                "an in-slice miss is an authoritative answer, not a refusal");

        // (3) Out-of-slice read refuses DISTINCTLY — even though the key EXISTS upstream
        //     and its chain version passed through this edge (stored: NO; refused: YES).
        HttpResponse<String> refused = get(edgeBase + "/v1/config/other/hidden");
        assertEquals(404, refused.statusCode());
        assertEquals("not-subscribed",
                refused.headers().firstValue(EdgeHttpServer.HDR_REFUSED).orElse("missing"));

        // (4) Strong-read keys keep the 503 fail-close (CT-37) with PRECEDENCE — secure/
        //     is outside svc/ but must never surface as a mere not-subscribed 404.
        HttpResponse<String> strong = get(edgeBase + "/v1/config/secure/killswitch");
        assertEquals(503, strong.statusCode());
        assertEquals("strong-read",
                strong.headers().firstValue(EdgeHttpServer.HDR_FAIL_CLOSED).orElse("missing"));

        // (5) The refusal is observable on its own per-reason series.
        String metrics = get(edgeBase + "/metrics").body();
        assertTrue(metrics.lines().anyMatch(l ->
                        l.startsWith("edge_read_refusals_not_subscribed_total ")
                                && !l.endsWith(" 0")),
                "edge_read_refusals_not_subscribed_total must have moved:\n"
                        + metrics.lines().filter(l -> l.contains("read_refusals"))
                                .reduce("", (a, b) -> a + b + "\n"));
    }

    // --- helpers (the EdgeNodeIntegrationTest deadline-polling discipline) ---

    private void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // transient during churn — keep polling
            }
            Thread.onSpinWait();
        }
        fail("condition not reached within deadline: " + what);
    }

    private HttpResponse<String> get(String url) {
        try {
            return http.send(HttpRequest.newBuilder().uri(URI.create(url)).GET()
                            .timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
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
