package io.configd.edge.node;

import io.configd.edge.EdgeClientCore;
import io.configd.edge.StalenessTracker;
import io.configd.edge.StrongReadKeyClass;
import io.configd.edge.VersionCursor;
import io.configd.observability.PrometheusExporter;
import io.configd.store.ReadResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * Transport-agnostic edge read-serving decision logic (ADR-0043 / Netty-migration DR-N2).
 *
 * <p>This is the single source of truth for the edge read surface's behaviour — routing, the
 * cursor/staleness/strong-read/not-subscribed clauses, health gating, the {@code /metrics}
 * Bearer gate, method validation, and the metric/INV-M1 side-effects. It is driven by both the
 * JDK {@link EdgeHttpServer} adapter and the Netty {@link NettyEdgeHttpServer} adapter so the two
 * transports serve <b>byte-identical</b> responses on the canonical request paths by construction
 * (routing is exact-match — DR-N4), not by parallel re-implementation (the head-to-head prototype
 * diverged: no health/metrics endpoints, no 405, and it set the stale header only on hits — DR-N2).
 *
 * <p><b>Allocation discipline.</b> The logic writes to a {@link Sink} rather than building a
 * response object + header map per request, so it adds <b>no</b> per-request allocation over the
 * head-to-head Netty prototype (the gc-proof's 1,716 B/req must hold — charter §3). Header names
 * and the constant strings are interned; the only per-request strings are the cursor/version
 * decimal renderings the prototype already paid.
 *
 * <p>The control flow mirrors {@link EdgeHttpServer.ConfigReadHandler} exactly, including the
 * order of clauses (method → key → onRead → stale-header → strong-read → not-subscribed →
 * cursor-parse → store read → found/refused/miss) and which paths record the read-latency sample.
 */
public final class EdgeReadHandler {

    private static final String CONFIG_PREFIX = "/v1/config/";
    private static final String CT_JSON = "application/json";
    private static final String CT_OCTET = "application/octet-stream";
    private static final String CT_PROM = "text/plain; version=0.0.4; charset=utf-8";

    /** Sentinel for "no cursor header supplied" (mirrors {@link EdgeHttpServer}). */
    static final long NO_CURSOR = Long.MIN_VALUE;

    private final EdgeClientCore core;
    private final StrongReadKeyClass strongReadKeyClass;
    private final EdgeNodeMetrics metrics;
    private final PrometheusExporter exporter;
    private final String metricsScrapeToken;

    /**
     * @param core               the edge client core (lock-free read path + staleness)
     * @param strongReadKeyClass the CT-37 strong-read predicate (shared with the storage filter)
     * @param exporter           the Prometheus exporter over the process registry
     * @param metrics            the edge metric series (reads/refusals)
     * @param metricsScrapeToken F-S7-TLS-2: the optional {@code /metrics} Bearer secret; null = open
     */
    public EdgeReadHandler(EdgeClientCore core, StrongReadKeyClass strongReadKeyClass,
                           PrometheusExporter exporter, EdgeNodeMetrics metrics,
                           String metricsScrapeToken) {
        this.core = Objects.requireNonNull(core, "core must not be null");
        this.strongReadKeyClass =
                Objects.requireNonNull(strongReadKeyClass, "strongReadKeyClass must not be null");
        this.exporter = Objects.requireNonNull(exporter, "exporter must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.metricsScrapeToken = metricsScrapeToken;
    }

    /**
     * The transport's response builder. The logic calls {@link #header} zero or more times, then
     * exactly one terminal {@link #commit}. Implementations set Content-Length from the body length
     * and honour the transport's own keep-alive/framing.
     */
    public interface Sink {
        /** Set a response header (called before {@link #commit}). */
        void header(CharSequence name, CharSequence value);

        /** Terminal: send {@code status} with {@code contentType} and {@code body} (may be empty). */
        void commit(int status, CharSequence contentType, byte[] body);
    }

    /**
     * Routes one request. {@code path} must already have any query string stripped (see
     * {@link #stripQuery}). {@code cursorHeader}/{@code authHeader} are the raw request header
     * values (null if absent).
     */
    public void handle(String method, String path, String cursorHeader, String authHeader, Sink out) {
        boolean get = "GET".equals(method);
        if (path.startsWith(CONFIG_PREFIX)) {
            handleConfig(get, path, cursorHeader, out);
        } else if (path.equals("/health/live")) {
            if (notGet(get, out)) return;
            out.commit(200, CT_JSON, bytes("{\"live\":true}"));
        } else if (path.equals("/health/ready")) {
            if (notGet(get, out)) return;
            StalenessTracker.State state = core.stalenessState();
            boolean ready = state.ordinal() < StalenessTracker.State.DEGRADED.ordinal();
            out.commit(ready ? 200 : 503, CT_JSON,
                    bytes("{\"ready\":" + ready + ",\"staleness\":\"" + state + "\"}"));
        } else if (path.equals("/metrics")) {
            if (notGet(get, out)) return;
            handleMetrics(authHeader, out);
        } else {
            // No matching endpoint — the JDK HttpServer returns 404 for an unregistered context.
            out.commit(404, CT_JSON, bytes("Not Found"));
        }
    }

    // ---- GET /v1/config/{key} (mirrors EdgeHttpServer.ConfigReadHandler.handle exactly) ----

    private void handleConfig(boolean get, String path, String cursorHeader, Sink out) {
        if (notGet(get, out)) return;
        if (path.length() <= CONFIG_PREFIX.length()) {
            out.commit(400, CT_JSON, bytes("Missing config key in path"));
            return;
        }
        String key = path.substring(CONFIG_PREFIX.length());
        metrics.onRead();
        long readStart = System.nanoTime();
        try {
            // CT-03: stale header on EVERY read while STALE+ (set before any refusal branch, so a
            // refused/served response alike carries it — EdgeHttpServer sets it at the top).
            boolean stale = core.stalenessState().ordinal()
                    >= StalenessTracker.State.STALE.ordinal();
            if (stale) {
                out.header(EdgeHttpServer.HDR_STALE, "true");
            }

            // CT-37 store-and-never-serve: fail closed BEFORE the store is consulted.
            if (strongReadKeyClass.isStrongReadKey(key)) {
                metrics.onReadRefused(EdgeNodeMetrics.REASON_STRONG_READ);
                out.header(EdgeHttpServer.HDR_FAIL_CLOSED, "strong-read");
                out.commit(503, CT_JSON,
                        bytes("Fail-closed: strong-read key '" + key + "' is never served from "
                                + "bounded-stale edge state (RR-020 / ADR-0038); use the "
                                + "control plane's linearizable read path"));
                return;
            }

            // ADR-0040 §2: out-of-slice reads refuse with a DISTINCT reason before the store read.
            if (!core.servesKey(key)) {
                metrics.onReadRefused(EdgeNodeMetrics.REASON_NOT_SUBSCRIBED);
                out.header(EdgeHttpServer.HDR_REFUSED, "not-subscribed");
                out.commit(404, CT_JSON,
                        bytes("Refused: key '" + key + "' is outside this edge's subscribed "
                                + "prefixes (ADR-0038 storage filter); this edge holds no "
                                + "authoritative answer for it"));
                return;
            }

            long cursorVersion = parseCursor(cursorHeader);
            if (cursorVersion < 0 && cursorVersion != NO_CURSOR) {
                out.commit(400, CT_JSON, bytes("Invalid " + EdgeHttpServer.HDR_CURSOR + " header"));
                return;
            }

            // Snapshot localVersion BEFORE the read so the !found classification is sound (see
            // EdgeHttpServer). The cursor'd get() routes through the monitor-wired store → INV-M1.
            long localVersion = core.currentVersion();
            ReadResult result = (cursorVersion == NO_CURSOR)
                    ? core.get(key)
                    : core.get(key, new VersionCursor(cursorVersion, 0L));

            if (result.found()) {
                out.header(EdgeHttpServer.HDR_CURSOR, Long.toString(core.currentVersion()));
                out.header(EdgeHttpServer.HDR_VERSION, Long.toString(result.version()));
                out.commit(200, CT_OCTET, result.value());
                return;
            }

            out.header(EdgeHttpServer.HDR_CURSOR, Long.toString(localVersion));
            if (cursorVersion != NO_CURSOR && cursorVersion > localVersion) {
                metrics.onReadRefused(EdgeNodeMetrics.REASON_CURSOR_BEHIND);
                out.header(EdgeHttpServer.HDR_REFUSED, "cursor-behind");
                out.commit(404, CT_JSON,
                        bytes("Refused: edge at version " + localVersion + " is behind cursor "
                                + cursorVersion + " (retry or fail over; never served stale)"));
                return;
            }
            out.commit(404, CT_JSON, bytes("Not Found"));
        } finally {
            metrics.recordReadLatency(System.nanoTime() - readStart);
        }
    }

    // ---- GET /metrics (F-S7-TLS-2 Bearer gate, then the exposition) ----

    private void handleMetrics(String authHeader, Sink out) {
        if (metricsScrapeToken != null) {
            String presented = (authHeader != null && authHeader.startsWith("Bearer "))
                    ? authHeader.substring("Bearer ".length()) : null;
            // Constant-time compare to avoid leaking the token via response timing.
            if (presented == null || !MessageDigest.isEqual(
                    presented.getBytes(StandardCharsets.UTF_8),
                    metricsScrapeToken.getBytes(StandardCharsets.UTF_8))) {
                out.header("WWW-Authenticate", "Bearer");
                out.commit(401, CT_JSON, bytes("Unauthorized"));
                return;
            }
        }
        out.commit(200, CT_PROM, bytes(exporter.export()));
    }

    // ---- helpers (identical semantics to EdgeHttpServer) ----

    private static boolean notGet(boolean get, Sink out) {
        if (get) return false;
        out.commit(405, CT_JSON, bytes("Method Not Allowed"));
        return true;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Strips a query string from a raw URI/path. */
    static String stripQuery(String uri) {
        int q = uri.indexOf('?');
        return q < 0 ? uri : uri.substring(0, q);
    }

    /** Parses {@code X-Configd-Cursor}; {@link #NO_CURSOR} if absent, -1 if malformed. */
    static long parseCursor(String raw) {
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
