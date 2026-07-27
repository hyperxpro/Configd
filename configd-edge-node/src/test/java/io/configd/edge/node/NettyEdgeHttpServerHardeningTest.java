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
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocatorMetric;
import io.netty.buffer.PoolArenaMetric;
import io.netty.util.ResourceLeakDetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Adversarial hardening for {@link NettyEdgeHttpServer}, proven by negative tests:
 * <ul>
 *   <li>oversize request line/headers - 400 + closed (bounded {@code HttpServerCodec});</li>
 *   <li>oversize body - rejected, never buffered (the request-size ceiling);</li>
 *   <li>slowloris (a request that never completes) - connection closed at the request deadline;</li>
 *   <li>no {@code ByteBuf} leaks at {@code ResourceLeakDetector.Level.PARANOID}.</li>
 * </ul>
 * Timeouts/limits are forced low via system properties (read by the server constructor) so the
 * adversarial paths fire deterministically and fast.
 */
@Timeout(60)
class NettyEdgeHttpServerHardeningTest {

    private NettyEdgeHttpServer server;
    private final String[] savedProps = new String[3];

    private void startServerWith(long requestTimeoutMs, long idleTimeoutMs, int maxRequestBytes)
            throws Exception {
        savedProps[0] = System.getProperty("configd.edge.netty.requestTimeoutMillis");
        savedProps[1] = System.getProperty("configd.edge.netty.idleTimeoutMillis");
        savedProps[2] = System.getProperty("configd.edge.netty.maxRequestBytes");
        System.setProperty("configd.edge.netty.requestTimeoutMillis", Long.toString(requestTimeoutMs));
        System.setProperty("configd.edge.netty.idleTimeoutMillis", Long.toString(idleTimeoutMs));
        System.setProperty("configd.edge.netty.maxRequestBytes", Integer.toString(maxRequestBytes));

        MetricsRegistry registry = new MetricsRegistry();
        InvariantMonitor monitor = new InvariantMonitor(registry, false);
        EdgeNodeMetrics metrics = new EdgeNodeMetrics(registry);
        Clock clock = new Clock() {
            @Override public long currentTimeMillis() { return 1_000_000L; }
            @Override public long nanoTime() { return 1_000_000L * 1_000_000L; }
        };
        EdgeClientCore core = new EdgeClientCore(clock, monitor, metrics.implausibleCounter(),
                StrongReadKeyClass.DEFAULT, EdgeClientCore.FrameSink.NONE,
                EdgeClientCore.DEFAULT_HEARTBEAT_MS, EdgeClientCore.DEFAULT_SILENCE_FACTOR);
        metrics.bind(core);
        ConfigDelta delta = new ConfigDelta(0, 1,
                List.of(new ConfigMutation.Put("svc/a", "v1".getBytes(StandardCharsets.UTF_8))));
        core.onFrame(new EdgeFrame.Notify(List.of(new CommitNotification(1, 1_000_000L, delta))));

        server = new NettyEdgeHttpServer(0, core, StrongReadKeyClass.DEFAULT,
                new PrometheusExporter(registry), metrics);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        restore("configd.edge.netty.requestTimeoutMillis", savedProps[0]);
        restore("configd.edge.netty.idleTimeoutMillis", savedProps[1]);
        restore("configd.edge.netty.maxRequestBytes", savedProps[2]);
    }

    private static void restore(String key, String val) {
        if (val == null) System.clearProperty(key); else System.setProperty(key, val);
    }

    private int port() {
        return server.port();
    }


