package io.configd.edge.node;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.configd.edge.EdgeClientCore;
import io.configd.edge.StalenessTracker;
import io.configd.edge.StrongReadKeyClass;
import io.configd.edge.VersionCursor;
import io.configd.observability.PrometheusExporter;
import io.configd.store.ReadResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;

/**
 * The edge node's read-serving surface (C2 design §3.4/§4): JDK {@link HttpServer}
 * (the HttpApiServer pattern — virtual-thread executor, context-per-endpoint).
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /v1/config/{key}} — cursor/staleness-governed reads (below);</li>
 *   <li>{@code GET /health/live} — always 200 (process liveness);</li>
 *   <li>{@code GET /health/ready} — 503 when the staleness state is DEGRADED or worse
 *       (CT-05: the edge reports unhealthy to its load balancer), else 200;</li>
 *   <li>{@code GET /metrics} — Prometheus exposition via the existing
 *       {@link PrometheusExporter}.</li>
 * </ul>
 *
 * <h2>Read semantics (contract §2/§3; ADR-0035 + ADR-0039 consistent refusal)</h2>
 * <ul>
 *   <li>The client's cursor arrives via {@code X-Configd-Cursor} (a plain applied-mutation
 *       seq); every response carries {@code X-Configd-Cursor} = this edge's current store
 *       version (the cursor to carry forward — charter C2 "every read returns its cursor")
 *       and a hit carries {@code X-Configd-Version} = the value's write version;</li>
 *   <li><b>cursor-behind → 404 + {@code X-Configd-Refused: cursor-behind}</b>: the edge
 *       NEVER serves stale on a cursor-behind read, uniformly across steady state,
 *       catch-up, and failover (the contract §3 refusal semantics, amended in the
 *       contract pass per the C2-4 ruling). The refused read still routes through the
 *       monitor-wired store so {@code invariant.violation.monotonic_read} fires (INV-M1
 *       observability seam — C2-1);</li>
 *   <li>{@code X-Configd-Stale: true} on ALL read responses while the edge is STALE or
 *       worse (CT-03) — a stale CURRENT-cursor read is still SERVED (contract §2: stale
 *       data with notification), the header is the client's signal;</li>
 *   <li><b>strong-read keys (CT-37, store-and-never-serve)</b>: 503 +
 *       {@code X-Fail-Closed: strong-read} before the store is consulted — the value is
 *       stored (ADR-0038 suppression detectability) but never served from bounded-stale
 *       edge state; clients go to the control plane's linearizable path (RR-020);</li>
 *   <li><b>not-subscribed keys (ADR-0040 §2, C3)</b>: 404 +
 *       {@code X-Configd-Refused: not-subscribed} before the store is consulted — within
 *       the subscribed slice a miss IS authoritative non-existence (the negative-caching
 *       descope), outside it this edge has no authoritative answer and says so
 *       distinctly.</li>
 * </ul>
 *
 * <h2>Hot-path honesty (CT-34)</h2>
 * The §3 hot-path law binds the in-process read path ({@code LocalConfigStore.get} —
 * lock-free, zero steady-state allocation, JMH-gc-profiled by
 * {@code LocalConfigStoreReadBenchmark}). THIS HTTP shell allocates per request (exchange,
 * headers, strings) and is honestly not the law's scope; its logging is counters-only on
 * the request path (no per-request INFO logging).
 */
public final class EdgeHttpServer {

    /** Request/response header carrying the client's monotonic-read cursor. */
    public static final String HDR_CURSOR = "X-Configd-Cursor";
    /** Response header: the served value's write version. */
    public static final String HDR_VERSION = "X-Configd-Version";
    /** Response header on a cursor-behind refusal. */
    public static final String HDR_REFUSED = "X-Configd-Refused";
    /** Response header set on all reads while STALE+. */
    public static final String HDR_STALE = "X-Configd-Stale";
    /** Response header on the strong-read fail-close (RR-020-consistent). */
    public static final String HDR_FAIL_CLOSED = "X-Fail-Closed";

    private static final String CONFIG_PREFIX = "/v1/config/";

    private final HttpServer server;

    /**
     * F-S7-TLS-2 (edge {@code /metrics} exposure, Low): optional Bearer-token scrape secret for
     * {@code GET /metrics}, mirroring the control-plane F-0055 gate. When set (system property
     * {@code configd.edge.metricsScrapeToken}), an unauthenticated scrape is refused 401 — the edge
     * Prometheus surface no longer leaks staleness/reconnect/version reconnaissance (threat AS-5) to
     * anyone who can reach the port. Null = open (legacy; rely on infra segmentation instead). Served
     * on the edge HTTP request vthread; the edge process runs no consensus, so RR-002 is N/A.
     */
    private final String metricsScrapeToken = System.getProperty("configd.edge.metricsScrapeToken");

