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
 * The edge node's read-serving surface: JDK {@link HttpServer} with a virtual-thread executor.
 *
 * <p>The read-serving <em>decision logic</em> lives in the transport-agnostic
 * {@link EdgeReadHandler}; this class is a thin JDK-{@code HttpServer} adapter over it, and
 * {@link NettyEdgeHttpServer} is the Netty adapter over the <em>same</em> logic - so the two
 * transports serve byte-identical responses on the canonical request paths by construction
 * (routing is exact-match). This JDK adapter is retained as the equivalence reference
 * (it is what {@code EdgeHttpServerTest} pins).
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /v1/config/{key}} - cursor/staleness-governed reads;</li>
 *   <li>{@code GET /health/live} - always 200 (process liveness);</li>
 *   <li>{@code GET /health/ready} - 503 when the staleness state is DEGRADED or worse;</li>
 *   <li>{@code GET /metrics} - Prometheus exposition (optional Bearer scrape-token gate when
 *       configured).</li>
 * </ul>
 *
 * <h2>Read semantics</h2>
 * <ul>
 *   <li>The client's cursor arrives via {@code X-Configd-Cursor}; every response carries
 *       {@code X-Configd-Cursor} = this edge's current store version, and a hit carries
 *       {@code X-Configd-Version} = the value's write version;</li>
 *   <li><b>cursor-behind: 404 + {@code X-Configd-Refused: cursor-behind}</b>: the edge NEVER serves
 *       stale on a cursor-behind read; the refused read still routes through the monitor-wired store
 *       so {@code invariant.violation.monotonic_read} fires;</li>
 *   <li>{@code X-Configd-Stale: true} on ALL read responses while STALE or worse;</li>
 *   <li><b>strong-read keys</b>: 503 + {@code X-Fail-Closed: strong-read} before the store
 *       is consulted;</li>
 *   <li><b>not-subscribed keys</b>: 404 + {@code X-Configd-Refused: not-subscribed}
 *       before the store is consulted.</li>
 * </ul>
 *
 * <p>This JDK HTTP shell allocates per request (exchange, headers, streams) - the very cost
 * the Netty adapter removes, at much less server-side allocation.
 */
public final class EdgeHttpServer {

    public static final String HDR_CURSOR = "X-Configd-Cursor";
    public static final String HDR_VERSION = "X-Configd-Version";
    public static final String HDR_REFUSED = "X-Configd-Refused";
    public static final String HDR_STALE = "X-Configd-Stale";
    public static final String HDR_FAIL_CLOSED = "X-Fail-Closed";

    /**
     * Optional Bearer-token scrape secret for {@code GET /metrics} (system property
     * {@code configd.edge.metricsScrapeToken}). When set, an unauthenticated scrape is refused 401.
     * Null = open. Read once at construction so both adapters observe the same policy.
     */
    private final String metricsScrapeToken = System.getProperty("configd.edge.metricsScrapeToken");

    private final HttpServer server;
    private final EdgeReadHandler handler;

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
        // as the JDK HttpServer's unregistered-context default does).
        server.createContext("/", this::dispatch);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() {
        server.start();
    }

    /** The actual bound port (resolves an ephemeral port 0 after {@link #start()}). */
    public int port() {
        return server.getAddress().getPort();
    }

    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
    }

    private void dispatch(HttpExchange exchange) {
        try {
            handler.handle(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst(HDR_CURSOR),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new JdkSink(exchange));
        } finally {
            exchange.close();
        }
    }

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
