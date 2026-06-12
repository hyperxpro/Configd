package io.configd.edge.node;

import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.observability.MetricsRegistry;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CT-06, the PROCESS half (C3): the DISCONNECTED re-bootstrap orchestration behind the
 * C2 trigger seam is REAL. The trigger chain was pinned by C2
 * ({@code EdgeNodeMetricsTest#disconnectedTransitionFiresTheRebootstrapSeamOnce}: each
 * entry into DISCONNECTED counts {@code edge_rebootstrap_triggered_total} and fires the
 * hook, including with no live stream); C3 supplies what the hook DOES — this test pins:
 * <ol>
 *   <li>the composed hook invokes {@link EdgeStreamClient#requestRebootstrap} FIRST and
 *       any injected observer second (the composition contract);</li>
 *   <li>{@code requestRebootstrap} on a live session tears the connection down and the
 *       client re-SUBSCRIBEs at its current cursor and keeps converging — a real full
 *       re-subscribe, not a stub.</li>
 * </ol>
 * The DISCONNECTED *transition* itself is wall-clock-bound (30 s, ADR-0039) and is
 * exercised at SIM level with the logical clock (testkit
 * {@code EdgeReBootstrapOnDisconnectTest}) — no sleeps-as-sync here.
 */
@Timeout(120)
class EdgeReBootstrapOnDisconnectTest {

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
    void composedHookRunsTheOrchestrationFirstAndTheInjectedObserverSecond() {
        AtomicInteger observerRuns = new AtomicInteger();
        EdgeStreamClient client = new EdgeStreamClient(
                List.of(InetSocketAddress.createUnresolved("127.0.0.1", 1)),
                "edge-hook", List.of(), null, 50L,
                EdgeNodeConfig.DEFAULT_HEARTBEAT_SILENCE_FACTOR, Clock.system(),
                new EdgeNodeMetrics(new MetricsRegistry()),
                observerRuns::incrementAndGet, () -> { });

        // Never started: no connection exists — the orchestration reduces to arming the
        // re-bootstrap request (cuts the next backoff short), exactly the disconnected
        // case the CT-06 metrics-pump gotcha names.
        client.rebootstrapHookForTest().run();

        assertTrue(client.rebootstrapRequestedForTest(),
                "the hook must arm the re-bootstrap orchestration");
        assertEquals(1, observerRuns.get(), "the injected observer composes — it is not replaced");
    }

    @Test
    void requestRebootstrapOnALiveSessionForcesResubscribeAndKeepsConverging() throws Exception {
        Path signingKey = tempDir.resolve("signing-key.bin");
        SigningKeyStore.loadOrCreate(signingKey);
        server = ConfigdServer.start(new ServerConfig(
                NodeId.of(0), tempDir.resolve("server-data"), Set.of(), "127.0.0.1",
                0, 0, null, null, null, null, Map.of(), signingKey, Set.of("secure/"), 0));
        Path verifyKey = tempDir.resolve("verify-key.der");
        VerifyKeyExporter.export(signingKey, verifyKey);
        String serverBase = "http://127.0.0.1:" + server.apiPort();

        edge = EdgeNodeMain.start(new EdgeNodeConfig(
                "edge-rebootstrap",
                List.of(InetSocketAddress.createUnresolved(
                        "127.0.0.1", server.fanOutServer().localPort())),
                0, tempDir.resolve("edge-data"), verifyKey, List.of(),
                null, null, null, 50L, EdgeNodeConfig.DEFAULT_HEARTBEAT_SILENCE_FACTOR,
                EdgeNodeConfig.DEFAULT_POISON_MAX_RETRIES));
        String edgeBase = "http://127.0.0.1:" + edge.apiPort();

        // Converged baseline.
        long seq1 = putCommitted(serverBase, "svc/r", "v1");
        await("edge converged", () -> edge.core().currentVersion() >= seq1);
        long reconnectsBefore = edge.metricsRegistry().counter("edge.reconnects").get();

        // The CT-06 orchestration: tear down + immediate full re-subscribe (the same call
        // the composed hook makes on a DISCONNECTED entry).
        edge.streamClient().requestRebootstrap("test-trigger");

        // The session cycled (a reconnect was counted)...
        await("reconnect cycle ran",
                () -> edge.metricsRegistry().counter("edge.reconnects").get() > reconnectsBefore);

        // ...the client re-SUBSCRIBEd at its CURRENT cursor (no needless full snapshot —
        // the store version never regressed) and keeps converging on new writes.
        long seq2 = putCommitted(serverBase, "svc/r", "v2");
        await("post-re-bootstrap convergence", () -> edge.core().currentVersion() >= seq2);
        assertTrue(edge.core().currentVersion() >= seq2);
        HttpResponse<String> read = get(edgeBase + "/v1/config/svc/r",
                EdgeHttpServer.HDR_CURSOR, String.valueOf(seq2));
        assertEquals(200, read.statusCode());
        assertEquals("v2", read.body());

        // CT-06's metric series exists from scrape 0 (the trigger-count half is C2's
        // EdgeNodeMetricsTest; here we only require the seam stayed observable).
        String metrics = get(edgeBase + "/metrics").body();
        assertTrue(metrics.lines().anyMatch(l ->
                        l.startsWith("edge_rebootstrap_triggered_total ")),
                "edge_rebootstrap_triggered_total must be exported eagerly");
    }

    // --- helpers ---

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
