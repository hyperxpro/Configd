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


public final class HttpApiServer {

    private final HttpServer server;

    
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
                 null,  null);
    }

    
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
        this(null, port, sslContext, healthService, prometheusExporter, configStore, writeService,
                readService, authInterceptor, aclService, strongReadPolicy, leaderHintSupplier,
                auditLog, replayGuard, leadershipAdmin, chain);
    }

    
    public HttpApiServer(String bindAddress,
                         int port,
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
        this(bindAddress, port, sslContext, healthService, prometheusExporter, configStore, writeService,
                readService, authInterceptor, aclService, strongReadPolicy, leaderHintSupplier,
                auditLog, replayGuard, leadershipAdmin, chain, null, null);
    }

    
    public HttpApiServer(String bindAddress,
                         int port,
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
                         AuthenticatorChain chain,
                         AdminApiHandler.RaftClusterAdmin raftClusterAdmin,
                         AdminApiHandler.KeyringRotationAdmin keyringRotator) throws IOException {
        InetSocketAddress bindAddr = bindAddress == null
                ? new InetSocketAddress(port)                 // wildcard (all interfaces)
                : new InetSocketAddress(bindAddress, port);
        if (sslContext != null) {
            HttpsServer httpsServer = HttpsServer.create(bindAddr, 0);
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
            this.server = HttpServer.create(bindAddr, 0);
        }

        AdminApiHandler handler = new AdminApiHandler(healthService, prometheusExporter, configStore,
                writeService, readService, authInterceptor, aclService, strongReadPolicy,
                leaderHintSupplier, auditLog, replayGuard, leadershipAdmin, chain, raftClusterAdmin,
                keyringRotator);

        // A single root context: the shared handler does its own exact-match routing,
        // so a suffix variant of a fixed endpoint cannot be served by a prefix-matched context.
        server.createContext("/", new RootHandler(handler));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    
    public void start() {
        server.start();
    }

    
    public int port() {
        return server.getAddress().getPort();
    }

    
    String boundHost() {
        return server.getAddress().getAddress().getHostAddress();
    }

    
    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
    }

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
                    return List.of();
                }
            }
            return List.of();
        }
    }
}
