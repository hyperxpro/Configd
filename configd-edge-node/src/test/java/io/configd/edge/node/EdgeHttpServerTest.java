package io.configd.edge.node;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.edge.EdgeClientCore;
import io.configd.edge.StrongReadKeyClass;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level matrix for {@link EdgeHttpServer} over a REAL (deterministically clocked)
 * {@link EdgeClientCore} — no sockets to the fan-out side, frames are fed directly. Pins
 * the serving clauses: cursor echo (charter C2), cursor-behind consistent refusal
 * (contract §3 / CT-12 semantics), CT-03 stale header, CT-05 readiness gating, CT-37
 * strong-read fail-close (store-and-never-serve), and the INV-M1 observability seam.
 */
@Timeout(60)
class EdgeHttpServerTest {

    static final class TestClock implements Clock {
        long timeMs = 1_000_000L;
        @Override public long currentTimeMillis() { return timeMs; }
        @Override public long nanoTime() { return timeMs * 1_000_000L; }
        void advance(long ms) { timeMs += ms; }
    }

    private TestClock clock;
    private MetricsRegistry registry;
    private EdgeNodeMetrics metrics;
    private EdgeClientCore core;
    private EdgeHttpServer server;
    private HttpClient http;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        clock = new TestClock();
        registry = new MetricsRegistry();
        // Production-mode monitor: an INV-M1 refusal increments the violation counter
        // (asserted below) and never throws into the serving path.
        InvariantMonitor monitor = new InvariantMonitor(registry, false);
        metrics = new EdgeNodeMetrics(registry);
        core = new EdgeClientCore(clock, monitor, metrics.implausibleCounter(),
                StrongReadKeyClass.DEFAULT, EdgeClientCore.FrameSink.NONE,
                EdgeClientCore.DEFAULT_HEARTBEAT_MS, EdgeClientCore.DEFAULT_SILENCE_FACTOR);
        metrics.bind(core);
        server = new EdgeHttpServer(0, core, StrongReadKeyClass.DEFAULT,
                new PrometheusExporter(registry), metrics);
        server.start();
        base = "http://127.0.0.1:" + server.port();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Applies one Put notification at {@code seq} through the real core. */
    private void apply(long seq, String key, String value) {
        ConfigDelta delta = new ConfigDelta(seq - 1, seq,
                List.of(new ConfigMutation.Put(key, value.getBytes(StandardCharsets.UTF_8))));
        core.onFrame(new EdgeFrame.Notify(List.of(
                new CommitNotification(seq, clock.timeMs, delta))));
    }

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(base + path)).GET().timeout(Duration.ofSeconds(10));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    // -----------------------------------------------------------------------
    // Serving + cursor echo
    // -----------------------------------------------------------------------

    @Test
    void servedReadCarriesVersionAndCursor() throws Exception {
        apply(1, "svc/a", "v1");
        HttpResponse<String> resp = get("/v1/config/svc/a");
        assertEquals(200, resp.statusCode());
        assertEquals("v1", resp.body());
        assertEquals("1", resp.headers().firstValue(EdgeHttpServer.HDR_VERSION).orElseThrow());
        assertEquals("1", resp.headers().firstValue(EdgeHttpServer.HDR_CURSOR).orElseThrow(),
                "every read returns its cursor (charter C2)");
        assertTrue(resp.headers().firstValue(EdgeHttpServer.HDR_STALE).isEmpty(),
                "CURRENT edge must not set the stale header");
    }

    @Test
    void cursorAtCurrentVersionIsServed() throws Exception {
        apply(1, "svc/a", "v1");
        HttpResponse<String> resp = get("/v1/config/svc/a", EdgeHttpServer.HDR_CURSOR, "1");
        assertEquals(200, resp.statusCode(), "cursor == local version is satisfiable");
    }

    @Test
    void initialCursorZeroIsValidAndServed() throws Exception {
        // VersionCursor.INITIAL is version 0 — a first-read client sends cursor 0 and it
        // is always satisfiable, never a malformed-cursor 400 (the < 0 boundary).
        apply(1, "svc/a", "v1");
        HttpResponse<String> resp = get("/v1/config/svc/a", EdgeHttpServer.HDR_CURSOR, "0");
        assertEquals(200, resp.statusCode(), "cursor 0 (INITIAL) must be accepted");
        assertEquals("v1", resp.body());
    }

    // -----------------------------------------------------------------------
    // Consistent refusal (contract §3; CT-12 semantics)
    // -----------------------------------------------------------------------

    @Test
    void cursorBehindIsRefusedNeverServedStale() throws Exception {
        apply(1, "svc/a", "v1");
        long invBefore = registry.counter("invariant.violation.monotonic_read").get();

        HttpResponse<String> resp = get("/v1/config/svc/a", EdgeHttpServer.HDR_CURSOR, "5");
        assertEquals(404, resp.statusCode(), "refusal is 404, never a stale 200");
        assertEquals("cursor-behind",
                resp.headers().firstValue(EdgeHttpServer.HDR_REFUSED).orElseThrow());
        assertEquals("1", resp.headers().firstValue(EdgeHttpServer.HDR_CURSOR).orElseThrow(),
                "the refusal still reports the edge's cursor");

        assertEquals(1, registry.counter(
                "edge.read_refusals." + EdgeNodeMetrics.REASON_CURSOR_BEHIND).get());
        assertTrue(registry.counter("invariant.violation.monotonic_read").get() > invBefore,
                "the refused read routes through the monitor-wired store (INV-M1 seam)");
    }

    @Test
    void missingKeyIsPlain404WithoutRefusedHeader() throws Exception {
        apply(1, "svc/a", "v1");
        HttpResponse<String> resp = get("/v1/config/svc/nope", EdgeHttpServer.HDR_CURSOR, "1");
        assertEquals(404, resp.statusCode());
        assertTrue(resp.headers().firstValue(EdgeHttpServer.HDR_REFUSED).isEmpty(),
                "a true miss is not a refusal");
    }

    @Test
    void malformedCursorIs400() throws Exception {
        apply(1, "svc/a", "v1");
        assertEquals(400, get("/v1/config/svc/a", EdgeHttpServer.HDR_CURSOR, "abc").statusCode());
        assertEquals(400, get("/v1/config/svc/a", EdgeHttpServer.HDR_CURSOR, "-3").statusCode());
    }

    // -----------------------------------------------------------------------
    // CT-03 stale header / CT-05 readiness
    // -----------------------------------------------------------------------

    @Test
    void staleHeaderSetOnAllReadsWhenStalePlus() throws Exception {
        apply(1, "svc/a", "v1");
        clock.advance(600); // past the 500ms STALE threshold (frontier static, no heartbeats)
        HttpResponse<String> hit = get("/v1/config/svc/a");
        assertEquals(200, hit.statusCode(), "stale data is still served (with notification)");
        assertEquals("true", hit.headers().firstValue(EdgeHttpServer.HDR_STALE).orElseThrow());
        HttpResponse<String> miss = get("/v1/config/svc/nope");
        assertEquals("true", miss.headers().firstValue(EdgeHttpServer.HDR_STALE).orElseThrow(),
                "the header is set on ALL reads while STALE+ (contract §2)");
    }

    @Test
    void livenessAlways200AndReadinessGatesOnDegraded() throws Exception {
        // Boot: no frontier yet → DISCONNECTED → not ready, but live.
        assertEquals(200, get("/health/live").statusCode());
        assertEquals(503, get("/health/ready").statusCode(),
                "a never-synced edge must not report ready");

        apply(1, "svc/a", "v1"); // frontier at wall-now → CURRENT
        assertEquals(200, get("/health/ready").statusCode());

        clock.advance(600); // STALE: degraded threshold not reached → still ready
        assertEquals(200, get("/health/ready").statusCode(), "STALE alone keeps serving");

        clock.advance(5_000); // past 5s → DEGRADED → unhealthy to the LB (CT-05)
        assertEquals(503, get("/health/ready").statusCode());
        assertEquals(200, get("/health/live").statusCode(), "liveness is unconditional");
    }

    // -----------------------------------------------------------------------
    // CT-37 strong reads: store-and-never-serve
    // -----------------------------------------------------------------------

    @Test
    void strongReadKeyFailsClosedEvenThoughStored() throws Exception {
        apply(1, "secure/kill-switch", "armed");
        // Stored (ADR-0038 always-store)…
        assertTrue(core.get("secure/kill-switch").found(), "strong-read keys ARE stored");
        // …but NEVER served from bounded-stale edge state.
        HttpResponse<String> resp = get("/v1/config/secure/kill-switch");
        assertEquals(503, resp.statusCode());
        assertEquals("strong-read",
                resp.headers().firstValue(EdgeHttpServer.HDR_FAIL_CLOSED).orElseThrow());
        assertFalse(resp.body().contains("armed"), "the value must not leak in the body");
        assertEquals(1, registry.counter(
                "edge.read_refusals." + EdgeNodeMetrics.REASON_STRONG_READ).get());

        // The refusal is unconditional — a satisfiable cursor does not unlock it.
        assertEquals(503, get("/v1/config/secure/kill-switch",
                EdgeHttpServer.HDR_CURSOR, "1").statusCode());
    }

    // -----------------------------------------------------------------------
    // Surface hygiene
    // -----------------------------------------------------------------------

    @Test
    void nonGetIs405AndMissingKeyPathIs400() throws Exception {
        for (String path : List.of("/v1/config/svc/a", "/health/live", "/health/ready", "/metrics")) {
            HttpRequest put = HttpRequest.newBuilder().uri(URI.create(base + path))
                    .PUT(HttpRequest.BodyPublishers.ofString("x")).build();
            assertEquals(405, http.send(put, HttpResponse.BodyHandlers.ofString()).statusCode(),
                    "every edge endpoint is GET-only: " + path);
        }
        assertEquals(400, get("/v1/config/").statusCode());
    }

    @Test
    void metricsEndpointExportsTheContractualSeries() throws Exception {
        apply(1, "svc/a", "v1");
        get("/v1/config/svc/a");
        String text = get("/metrics").body();
        for (String series : List.of(
                "edge_staleness_ms", "edge_staleness_state",
                "configd_edge_staleness_violation_total", "edge_staleness_implausible_total",
                "edge_cursor_lag", "edge_applied_total", "edge_gaps_total",
                "edge_snapshots_applied_total", "edge_reads_total",
                "edge_read_refusals_cursor_behind_total", "edge_read_refusals_strong_read_total",
                "edge_reconnects_total", "edge_rebootstrap_triggered_total",
                "edge_verify_rejections_total")) {
            assertTrue(text.lines().anyMatch(l -> l.startsWith(series + " ")),
                    "series must be exported from the first scrape (RR-013): " + series);
        }
        assertTrue(text.lines().anyMatch(l -> l.startsWith("edge_reads_total ")
                        && !l.endsWith(" 0")),
                "edge_reads_total must have moved");
    }
}
