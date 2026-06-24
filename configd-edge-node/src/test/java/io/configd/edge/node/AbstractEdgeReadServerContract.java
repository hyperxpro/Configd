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
 * The edge read-serving contract, run identically against <b>both</b> transports (ADR-0043 / DR-N2):
 * {@link EdgeHttpServerTest} starts the JDK {@link EdgeHttpServer}, {@link NettyEdgeHttpServerTest}
 * starts the Netty {@link NettyEdgeHttpServer}. Because both adapters delegate to the same
 * {@link EdgeReadHandler}, every clause here — cursor echo (charter C2), cursor-behind consistent
 * refusal (CT-12), CT-03 stale header on ALL reads, CT-05 readiness gating, CT-37 strong-read
 * fail-close, ADR-0040 not-subscribed refusal, F-S7-TLS-2 {@code /metrics} Bearer gate, method
 * validation, and the INV-M1 observability seam — must hold byte-for-byte on each. A control that
 * passes on JDK but not Netty is a migration regression (the worst outcome — charter §3 rule 1).
 */
@Timeout(60)
abstract class AbstractEdgeReadServerContract {

    /** A started server: its bound port and a stop hook. */
    interface ServerHandle {
        int port();

        void stop();
    }

    /** Starts the transport under test on {@code port} (0 = ephemeral). */
    abstract ServerHandle start(int port, EdgeClientCore core, StrongReadKeyClass strongReadKeyClass,
                                PrometheusExporter exporter, EdgeNodeMetrics metrics) throws Exception;

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
    private ServerHandle server;
    private HttpClient http;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        clock = new TestClock();
        registry = new MetricsRegistry();
        InvariantMonitor monitor = new InvariantMonitor(registry, false);
        metrics = new EdgeNodeMetrics(registry);
        core = new EdgeClientCore(clock, monitor, metrics.implausibleCounter(),
                StrongReadKeyClass.DEFAULT, EdgeClientCore.FrameSink.NONE,
                EdgeClientCore.DEFAULT_HEARTBEAT_MS, EdgeClientCore.DEFAULT_SILENCE_FACTOR);
        metrics.bind(core);
        server = start(0, core, StrongReadKeyClass.DEFAULT, new PrometheusExporter(registry), metrics);
        base = "http://127.0.0.1:" + server.port();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
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
    void servedReadRecordsLatencyHistogram() throws Exception {
        apply(1, "svc/a", "v1");
        assertEquals(200, get("/v1/config/svc/a").statusCode());
        // The read-latency sample is recorded in a finally that runs after the response is handed to
        // the transport, so a single immediate scrape can race it; poll until it lands (2 s bound
        // still fails loudly if never recorded — hides no real regression).
        java.util.regex.Pattern countPat = java.util.regex.Pattern
                .compile("(?m)^configd_edge_read_seconds_count\\s+(\\S+)");
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        String scrape;
        while (true) {
            scrape = new PrometheusExporter(registry,
                    io.configd.observability.ConfigdMetrics.edgeProcessHistogramSchedules()).export();
            java.util.regex.Matcher c = countPat.matcher(scrape);
            if ((c.find() && Double.parseDouble(c.group(1)) >= 1.0) || System.nanoTime() >= deadlineNanos) {
                break;
            }
            Thread.sleep(5);
        }
        assertTrue(scrape.contains("configd_edge_read_seconds_bucket{le=\"0.001\"}"),
                "the le=0.001 bucket the edge-read burn-rate alert queries must be emitted:\n" + scrape);
        java.util.regex.Matcher m = countPat.matcher(scrape);
        assertTrue(m.find() && Double.parseDouble(m.group(1)) >= 1.0,
                "a served read must record a configd_edge_read_seconds sample:\n" + scrape);
    }

    // -----------------------------------------------------------------------
    // F-S7-TLS-2: edge /metrics scrape-token auth (the AS-5 reconnaissance leak)
    // -----------------------------------------------------------------------