    /**
     * @param port               the bind port (0 = ephemeral; see {@link #port()})
     * @param core               the edge client core (lock-free read path + staleness)
     * @param strongReadKeyClass the CT-37 strong-read predicate (the same shared class the
     *                           storage filter uses — one source of truth)
     * @param exporter           the Prometheus exporter over the process registry
     * @param metrics            the edge metric series (reads/refusals)
     */
    public EdgeHttpServer(int port, EdgeClientCore core,
                          StrongReadKeyClass strongReadKeyClass,
                          PrometheusExporter exporter, EdgeNodeMetrics metrics)
            throws IOException {
        Objects.requireNonNull(core, "core must not be null");
        Objects.requireNonNull(strongReadKeyClass, "strongReadKeyClass must not be null");
        Objects.requireNonNull(exporter, "exporter must not be null");
        Objects.requireNonNull(metrics, "metrics must not be null");
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(CONFIG_PREFIX, new ConfigReadHandler(core, strongReadKeyClass, metrics));
        server.createContext("/health/live", EdgeHttpServer::handleLive);
        server.createContext("/health/ready", exchange -> handleReady(exchange, core));
        server.createContext("/metrics", exchange -> handleMetrics(exchange, exporter, metricsScrapeToken));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    /** Starts the HTTP server. */
    public void start() {
        server.start();
    }

    /** The actual bound port (resolves an ephemeral port 0 after {@link #start()}). */
    public int port() {
        return server.getAddress().getPort();
    }

    /** Stops the server, waiting up to {@code delaySeconds} for in-flight requests. */
    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
    }

    // -----------------------------------------------------------------------
    // GET /v1/config/{key}
    // -----------------------------------------------------------------------

    private static final class ConfigReadHandler implements HttpHandler {

        private final EdgeClientCore core;
        private final StrongReadKeyClass strongReadKeyClass;
        private final EdgeNodeMetrics metrics;

