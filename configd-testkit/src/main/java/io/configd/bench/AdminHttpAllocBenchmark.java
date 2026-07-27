package io.configd.bench;

import io.configd.api.AclService;
import io.configd.api.AuthInterceptor;
import io.configd.api.HealthService;
import io.configd.common.NodeId;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.server.HttpApiServer;
import io.configd.server.NettyHttpApiServer;
import io.configd.server.StrongReadPolicy;
import io.configd.store.VersionedConfigStore;
import org.openjdk.jmh.annotations.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Allocation baseline for <b>surface 1: control/admin API</b>
 * ({@code configd-server} - {@link HttpApiServer}, JDK {@code com.sun.net.httpserver}).
 * Measures the <b>end-to-end per-request allocation</b> of a real admin read served over a
 * real loopback connection, with {@code -prof gc} (metric {@code gc.alloc.rate.norm}, B/op).
 *
 * <h2>Method (experimental control, not a single number)</h2>
 * JMH's GC profiler is JVM-wide, so an in-process loopback measures client + server + (when
 * enabled) TLS allocation per request. To attribute, two legs share one reused
 * keep-alive {@link HttpClient}:
 * <ul>
 *   <li>{@code healthLive} - {@code GET /health/live}, a trivial constant-body handler. This
 *       is the CONTROL: the per-request garbage that exists for <b>any</b> request just from
 *       the JDK {@code HttpExchange} machinery + the client round-trip - independent of the
 *       config read path. This is the floor a hand-rolled Netty pipeline would attack.</li>
 *   <li>{@code configGet} - {@code GET /v1/config/{key}} hitting the real
 *       {@link VersionedConfigStore}. The marginal allocation over {@code healthLive} is the
 *       read path's own cost (store get + value response); the shared term is the shell.</li>
 * </ul>
 *
 * <p>{@code authMode}: {@code off} wires no auth (isolates the shell); {@code on} wires the
 * production bearer-{@link AuthInterceptor} + {@link AclService} read gate, so the delta
 * shows the authn/authz allocation. The connection is HTTP keep-alive (the steady-state
 * admin client), so no per-request handshake; TLS is out of this baseline's scope (added
 * end-to-end during migration if the surface is convicted).
 *
 * <pre>
 *   java --enable-preview -jar configd-testkit/target/benchmarks.jar \
 *       AdminHttpAllocBenchmark -prof gc -f 2 -wi 3 -i 4
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(value = 2)
public class AdminHttpAllocBenchmark {

    @Param({"off", "on"})
    String authMode;

    /**
     * The transport under test. {@code jdk} = the incumbent {@code com.sun.net.httpserver}
     * {@link HttpApiServer}; {@code netty} = the {@link NettyHttpApiServer}. The client,
     * key set, value size and shell are identical across both legs, so the {@code configGet} B/op
     * DELTA between {@code jdk} and {@code netty} is the server-side transport allocation difference.
     */
    @Param({"jdk", "netty"})
    String serverType;

    private static final int KEY_COUNT = 256;
    private static final int VALUE_BYTES = 64;
    private static final String READER_TOKEN = "good-reader";

    private HttpApiServer jdkServer;
    private NettyHttpApiServer nettyServer;
    private HttpClient client;
    private HttpRequest[] configRequests; // pre-built, rotated, to focus on the round trip
    private HttpRequest healthRequest;
    private int cursor;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        VersionedConfigStore store = new VersionedConfigStore();
        byte[] value = new byte[VALUE_BYTES];
        for (int i = 0; i < VALUE_BYTES; i++) {
            value[i] = (byte) i;
        }
        for (int i = 0; i < KEY_COUNT; i++) {
            store.put("app/svc-" + i, value, i + 1);
        }

        boolean auth = "on".equals(authMode);
        AuthInterceptor authInterceptor = auth ? new AuthInterceptor(token ->
                READER_TOKEN.equals(token)
                        ? new AuthInterceptor.AuthResult.Authenticated("reader", Set.of("read"))
                        : new AuthInterceptor.AuthResult.Denied("unknown token")) : null;
        AclService acl = null;
        if (auth) {
            acl = new AclService();
            acl.grant("app/", "reader", Set.of(AclService.Permission.READ));
        }

        int port;
        if ("netty".equals(serverType)) {
            nettyServer = new NettyHttpApiServer(
                    0, /* sslContext */ null, new HealthService(), new PrometheusExporter(registry),
                    store, /* writeService */ null, /* readService */ null,
                    authInterceptor, acl, StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(1),
                    /* auditLog */ null, /* replayGuard */ null);
            nettyServer.start();
            port = nettyServer.port();
        } else {
            jdkServer = new HttpApiServer(
                    0, /* sslContext */ null, new HealthService(), new PrometheusExporter(registry),
                    store, /* writeService */ null, /* readService */ null,
                    authInterceptor, acl, StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(1));
            jdkServer.start();
            port = jdkServer.port();
        }
        String base = "http://127.0.0.1:" + port;

        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        configRequests = new HttpRequest[KEY_COUNT];
        for (int i = 0; i < KEY_COUNT; i++) {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/v1/config/app/svc-" + i)).GET();
            if (auth) {
                b.header("Authorization", "Bearer " + READER_TOKEN);
            }
            configRequests[i] = b.build();
        }
        healthRequest = HttpRequest.newBuilder()
                .uri(URI.create(base + "/health/live")).GET().build();

        // Warm the connection + assert the wiring serves 200 (a 401/404 would measure the
        // wrong path - fail loudly rather than silently baseline an error response).
        HttpResponse<byte[]> probe = client.send(configRequests[0], HttpResponse.BodyHandlers.ofByteArray());
        if (probe.statusCode() != 200) {
            throw new IllegalStateException("configGet probe expected 200 but got " + probe.statusCode()
                    + " (authMode=" + authMode + ") — benchmark would baseline the wrong path");
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (jdkServer != null) {
            jdkServer.stop(0);
        }
        if (nettyServer != null) {
            nettyServer.stop();
        }
    }

    @Benchmark
    public int configGet() throws Exception {
        HttpRequest req = configRequests[(cursor++ & 0x7fffffff) % KEY_COUNT];
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        return resp.statusCode() + resp.body().length;
    }

    @Benchmark
    public int healthLive() throws Exception {
        HttpResponse<byte[]> resp = client.send(healthRequest, HttpResponse.BodyHandlers.ofByteArray());
        return resp.statusCode() + resp.body().length;
    }
}