    @Test
    void metricsScrapeTokenGatesUnauthenticatedScrape() throws Exception {
        String prop = "configd.edge.metricsScrapeToken";
        String saved = System.getProperty(prop);
        ServerHandle gated = null;
        try {
            System.setProperty(prop, "scrape-secret"); // read by the field initializer at construction
            gated = start(0, core, StrongReadKeyClass.DEFAULT, new PrometheusExporter(registry), metrics);
            String g = "http://127.0.0.1:" + gated.port();

            // No token → 401 (the staleness/reconnect/version reconnaissance leak is closed).
            HttpResponse<String> noTok = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(g + "/metrics")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, noTok.statusCode(), "edge /metrics must require the configured scrape token");
            assertTrue(noTok.headers().firstValue("WWW-Authenticate").isPresent(),
                    "a 401 must advertise Bearer auth");

            // Wrong token → 401.
            HttpResponse<String> wrong = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(g + "/metrics")).header("Authorization", "Bearer nope").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, wrong.statusCode(), "a wrong scrape token is refused");

            // Correct token → 200 + the exposition.
            HttpResponse<String> ok = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(g + "/metrics")).header("Authorization", "Bearer scrape-secret").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode(), "the configured scrape token is accepted");
        } finally {
            if (gated != null) gated.stop();
            if (saved == null) System.clearProperty(prop); else System.setProperty(prop, saved);
        }
    }

    @Test
    void metricsOpenWhenNoScrapeTokenConfigured() throws Exception {
        // Backward compat: the @BeforeEach server was built with no token → /metrics stays open (200).
        assertEquals(200, get("/metrics").statusCode(),
                "with no scrape token configured, /metrics stays open (legacy / infra segmentation)");
    }

    @Test
    void cursorAtCurrentVersionIsServed() throws Exception {
        apply(1, "svc/a", "v1");
        HttpResponse<String> resp = get("/v1/config/svc/a", EdgeHttpServer.HDR_CURSOR, "1");
        assertEquals(200, resp.statusCode(), "cursor == local version is satisfiable");
    }

    @Test
    void initialCursorZeroIsValidAndServed() throws Exception {
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
        assertTrue(core.get("secure/kill-switch").found(), "strong-read keys ARE stored");
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
    void routingIsExactMatchForFixedEndpoints() throws Exception {
        // ADR-0043 DR-N4: routing tightened from the JDK HttpServer's longest-PREFIX context match to
        // EXACT match (a single dispatcher over EdgeReadHandler). Suffix variants of the fixed health/
        // metrics endpoints — which the prefix match would have served — now 404. Intentional
        // hardening: e.g. /metricsXYZ must NOT reach the Prometheus exposition. Pinned on both
        // transports (the JDK adapter now routes through the same EdgeReadHandler, so they agree).
        apply(1, "svc/a", "v1");
        assertEquals(200, get("/health/live").statusCode(), "the canonical endpoint is served");
        assertEquals(200, get("/metrics").statusCode());
        assertEquals(404, get("/health/livez").statusCode(), "suffix variant of /health/live → 404");
        assertEquals(404, get("/health/live/x").statusCode(), "subpath of /health/live → 404");
        assertEquals(404, get("/metricsXYZ").statusCode(),
                "suffix variant of /metrics must not reach the exposition");
        assertEquals(404, get("/metrics/foo").statusCode(), "subpath of /metrics → 404");
        assertEquals(404, get("/nope").statusCode(), "an unmatched path → 404");
    }

    @Test
    void notSubscribedKeyRefusesDistinctlyWhileInSliceMissIsAuthoritative() throws Exception {
        core.addSubscription("svc/");
        apply(1, "svc/a", "v1");

        HttpResponse<String> refused = get("/v1/config/other/x");
        assertEquals(404, refused.statusCode());
        assertEquals("not-subscribed",
                refused.headers().firstValue(EdgeHttpServer.HDR_REFUSED).orElse("missing"));
        assertEquals(1, registry.counter("edge.read_refusals.not_subscribed").get());

        HttpResponse<String> miss = get("/v1/config/svc/absent");
        assertEquals(404, miss.statusCode());
        assertTrue(miss.headers().firstValue(EdgeHttpServer.HDR_REFUSED).isEmpty(),
                "an in-slice miss is authoritative non-existence, not a refusal");

        HttpResponse<String> strong = get("/v1/config/secure/x");
        assertEquals(503, strong.statusCode());
        assertEquals(1, registry.counter("edge.read_refusals.not_subscribed").get(),
                "the strong-read refusal is its own series, not not_subscribed");
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
                "edge_read_refusals_not_subscribed_total",
                "edge_poison_retries_total", "configd_edge_poison_pill_total",
                "configd_edge_poison_pill_terminal_total",
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