    @Test
    void oversizeHeaderBlockIsRejectedWith4xx() throws Exception {
        startServerWith(30_000, 60_000, 1 << 20);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port()), 2000);
            s.setSoTimeout(5000);
            OutputStream os = s.getOutputStream();
            StringBuilder req = new StringBuilder("GET /v1/config/svc/a HTTP/1.1\r\nHost: x\r\nX-Big: ");
            req.append("A".repeat(16384));   // > the 8 KiB header cap
            req.append("\r\n\r\n");
            os.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            os.flush();
            String status = new BufferedReader(new InputStreamReader(s.getInputStream(),
                    StandardCharsets.US_ASCII)).readLine();
            // Either a 4xx status line then close, or an immediate close (both = rejected, not served).
            if (status != null) {
                assertTrue(status.startsWith("HTTP/1.1 4"),
                        "oversize header must be a 4xx rejection, got: " + status);
            }
        }
    }


    @Test
    void oversizeBodyIsRejectedNotBuffered() throws Exception {
        startServerWith(30_000, 60_000, 1024); // 1 KiB ceiling
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port()), 2000);
            s.setSoTimeout(5000);
            OutputStream os = s.getOutputStream();
            int bodyLen = 64 * 1024; // 64 KiB >> ceiling
            String head = "POST /v1/config/svc/a HTTP/1.1\r\nHost: x\r\nContent-Length: "
                    + bodyLen + "\r\n\r\n";
            String status = null;
            try {
                os.write(head.getBytes(StandardCharsets.US_ASCII));
                os.write(new byte[bodyLen]);
                os.flush();
                status = new BufferedReader(new InputStreamReader(s.getInputStream(),
                        StandardCharsets.US_ASCII)).readLine();
            } catch (SocketException reset) {
                // Server closed the oversize upload mid-stream — also a valid rejection.
                return;
            }
            if (status != null) {
                assertNotEquals("HTTP/1.1 200 OK", status, "oversize body must not be served 200");
                assertTrue(status.startsWith("HTTP/1.1 4"),
                        "oversize body must be a 4xx rejection (413), got: " + status);
            }
        }
    }


    @Test
    void slowlorisIncompleteRequestIsClosedAtDeadline() throws Exception {
        startServerWith(400, 60_000, 1 << 20); // 400 ms request-completion deadline
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port()), 2000);
            s.setSoTimeout(5000); // >> the 400 ms deadline; the server must close first
            OutputStream os = s.getOutputStream();
            // A partial request: headers begun, never terminated (no final CRLF) — never completes.
            os.write("GET /v1/config/svc/a HTTP/1.1\r\nHost: x\r\n".getBytes(StandardCharsets.US_ASCII));
            os.flush();
            // The server's request deadline (400 ms) must close the connection → read sees EOF
            // well before the 5 s socket timeout. A SocketTimeoutException here = the defence failed.
            int first = s.getInputStream().read();
            assertTrue(first == -1 || first == 'H',
                    "slowloris connection must be closed (EOF) or answered, not held open");
            if (first == 'H') {
                // If the server answered (some builds 408), it must still close promptly — drain to EOF.
                while (s.getInputStream().read() != -1) { }
            }
        }
    }

    @Test
    void completeKeepAliveRequestStillServedUnderHardening() throws Exception {
        // The hardening must not break legitimate fast clients: a normal keep-alive GET is served 200.
        startServerWith(30_000, 60_000, 1 << 20);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port() + "/v1/config/svc/a"))
                .GET().timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
        org.junit.jupiter.api.Assertions.assertEquals(200, resp.statusCode());
        org.junit.jupiter.api.Assertions.assertEquals("v1", resp.body());
    }


    @Test
    void noByteBufLeaksUnderSustainedTraffic() throws Exception {
        // PARANOID logs "LEAK:" on any GC of an unreleased buffer. The hard assertion exploits the
        // leak/cache distinction: a per-request LEAK grows the pooled allocator's active-allocation
        // count in proportion to load, whereas a warm thread-local pool CACHE (released-but-retained
        // buffers, which also count as "active") stabilizes. Two equal batches: after batch 1 warms
        // the cache, batch 2 must add ~0 net active allocations — a real leak adds ~one per request.
        // Deterministic, no special JVM args, shared-JVM-safe.
        ResourceLeakDetector.Level savedLevel = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        try {
            startServerWith(30_000, 60_000, 1 << 20);
            String base = "http://127.0.0.1:" + port();
            try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                hammer(http, base, 300);
                Thread.sleep(250);
                long mid = activeAllocations();
                hammer(http, base, 300);
                Thread.sleep(250);
                long end = activeAllocations();
                long growth = end - mid;
                assertTrue(growth <= 128, "ByteBuf leak: active pooled allocations grew by " + growth
                        + " (mid=" + mid + " end=" + end + ") over the 2nd identical 1500-request batch "
                        + "— a per-request leak scales with load; a warm pool cache does not (PARANOID)");
            }
        } finally {
            ResourceLeakDetector.setLevel(savedLevel);
        }
    }

    private static void hammer(HttpClient http, String base, int n) {
        for (int i = 0; i < n; i++) {
            send(http, base + "/v1/config/svc/a");          // hit (pooled body)
            send(http, base + "/v1/config/svc/missing");    // miss (text body)
            send(http, base + "/v1/config/secure/x");       // strong-read 503
            send(http, base + "/health/live");              // health
            send(http, base + "/metrics");                  // exposition
        }
    }

    private static long activeAllocations() {
        PooledByteBufAllocatorMetric m = PooledByteBufAllocator.DEFAULT.metric();
        long n = 0;
        for (PoolArenaMetric a : m.directArenas()) {
            n += a.numActiveAllocations();
        }
        for (PoolArenaMetric a : m.heapArenas()) {
            n += a.numActiveAllocations();
        }
        return n;
    }

    private static void send(HttpClient http, String url) {
        try {
            http.send(HttpRequest.newBuilder().uri(URI.create(url)).GET()
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            fail("request failed: " + url + " — " + e);
        }
    }
}
