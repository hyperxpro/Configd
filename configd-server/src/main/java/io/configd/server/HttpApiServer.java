package io.configd.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.configd.api.AclService;
import io.configd.api.AuditLog;
import io.configd.api.AuthInterceptor;
import io.configd.common.auth.AuthenticatorChain;
import io.configd.api.ConfigReadService;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.api.ReplayGuard;
import io.configd.common.ConfigScope;
import io.configd.common.NodeId;
import io.configd.observability.PrometheusExporter;
import io.configd.store.VersionedConfigStore;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

/**
 * JDK-transport adapter for the Configd admin / control-plane HTTP API. Uses the built-in
 * {@link HttpServer} (or {@link HttpsServer} when TLS is configured) and is a <b>thin transport
 * shell</b>: it maps each {@link HttpExchange} to an {@link AdminApiHandler.AdminRequest}, delegates
 * the decision to the shared {@link AdminApiHandler}, and writes back the returned
 * {@link AdminApiHandler.AdminResponse}. All security decision logic (authn/authz/audit/replay/429/
 * strong-read, the {@code /metrics} bearer gate, routing, method validation) lives in
 * {@link AdminApiHandler} so it is proven once and re-proven identically on the Netty adapter.
 *
 * <p>Endpoints: {@code GET /health/live}, {@code GET /health/ready}, {@code GET /metrics},
 * {@code GET|PUT|DELETE /v1/config/{key}}.
 */
public final class HttpApiServer {

    private final HttpServer server;

    /**
     * Creates and configures the HTTP API server.
     *
     * @param port           the port to listen on
     * @param sslContext     SSL context for HTTPS, or null for plain HTTP
     * @param healthService  health check service
     * @param prometheusExporter Prometheus metrics exporter
     * @param configStore    versioned config store for reads
     * @param writeService   config write service for puts/deletes
     * @param readService    config read service for linearizable reads (may be null)
     * @param authInterceptor auth interceptor, or null if auth disabled
     * @param aclService     ACL service, or null if ACLs disabled
     * @param strongReadPolicy strong-read key-class policy; must not be null
     * @param leaderHintSupplier supplies the currently-known leader NodeId for
     *                       {@code X-Leader-Hint} (may return null when unknown); must not be null
     */
    public HttpApiServer(int port,
                         SSLContext sslContext,
                         HealthService healthService,
                         PrometheusExporter prometheusExporter,
                         VersionedConfigStore configStore,
                         ConfigWriteService writeService,
                         ConfigReadService readService,
                         AuthInterceptor authInterceptor,
                         AclService aclService,
                         StrongReadPolicy strongReadPolicy,
                         BiFunction<ConfigScope, String, NodeId> leaderHintSupplier) throws IOException {
        this(port, sslContext, healthService, prometheusExporter, configStore, writeService,
                readService, authInterceptor, aclService, strongReadPolicy, leaderHintSupplier,
                /* auditLog */ null, /* replayGuard */ null);
    }

    /**
     * Full constructor adding the security controls. Both are optional:
     *
     * @param auditLog     tamper-evident audit log; may be null to disable auditing
     * @param replayGuard  replay protection; may be null (default off, opt-in for pre-production back-compat)
     */
    public HttpApiServer(int port,
                         SSLContext sslContext,
                         HealthService healthService,
                         PrometheusExporter prometheusExporter,
                         VersionedConfigStore configStore,
                         ConfigWriteService writeService,
                         ConfigReadService readService,
                         AuthInterceptor authInterceptor,
                         AclService aclService,
                         StrongReadPolicy strongReadPolicy,
                         BiFunction<ConfigScope, String, NodeId> leaderHintSupplier,
                         AuditLog auditLog,
                         ReplayGuard replayGuard) throws IOException {
        this(port, sslContext, healthService, prometheusExporter, configStore, writeService, readService,
                authInterceptor, aclService, strongReadPolicy, leaderHintSupplier, auditLog, replayGuard, null);
    }

    /**
     * As the full constructor, plus the {@link AdminApiHandler.LeadershipAdmin} seam that backs the
     * ADMIN-gated leadership-transfer endpoint. A {@code null} seam leaves that endpoint unrouted.
     */
    public HttpApiServer(int port,
                         SSLContext sslContext,
                         HealthService healthService,
                         PrometheusExporter prometheusExporter,
                         VersionedConfigStore configStore,
                         ConfigWriteService writeService,
                         ConfigReadService readService,
                         AuthInterceptor authInterceptor,
                         AclService aclService,
                         StrongReadPolicy strongReadPolicy,
                         BiFunction<ConfigScope, String, NodeId> leaderHintSupplier,
                         AuditLog auditLog,
                         ReplayGuard replayGuard,
                         AdminApiHandler.LeadershipAdmin leadershipAdmin) throws IOException {
        this(port, sslContext, healthService, prometheusExporter, configStore, writeService, readService,
                authInterceptor, aclService, strongReadPolicy, leaderHintSupplier, auditLog, replayGuard,
                leadershipAdmin, null);
    }

