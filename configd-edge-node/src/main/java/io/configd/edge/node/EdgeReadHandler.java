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
 * Transport-agnostic edge read-serving logic. Single source of truth: routing, cursor/staleness
 * clauses, health gating, metrics. Driven by both {@link EdgeHttpServer} and
 * {@link NettyEdgeHttpServer} so responses are byte-identical by construction.
 */
public final class EdgeReadHandler {

    private static final String CONFIG_PREFIX = "/v1/config/";
    private static final String CT_JSON = "application/json";
    private static final String CT_OCTET = "application/octet-stream";
    private static final String CT_PROM = "text/plain; version=0.0.4; charset=utf-8";

    /** Sentinel for "no cursor header supplied". */
    static final long NO_CURSOR = Long.MIN_VALUE;

    private final EdgeClientCore core;
    private final StrongReadKeyClass strongReadKeyClass;
    private final EdgeNodeMetrics metrics;
    private final PrometheusExporter exporter;
    private final String metricsScrapeToken;

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

    public interface Sink {
        void header(CharSequence name, CharSequence value);

        void commit(int status, CharSequence contentType, byte[] body);
    }

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
            boolean stale = core.stalenessState().ordinal()
                    >= StalenessTracker.State.STALE.ordinal();
            if (stale) {
                out.header(EdgeHttpServer.HDR_STALE, "true");
            }

            if (strongReadKeyClass.isStrongReadKey(key)) {
                metrics.onReadRefused(EdgeNodeMetrics.REASON_STRONG_READ);
                out.header(EdgeHttpServer.HDR_FAIL_CLOSED, "strong-read");
                out.commit(503, CT_JSON,
                        bytes("Fail-closed: strong-read key '" + key + "' is never served from "
                                + "bounded-stale edge state (RR-020 / ADR-0038); use the "
                                + "control plane's linearizable read path"));
                return;
            }

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

            // Capture version before read: needed for sound !found classification.
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

    private static boolean notGet(boolean get, Sink out) {
        if (get) return false;
        out.commit(405, CT_JSON, bytes("Method Not Allowed"));
        return true;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String stripQuery(String uri) {
        int q = uri.indexOf('?');
        return q < 0 ? uri : uri.substring(0, q);
    }

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
