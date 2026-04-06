package io.configd.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.configd.api.AclService;
import io.configd.api.AuthInterceptor;
import io.configd.api.ConfigReadService;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.common.ConfigScope;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.store.ReadResult;
import io.configd.store.VersionedConfigStore;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * HTTP API server for Configd. Uses JDK's built-in {@link HttpServer}
 * (or {@link HttpsServer} when TLS is configured).
 * <p>
 * Endpoints:
 * <ul>
 *   <li>{@code GET /health/live} - liveness probe</li>
 *   <li>{@code GET /health/ready} - readiness probe</li>
 *   <li>{@code GET /metrics} - Prometheus-format metrics</li>
 *   <li>{@code PUT /v1/config/{key}} - write config</li>
 *   <li>{@code GET /v1/config/{key}} - read config</li>
 *   <li>{@code DELETE /v1/config/{key}} - delete config</li>
 * </ul>
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
     */
    public HttpApiServer(int port,
                         SSLContext sslContext,
                         HealthService healthService,
                         PrometheusExporter prometheusExporter,
                         VersionedConfigStore configStore,
                         ConfigWriteService writeService,
                         ConfigReadService readService,
                         AuthInterceptor authInterceptor,
                         AclService aclService) throws IOException {
        if (sslContext != null) {
            HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
            this.server = httpsServer;
        } else {
            this.server = HttpServer.create(new InetSocketAddress(port), 0);
        }

        server.createContext("/health/live", new LivenessHandler(healthService));
        server.createContext("/health/ready", new ReadinessHandler(healthService));
        // F-0055 fix: when auth is configured, /metrics requires a valid
        // bearer token. /health endpoints remain public so liveness/readiness
        // probes keep working. Metrics exposition can leak operational state
        // (leader identity, follower lag, key cardinality) and must be
        // protected on the same footing as the /v1/config/ endpoints.
        server.createContext("/metrics", new MetricsHandler(prometheusExporter, authInterceptor));
        server.createContext("/v1/config/", new ConfigHandler(
                configStore, writeService, readService, authInterceptor, aclService));

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Starts the HTTP server.
     */
    public void start() {
        server.start();
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
    // Handlers
    // -----------------------------------------------------------------------

    private static final class LivenessHandler implements HttpHandler {
        private final HealthService healthService;

        LivenessHandler(HealthService healthService) {
            this.healthService = healthService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            HealthService.HealthStatus status = healthService.liveness();
            int code = status.healthy() ? 200 : 503;
            String body = formatHealthStatus(status);
            sendResponse(exchange, code, body);
        }
    }

    private static final class ReadinessHandler implements HttpHandler {
        private final HealthService healthService;

        ReadinessHandler(HealthService healthService) {
            this.healthService = healthService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            HealthService.HealthStatus status = healthService.readiness();
            int code = status.healthy() ? 200 : 503;
            String body = formatHealthStatus(status);
            sendResponse(exchange, code, body);
        }
    }

    private static final class MetricsHandler implements HttpHandler {
        private final PrometheusExporter exporter;
        private final AuthInterceptor authInterceptor; // nullable

        MetricsHandler(PrometheusExporter exporter, AuthInterceptor authInterceptor) {
            this.exporter = exporter;
            this.authInterceptor = authInterceptor;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            // F-0055 fix: enforce bearer-token auth on /metrics when auth
            // is configured. 401 (not 403) because this is authentication,
            // not authorization — there is no ACL for metrics scraping yet.
            if (authInterceptor != null) {
                String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
                String token = null;
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    token = authHeader.substring("Bearer ".length());
                }
                AuthInterceptor.AuthResult authResult = authInterceptor.authenticate(token);
                if (authResult instanceof AuthInterceptor.AuthResult.Denied denied) {
                    exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                    sendResponse(exchange, 401, "Unauthorized: " + denied.reason());
                    return;
                }
            }
            String metricsText = exporter.export();
            sendResponseWithContentType(exchange, 200, metricsText,
                    "text/plain; version=0.0.4; charset=utf-8");
        }
    }

    private static final class ConfigHandler implements HttpHandler {
        private final VersionedConfigStore configStore;
        private final ConfigWriteService writeService;
        private final ConfigReadService readService; // nullable
        private final AuthInterceptor authInterceptor;
        private final AclService aclService;

        ConfigHandler(VersionedConfigStore configStore,
                      ConfigWriteService writeService,
                      ConfigReadService readService,
                      AuthInterceptor authInterceptor,
                      AclService aclService) {
            this.configStore = configStore;
            this.writeService = writeService;
            this.readService = readService;
            this.authInterceptor = authInterceptor;
            this.aclService = aclService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            // Extract key from /v1/config/{key}
            String prefix = "/v1/config/";
            if (!path.startsWith(prefix) || path.length() <= prefix.length()) {
                sendResponse(exchange, 400, "Missing config key in path");
                return;
            }
            String key = path.substring(prefix.length());

            switch (exchange.getRequestMethod()) {
                case "GET" -> handleGet(exchange, key);
                case "PUT" -> handlePut(exchange, key);
                case "DELETE" -> handleDelete(exchange, key);
                default -> sendResponse(exchange, 405, "Method Not Allowed");
            }
        }

        private void handleGet(HttpExchange exchange, String key) throws IOException {
            // Auth check for reads
            String authError = checkAuth(exchange, key, AclService.Permission.READ);
            if (authError != null) {
                sendResponse(exchange, 403, authError);
                return;
            }

            // Support linearizable reads via ?consistency=linearizable query parameter
            String query = exchange.getRequestURI().getQuery();
            boolean linearizable = query != null && query.contains("consistency=linearizable");

            ReadResult result;
            if (linearizable && readService != null) {
                result = readService.linearizableRead(key);
                if (result == null) {
                    // Leadership confirmation failed — cannot serve linearizable read
                    sendResponse(exchange, 503, "Not Leader - cannot serve linearizable read");
                    return;
                }
            } else {
                result = configStore.get(key);
            }

            if (!result.found()) {
                sendResponse(exchange, 404, "Not Found");
                return;
            }
            byte[] value = result.value();
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().set("X-Config-Version", String.valueOf(result.version()));
            exchange.getResponseHeaders().set("X-Consistency", linearizable ? "linearizable" : "stale");
            exchange.sendResponseHeaders(200, value.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(value);
            }
        }

        private void handlePut(HttpExchange exchange, String key) throws IOException {
            // Auth check for writes
            String authError = checkAuth(exchange, key, AclService.Permission.WRITE);
            if (authError != null) {
                sendResponse(exchange, 403, authError);
                return;
            }

            byte[] body = exchange.getRequestBody().readAllBytes();
            if (body.length == 0) {
                sendResponse(exchange, 400, "Request body must not be empty");
                return;
            }

            ConfigWriteService.WriteResult result = writeService.put(key, body, ConfigScope.GLOBAL);
            switch (result) {
                case ConfigWriteService.WriteResult.Accepted a ->
                        sendResponse(exchange, 200, "Accepted: proposalId=" + a.proposalId());
                case ConfigWriteService.WriteResult.NotLeader nl -> {
                    if (nl.leaderId() != null) {
                        exchange.getResponseHeaders().set("X-Leader-Hint", String.valueOf(nl.leaderId().id()));
                    }
                    sendResponse(exchange, 503, "Not Leader"
                            + (nl.leaderId() != null ? " (leader=" + nl.leaderId() + ")" : ""));
                }
                case ConfigWriteService.WriteResult.ValidationFailed vf ->
                        sendResponse(exchange, 400, "Validation failed: " + vf.reason());
                case ConfigWriteService.WriteResult.Overloaded _ ->
                        sendResponse(exchange, 429, "Overloaded");
            }
        }

        private void handleDelete(HttpExchange exchange, String key) throws IOException {
            // Auth check for deletes
            String authError = checkAuth(exchange, key, AclService.Permission.WRITE);
            if (authError != null) {
                sendResponse(exchange, 403, authError);
                return;
            }

            ConfigWriteService.WriteResult result = writeService.delete(key, ConfigScope.GLOBAL);
            switch (result) {
                case ConfigWriteService.WriteResult.Accepted a ->
                        sendResponse(exchange, 200, "Deleted: proposalId=" + a.proposalId());
                case ConfigWriteService.WriteResult.NotLeader nl -> {
                    if (nl.leaderId() != null) {
                        exchange.getResponseHeaders().set("X-Leader-Hint", String.valueOf(nl.leaderId().id()));
                    }
                    sendResponse(exchange, 503, "Not Leader"
                            + (nl.leaderId() != null ? " (leader=" + nl.leaderId() + ")" : ""));
                }
                case ConfigWriteService.WriteResult.ValidationFailed vf ->
                        sendResponse(exchange, 400, "Validation failed: " + vf.reason());
                case ConfigWriteService.WriteResult.Overloaded _ ->
                        sendResponse(exchange, 429, "Overloaded");
            }
        }

        /**
         * Checks authentication and ACL for the request.
         * Returns null if access is allowed, or an error message if denied.
         */
        private String checkAuth(HttpExchange exchange, String key, AclService.Permission permission) {
            if (authInterceptor == null) {
                return null; // auth not configured
            }

            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            String token = null;
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring("Bearer ".length());
            }

            AuthInterceptor.AuthResult authResult = authInterceptor.authenticate(token);
            if (authResult instanceof AuthInterceptor.AuthResult.Denied denied) {
                return "Authentication denied: " + denied.reason();
            }

            if (aclService != null && authResult instanceof AuthInterceptor.AuthResult.Authenticated authed) {
                if (!aclService.isAllowed(authed.principal(), key, permission)) {
                    return "Access denied: insufficient permissions for key '" + key + "'";
                }
            }

            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String formatHealthStatus(HealthService.HealthStatus status) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"healthy\":").append(status.healthy());
        sb.append(",\"checks\":[");
        boolean first = true;
        for (HealthService.CheckResult check : status.checks()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":\"").append(escapeJson(check.name()))
                    .append("\",\"healthy\":").append(check.healthy())
                    .append(",\"detail\":\"").append(escapeJson(check.detail())).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                     .replace("\"", "\\\"")
                     .replace("\n", "\\n")
                     .replace("\r", "\\r")
                     .replace("\t", "\\t");
    }

    private static void sendResponse(HttpExchange exchange, int code, String body) throws IOException {
        sendResponseWithContentType(exchange, code, body, "application/json");
    }

    private static void sendResponseWithContentType(HttpExchange exchange, int code,
                                                     String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