        ConfigReadHandler(EdgeClientCore core, StrongReadKeyClass strongReadKeyClass,
                          EdgeNodeMetrics metrics) {
            this.core = core;
            this.strongReadKeyClass = strongReadKeyClass;
            this.metrics = metrics;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith(CONFIG_PREFIX) || path.length() <= CONFIG_PREFIX.length()) {
                sendText(exchange, 400, "Missing config key in path");
                return;
            }
            String key = path.substring(CONFIG_PREFIX.length());
            metrics.onRead();
            // S6/WS-A: measure edge read-serving latency (configd_edge_read_seconds) at the HTTP
            // boundary in a finally so EVERY exit path is sampled — NOT the lock-free
            // EdgeClientCore.read() that the gate-5 0-B/op / 1.60 µs JMH benchmark guards. This
            // handler already allocates (HttpExchange, response bytes), so the two nanoTime calls +
            // bucket increment cannot regress gate-5 (re-verified by re-running gate-5).
            long readStart = System.nanoTime();
            try {
                // CT-03: the staleness header is the client's signal on EVERY read while
                // STALE+ (the data is still served unless cursor-behind/strong-read refuses).
                boolean stale = core.stalenessState().ordinal()
                        >= StalenessTracker.State.STALE.ordinal();
                if (stale) {
                    exchange.getResponseHeaders().set(HDR_STALE, "true");
                }

                // CT-37 store-and-never-serve: fail closed BEFORE the store is consulted.
                if (strongReadKeyClass.isStrongReadKey(key)) {
                    metrics.onReadRefused(EdgeNodeMetrics.REASON_STRONG_READ);
                    exchange.getResponseHeaders().set(HDR_FAIL_CLOSED, "strong-read");
                    sendText(exchange, 503,
                            "Fail-closed: strong-read key '" + key + "' is never served from "
                                    + "bounded-stale edge state (RR-020 / ADR-0038); use the "
                                    + "control plane's linearizable read path");
                    return;
                }

                // ADR-0040 §2 (the negative-caching descope's premise): WITHIN the subscribed
                // slice a store miss IS authoritative non-existence; OUTSIDE it the read is
                // refused with a DISTINCT reason before the store is consulted — never an
                // ambiguous 404 a client could mistake for non-existence.
                if (!core.servesKey(key)) {
                    metrics.onReadRefused(EdgeNodeMetrics.REASON_NOT_SUBSCRIBED);
                    exchange.getResponseHeaders().set(HDR_REFUSED, "not-subscribed");
                    sendText(exchange, 404,
                            "Refused: key '" + key + "' is outside this edge's subscribed "
                                    + "prefixes (ADR-0038 storage filter); this edge holds no "
                                    + "authoritative answer for it");
                    return;
                }

                long cursorVersion = parseCursor(exchange);
                if (cursorVersion < 0 && cursorVersion != NO_CURSOR) {
                    sendText(exchange, 400, "Invalid " + HDR_CURSOR + " header");
                    return;
                }

                // Snapshot the local version BEFORE the read so the !found classification
                // below is sound: localVersion >= cursor implies the read's snapshot also
                // satisfied the cursor (the store version is monotonic), so a miss is a true
                // not-found. localVersion < cursor classifies the miss as a refusal; if the
                // store advanced mid-flight a retry simply succeeds — refusal is the safe side.
                long localVersion = core.currentVersion();
                ReadResult result = (cursorVersion == NO_CURSOR)
                        ? core.get(key)
                        : core.get(key, new VersionCursor(cursorVersion, 0L));

                if (result.found()) {
                    // Every read returns its cursor (charter C2): the version to carry forward.
                    exchange.getResponseHeaders().set(HDR_CURSOR, String.valueOf(core.currentVersion()));
                    exchange.getResponseHeaders().set(HDR_VERSION, String.valueOf(result.version()));
                    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                    byte[] value = result.value();
                    exchange.sendResponseHeaders(200, value.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(value);
                    }
                    return;
                }

                exchange.getResponseHeaders().set(HDR_CURSOR, String.valueOf(localVersion));
                if (cursorVersion != NO_CURSOR && cursorVersion > localVersion) {
                    // The contract §3 consistent-refusal semantics (ADR-0035/ADR-0039, CT-12):
                    // NEVER serve stale on a cursor-behind read — uniform across steady state,
                    // catch-up, and failover. The monitor-wired store already recorded INV-M1.
                    metrics.onReadRefused(EdgeNodeMetrics.REASON_CURSOR_BEHIND);
                    exchange.getResponseHeaders().set(HDR_REFUSED, "cursor-behind");
                    sendText(exchange, 404,
                            "Refused: edge at version " + localVersion + " is behind cursor "
                                    + cursorVersion + " (retry or fail over; never served stale)");
                    return;
                }
                sendText(exchange, 404, "Not Found");
            } finally {
                metrics.recordReadLatency(System.nanoTime() - readStart);
            }
        }

        /** Sentinel for "no cursor header supplied". */
        private static final long NO_CURSOR = Long.MIN_VALUE;

        /** Parses {@code X-Configd-Cursor}; {@link #NO_CURSOR} if absent, -1 if malformed. */
        private static long parseCursor(HttpExchange exchange) {
            String raw = exchange.getRequestHeaders().getFirst(HDR_CURSOR);
            if (raw == null || raw.isBlank()) {
                return NO_CURSOR;
            }
            try {
                long v = Long.parseLong(raw.trim());
                return v < 0 ? -1 : v;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Health + metrics
    // -----------------------------------------------------------------------

    private static void handleLive(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        sendText(exchange, 200, "{\"live\":true}");
    }

    private static void handleReady(HttpExchange exchange, EdgeClientCore core) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        StalenessTracker.State state = core.stalenessState();
        boolean ready = state.ordinal() < StalenessTracker.State.DEGRADED.ordinal();
        sendText(exchange, ready ? 200 : 503,
                "{\"ready\":" + ready + ",\"staleness\":\"" + state + "\"}");
    }

    private static void handleMetrics(HttpExchange exchange, PrometheusExporter exporter,
                                      String scrapeToken)
            throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        // F-S7-TLS-2: when a scrape token is configured, require a matching Bearer token (401 — this
        // is authentication, not authorization; mirrors the control-plane F-0055 /metrics gate).
        // Constant-time compare to avoid leaking the token via response timing.
        if (scrapeToken != null) {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            String presented = (authHeader != null && authHeader.startsWith("Bearer "))
                    ? authHeader.substring("Bearer ".length()) : null;
            if (presented == null || !java.security.MessageDigest.isEqual(
                    presented.getBytes(StandardCharsets.UTF_8),
                    scrapeToken.getBytes(StandardCharsets.UTF_8))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                sendText(exchange, 401, "Unauthorized");
                return;
            }
        }
        byte[] body = exporter.export().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",
                "text/plain; version=0.0.4; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static void sendText(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
