package io.configd.edge.node;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.configd.edge.EdgeClientCore;
import io.configd.edge.StrongReadKeyClass;
import io.configd.observability.PrometheusExporter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.Executors;

/**
 * The edge node's read-serving surface (C2 design §3.4/§4): JDK {@link HttpServer}
 * (the HttpApiServer pattern — virtual-thread executor).
 *
 * <p><b>Netty-migration (ADR-0043).</b> The read-serving <em>decision logic</em> now lives in the
 * transport-agnostic {@link EdgeReadHandler}; this class is a thin JDK-{@code HttpServer} adapter
 * over it, and {@link NettyEdgeHttpServer} is the Netty adapter over the <em>same</em> logic — so
 * the two transports serve byte-identical responses on the canonical request paths by construction
 * (DR-N2; routing is exact-match, DR-N4). M1 swaps the
 * production edge to {@link NettyEdgeHttpServer}; this JDK adapter is retained as the equivalence
 * reference (it is what {@code EdgeHttpServerTest} pins) until the swap is verified.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /v1/config/{key}} — cursor/staleness-governed reads;</li>
 *   <li>{@code GET /health/live} — always 200 (process liveness);</li>
 *   <li>{@code GET /health/ready} — 503 when the staleness state is DEGRADED or worse (CT-05);</li>
 *   <li>{@code GET /metrics} — Prometheus exposition (F-S7-TLS-2 Bearer gate when configured).</li>
 * </ul>
 *
 * <h2>Read semantics (contract §2/§3; ADR-0035 + ADR-0039 consistent refusal)</h2>
 * <ul>
 *   <li>The client's cursor arrives via {@code X-Configd-Cursor}; every response carries
 *       {@code X-Configd-Cursor} = this edge's current store version, and a hit carries
 *       {@code X-Configd-Version} = the value's write version;</li>
 *   <li><b>cursor-behind → 404 + {@code X-Configd-Refused: cursor-behind}</b>: the edge NEVER serves
 *       stale on a cursor-behind read; the refused read still routes through the monitor-wired store
 *       so {@code invariant.violation.monotonic_read} fires (INV-M1 seam);</li>
 *   <li>{@code X-Configd-Stale: true} on ALL read responses while STALE or worse (CT-03);</li>
 *   <li><b>strong-read keys (CT-37)</b>: 503 + {@code X-Fail-Closed: strong-read} before the store
 *       is consulted (RR-020);</li>
 *   <li><b>not-subscribed keys (ADR-0040 §2)</b>: 404 + {@code X-Configd-Refused: not-subscribed}
 *       before the store is consulted.</li>
 * </ul>
 *
 * <h2>Hot-path honesty (CT-34)</h2>
 * The §3 hot-path law binds the in-process read path; THIS JDK HTTP shell allocates per request
 * (exchange, headers, streams) — the very cost the Netty adapter removes (8.7×, ADR-0043 evidence).
 */
public final class EdgeHttpServer {

    /** Request/response header carrying the client's monotonic-read cursor. */
    public static final String HDR_CURSOR = "X-Configd-Cursor";
    /** Response header: the served value's write version. */
    public static final String HDR_VERSION = "X-Configd-Version";
    /** Response header on a cursor-behind / not-subscribed refusal. */
    public static final String HDR_REFUSED = "X-Configd-Refused";
    /** Response header set on all reads while STALE+. */
    public static final String HDR_STALE = "X-Configd-Stale";
    /** Response header on the strong-read fail-close (RR-020-consistent). */
    public static final String HDR_FAIL_CLOSED = "X-Fail-Closed";

    /**
     * F-S7-TLS-2 (edge {@code /metrics} exposure, Low): optional Bearer-token scrape secret for
     * {@code GET /metrics} (system property {@code configd.edge.metricsScrapeToken}). When set, an
     * unauthenticated scrape is refused 401. Null = open (legacy). Read once at construction so both
     * adapters observe the same policy.
     */
    private final String metricsScrapeToken = System.getProperty("configd.edge.metricsScrapeToken");

    private final HttpServer server;
    private final EdgeReadHandler handler;

    /**
     * @param port               the bind port (0 = ephemeral; see {@link #port()})
     * @param core               the edge client core (lock-free read path + staleness)
     * @param strongReadKeyClass the CT-37 strong-read predicate (shared with the storage filter)
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
        this.handler = new EdgeReadHandler(core, strongReadKeyClass, exporter, metrics,
                metricsScrapeToken);
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        // Single root context: the shared handler routes by path (and 404s unmatched paths, exactly
        // as the JDK HttpServer's unregistered-context default did).
        server.createContext("/", this::dispatch);
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

    private void dispatch(HttpExchange exchange) {
        try {
            handler.handle(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(), // URI.getPath() already strips the query
                    exchange.getRequestHeaders().getFirst(HDR_CURSOR),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new JdkSink(exchange));
        } finally {
            exchange.close();
        }
    }

    /** Renders an {@link EdgeReadHandler.Sink} onto a JDK {@link HttpExchange}. */
    private static final class JdkSink implements EdgeReadHandler.Sink {
        private final HttpExchange exchange;

        JdkSink(HttpExchange exchange) {
            this.exchange = exchange;
        }

        @Override
        public void header(CharSequence name, CharSequence value) {
            exchange.getResponseHeaders().set(name.toString(), value.toString());
        }

        @Override
        public void commit(int status, CharSequence contentType, byte[] body) {
            try {
                exchange.getResponseHeaders().set("Content-Type", contentType.toString());
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } catch (IOException e) {
                // Client gone / write failed mid-response — nothing actionable on a read server.
            }
        }
    }
}