    /**
     * As the leadership-seam constructor, plus the SPI {@link AuthenticatorChain}. Mirrors
     * {@code NettyHttpApiServer}'s chain constructor so the two adapters enforce the chain identically:
     * when the chain is non-null it supersedes {@code authInterceptor}; when null this is byte-identical to
     * the legacy bearer wiring.
     */
    public HttpApiServer(int port,
                         SSLContext sslContext,
                         HealthService healthService,
                         PrometheusExporter prometheusExporter,
                         VersionedConfigStore configStore,
                         ConfigWriteService writeService,
                         ConfigReadService readService,
                         AuthInterceptor authInterceptor,
                         AclService aclService,
                         StrongReadPolicy strongReadPolicy,
                         BiFunction<ConfigScope, String, NodeId> leaderHintSupplier,
                         AuditLog auditLog,
                         ReplayGuard replayGuard,
                         AdminApiHandler.LeadershipAdmin leadershipAdmin,
                         AuthenticatorChain chain) throws IOException {
        if (sslContext != null) {
            HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
            // mTLS mode: request (optionally) a client certificate so the mtls authenticator can identify
            // the caller by its verified cert. wantClientAuth (not need) keeps bearer/basic clients working
            // in a mixed chain. Gated on the chain actually including mtls, so non-mtls TLS is byte-identical.
            boolean wantClientCert = chain != null && chain.providerTypes().contains("mtls");
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters sslParams = getSSLContext().getDefaultSSLParameters();
                    if (wantClientCert) {
                        sslParams.setWantClientAuth(true);
                    }
                    params.setSSLParameters(sslParams);
                }
            });
            this.server = httpsServer;
        } else {
            this.server = HttpServer.create(new InetSocketAddress(port), 0);
        }

        AdminApiHandler handler = new AdminApiHandler(healthService, prometheusExporter, configStore,
                writeService, readService, authInterceptor, aclService, strongReadPolicy,
                leaderHintSupplier, auditLog, replayGuard, leadershipAdmin, chain);

        // A single root context: the shared handler does its own exact-match routing,
        // so a suffix variant of a fixed endpoint cannot be served by a prefix-matched context.
        server.createContext("/", new RootHandler(handler));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    /** Starts the HTTP server. */
    public void start() {
        server.start();
    }

    /**
     * The actual bound port. After {@link #start()} with a {@code 0} (ephemeral) port this
     * returns the OS-assigned port, so tests can target the server without a fixed port.
     */
    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * Stops the HTTP server.
     *
     * @param delaySeconds seconds to wait for in-flight requests
     */
    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
    }

    // -----------------------------------------------------------------------
    // Transport shell: HttpExchange <-> AdminApiHandler
    // -----------------------------------------------------------------------

    private static final class RootHandler implements HttpHandler {
        private final AdminApiHandler handler;

        RootHandler(AdminApiHandler handler) {
            this.handler = handler;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            AdminApiHandler.AdminResponse response = handler.handle(new ExchangeRequest(exchange));
            for (Map.Entry<String, String> header : response.headers().entrySet()) {
                exchange.getResponseHeaders().set(header.getKey(), header.getValue());
            }
            byte[] body = response.body();
            exchange.sendResponseHeaders(response.status(), body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    /**
     * {@link AdminApiHandler.AdminRequest} backed by a JDK {@link HttpExchange}. {@link #uri()}
     * returns the exchange's already-parsed {@code java.net.URI}; the Netty adapter builds the same
     * type from the raw request target, so path decoding (C6) is identical on both transports.
     */
    private record ExchangeRequest(HttpExchange exchange) implements AdminApiHandler.AdminRequest {
        @Override
        public String method() {
            return exchange.getRequestMethod();
        }

        @Override
        public URI uri() {
            return exchange.getRequestURI();
        }

        @Override
        public String header(String name) {
            return exchange.getRequestHeaders().getFirst(name);
        }

        @Override
        public byte[] body() throws IOException {
            return exchange.getRequestBody().readAllBytes();
        }

        @Override
        public List<X509Certificate> peerCertificates() {
            if (exchange instanceof HttpsExchange https) {
                try {
                    Certificate[] certs = https.getSSLSession().getPeerCertificates();
                    List<X509Certificate> chain = new ArrayList<>(certs.length);
                    for (Certificate c : certs) {
                        if (c instanceof X509Certificate x) {
                            chain.add(x);
                        }
                    }
                    return chain;
                } catch (SSLPeerUnverifiedException e) {
                    return List.of(); // the peer presented no client certificate
                }
            }
            return List.of();
        }
    }
}
