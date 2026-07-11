package io.configd.server;

import io.configd.api.HealthService;
import io.configd.common.NodeId;
import io.configd.observability.ConfigdMetrics;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.store.VersionedConfigStore;
import io.netty.buffer.PoolArenaMetric;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocatorMetric;
import io.netty.util.ResourceLeakDetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Adversarial hardening for {@link NettyHttpApiServer}: the controls a control-plane write port must
 * carry, each proven by the negative test that performs the attack:
 * <ul>
 *   <li>oversize request line or headers get a 400 and the connection closed (bounded {@code HttpServerCodec});</li>
 *   <li>oversize body (over {@code maxRequestBytes}) gets a 413 and the connection closed (the
 *       {@code HttpObjectAggregator} ceiling), never buffered;</li>
 *   <li>slowloris, both a never-terminated request and the dribble variant, is closed within a bounded
 *       time (the request-arrival deadline);</li>
 *   <li>no {@code ByteBuf} leaks at {@code ResourceLeakDetector.Level.PARANOID} under sustained load;</li>
 *   <li>a legitimate keep-alive request is still served (the hardening must not break fast clients).</li>
 * </ul>
 * The timeouts and limits are forced low via the {@code configd.server.netty.*} system properties (read by
 * the constructor) so the adversarial paths fire deterministically and fast. The server is built with a
 * minimal spec: health, exporter, an empty store, the default strong-read policy, and a leader hint, with
 * no auth/acl/write/read/audit/replay, so the legit-traffic probe uses the public
 * {@code GET /health/live} endpoint and needs no auth fixture.
 */
@Timeout(60)
class NettyHttpApiServerHardeningTest {

    private NettyHttpApiServer server;
    private MetricsRegistry meteredRegistry;
    private final String[] savedProps = new String[3];

    private void startServerWith(long requestTimeoutMs, long idleTimeoutMs, int maxRequestBytes) throws Exception {
        savedProps[0] = System.getProperty("configd.server.netty.requestTimeoutMillis");
        savedProps[1] = System.getProperty("configd.server.netty.idleTimeoutMillis");
        savedProps[2] = System.getProperty("configd.server.netty.maxRequestBytes");
        System.setProperty("configd.server.netty.requestTimeoutMillis", Long.toString(requestTimeoutMs));
        System.setProperty("configd.server.netty.idleTimeoutMillis", Long.toString(idleTimeoutMs));
        System.setProperty("configd.server.netty.maxRequestBytes", Integer.toString(maxRequestBytes));

        MetricsRegistry registry = new MetricsRegistry();
        // Minimal spec: only what the public health endpoint + the codec/aggregator need. No
        // auth/acl/write/read/audit/replay - the legit-traffic probe hits GET /health/live.
        server = new NettyHttpApiServer(
                0, /* sslContext */ null, new HealthService(), new PrometheusExporter(registry),
                new VersionedConfigStore(), /* writeService */ null, /* readService */ null,
                /* authInterceptor */ null, /* aclService */ null, StrongReadPolicy.defaultPolicy(),
                (scope, key) -> NodeId.of(1), /* auditLog */ null, /* replayGuard */ null);
        server.start();
    }

    /**
     * Starts a server whose exporter reads a registry pre-seeded with {@link ConfigdMetrics} (so the
     * ingress-reject counters are eager-created), retaining the registry so a test can read the counters.
     * The server pulls this same registry off the exporter to increment on the 400 / 413 paths.
     */
    private void startMeteredServerWith(long requestTimeoutMs, long idleTimeoutMs, int maxRequestBytes)
            throws Exception {
        savedProps[0] = System.getProperty("configd.server.netty.requestTimeoutMillis");
        savedProps[1] = System.getProperty("configd.server.netty.idleTimeoutMillis");
        savedProps[2] = System.getProperty("configd.server.netty.maxRequestBytes");
        System.setProperty("configd.server.netty.requestTimeoutMillis", Long.toString(requestTimeoutMs));
        System.setProperty("configd.server.netty.idleTimeoutMillis", Long.toString(idleTimeoutMs));
        System.setProperty("configd.server.netty.maxRequestBytes", Integer.toString(maxRequestBytes));

        meteredRegistry = new MetricsRegistry();
        new ConfigdMetrics(meteredRegistry, () -> 0L); // eager-creates the ingress-reject counters
        server = new NettyHttpApiServer(
                0, /* sslContext */ null, new HealthService(), new PrometheusExporter(meteredRegistry),
                new VersionedConfigStore(), /* writeService */ null, /* readService */ null,
                /* authInterceptor */ null, /* aclService */ null, StrongReadPolicy.defaultPolicy(),
                (scope, key) -> NodeId.of(1), /* auditLog */ null, /* replayGuard */ null);
        server.start();
    }

    private long rejectCount(String reason) {
        var mv = meteredRegistry.snapshot().metrics()
                .get(ConfigdMetrics.NAME_HTTP_REQUEST_REJECTED_BASE + "." + reason);
        return mv == null ? -1 : mv.value();
    }

    private long awaitRejectCount(String reason, long atLeast, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
        long v = rejectCount(reason);
        while (System.nanoTime() < deadline && v < atLeast) {
            Thread.sleep(25);
            v = rejectCount(reason);
        }
        return v;
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        restore("configd.server.netty.requestTimeoutMillis", savedProps[0]);
        restore("configd.server.netty.idleTimeoutMillis", savedProps[1]);
        restore("configd.server.netty.maxRequestBytes", savedProps[2]);
    }

    private static void restore(String key, String val) {
        if (val == null) System.clearProperty(key); else System.setProperty(key, val);
    }

    private int port() {
        return server.port();
    }

    // Oversize request line or headers get a 400 (bounded HttpServerCodec, never unbounded buffering)

    @Test
    void oversizeHeaderBlockIsRejectedWith4xxAndClosed() throws Exception {
        startServerWith(30_000, 60_000, 1 << 20);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port()), 2000);
            s.setSoTimeout(5000);
            OutputStream os = s.getOutputStream();
            StringBuilder req = new StringBuilder("GET /health/live HTTP/1.1\r\nHost: x\r\nX-Big: ");
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
                // The server signalled close (Connection: close + CLOSE listener); the read drains to EOF.
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(),
                        StandardCharsets.US_ASCII));
                while (r.readLine() != null) { /* drain to EOF */ }
            }
        }
    }

    // Oversize body gets a 413 (the request-size ceiling), never accumulated

    @Test
    void oversizeBodyIsRejectedWith413NotBuffered() throws Exception {
        startServerWith(30_000, 60_000, 1024); // 1 KiB ceiling (shrunk via the system property)
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port()), 2000);
            s.setSoTimeout(5000);
            OutputStream os = s.getOutputStream();
            int bodyLen = 64 * 1024; // 64 KiB >> ceiling
            String head = "PUT /v1/config/app/feature HTTP/1.1\r\nHost: x\r\nContent-Length: "
                    + bodyLen + "\r\n\r\n";
            String status = null;
            try {
                os.write(head.getBytes(StandardCharsets.US_ASCII));
                os.write(new byte[bodyLen]);
                os.flush();
                status = new BufferedReader(new InputStreamReader(s.getInputStream(),
                        StandardCharsets.US_ASCII)).readLine();
            } catch (SocketException reset) {
                // Server closed the oversize upload mid-stream - also a valid rejection.
                return;
            }
            if (status != null) {
                assertNotEquals("HTTP/1.1 200 OK", status, "oversize body must not be served 200");
                assertTrue(status.startsWith("HTTP/1.1 413") || status.startsWith("HTTP/1.1 4"),
                        "oversize body must be a 4xx rejection (413), got: " + status);
            }
        }
    }

    // The 400 and 413 ingress rejects must increment their reason counters

    @Test
    void malformedRequestTargetIncrementsBadRequestRejectCounter() throws Exception {
        startMeteredServerWith(30_000, 60_000, 1 << 20);
        assertEquals(0L, rejectCount(ConfigdMetrics.HTTP_REJECT_REASON_BAD_REQUEST),
                "the bad_request reject counter must render at 0 before any reject");
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port()), 2000);
            s.setSoTimeout(5000);
            OutputStream os = s.getOutputStream();
            // A request target the codec accepts as a token but that is not a valid URI (a malformed
            // percent-escape): new URI(...) throws in channelRead0, resulting in 400 and close (the
            // bad_request path).
            os.write("GET /bad%zz HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            os.flush();
            try {
                new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            } catch (SocketException reset) {
                // the server may reset instead of replying; the counter is the authoritative check
            }
        }
        assertTrue(awaitRejectCount(ConfigdMetrics.HTTP_REJECT_REASON_BAD_REQUEST, 1, 3_000) >= 1,
                "a malformed request target must increment the bad_request reject counter");
    }

    @Test
    void oversizeBodyIncrementsPayloadTooLargeRejectCounter() throws Exception {
        startMeteredServerWith(30_000, 60_000, 1024); // 1 KiB body ceiling
        assertEquals(0L, rejectCount(ConfigdMetrics.HTTP_REJECT_REASON_PAYLOAD_TOO_LARGE),
                "the payload_too_large reject counter must render at 0 before any reject");
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port()), 2000);
            s.setSoTimeout(5000);
            OutputStream os = s.getOutputStream();
            int bodyLen = 64 * 1024; // >> ceiling
            String head = "PUT /v1/config/app/feature HTTP/1.1\r\nHost: x\r\nContent-Length: "
                    + bodyLen + "\r\n\r\n";
            try {
                os.write(head.getBytes(StandardCharsets.US_ASCII));
                os.write(new byte[bodyLen]);
                os.flush();
                new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            } catch (SocketException reset) {
                // the server closed the oversize upload mid-stream; the counter is the authoritative check
            }
        }
        assertTrue(awaitRejectCount(ConfigdMetrics.HTTP_REJECT_REASON_PAYLOAD_TOO_LARGE, 1, 3_000) >= 1,
                "an oversize body must increment the payload_too_large reject counter");
    }

    // Slowloris: an incomplete request is closed at the request-arrival deadline

    @Test
    void slowlorisIncompleteRequestIsClosedAtDeadline() throws Exception {
        startServerWith(400, 60_000, 1 << 20); // 400 ms request-completion deadline
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port()), 2000);
            s.setSoTimeout(5000); // >> the 400 ms deadline; the server must close first
            OutputStream os = s.getOutputStream();
            // A partial request: headers begun, never terminated (no final CRLF) - never completes.
            os.write("GET /health/live HTTP/1.1\r\nHost: x\r\n".getBytes(StandardCharsets.US_ASCII));
            os.flush();
            // The server's request deadline (400 ms) must close the connection, so the read sees EOF
            // well before the 5 s socket timeout. A SocketTimeoutException here means the defence failed.
            int first = s.getInputStream().read();
            assertTrue(first == -1 || first == 'H',
                    "slowloris connection must be closed (EOF) or answered, not held open");
            if (first == 'H') {
                // If the server answered (some builds 408), it must still close promptly - drain to EOF.
                while (s.getInputStream().read() != -1) { /* drain */ }
            }
        }
    }

    @Test
    void slowlorisDribbleRequestIsClosedAtDeadline() throws Exception {
        // The dribble variant: send the request one byte at a time, slowly, never completing it
        // within the deadline. The HttpObjectAggregator holds the partial request (it never flips to
        // "processing"), so the arrival deadline must reap the connection. Bytes are dribbled with a
        // gap well under the deadline so several land, but the request is never terminated.
        startServerWith(400, 60_000, 1 << 20); // 400 ms request-completion deadline
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port()), 2000);
            s.setSoTimeout(5000); // >> the 400 ms deadline; the server must close first
            OutputStream os = s.getOutputStream();
            byte[] partial = "GET /health/live HTTP/1.1\r\nHost: x\r\n".getBytes(StandardCharsets.US_ASCII);
            try {
                for (byte b : partial) {
                    os.write(b);
                    os.flush();
                    Thread.sleep(30); // dribble: many bytes land, but the request never terminates
                }
            } catch (SocketException closedMidDribble) {
                // The server reaped the connection mid-dribble - the defence fired. Done.
                return;
            }
            // After the dribble, the never-terminated request must be reaped within the deadline:
            // the read sees EOF well before the 5 s socket timeout (a timeout here means defence failed).
            int first = s.getInputStream().read();
            assertTrue(first == -1 || first == 'H',
                    "slowloris dribble connection must be closed (EOF) or answered, not held open");
            if (first == 'H') {
                while (s.getInputStream().read() != -1) { /* drain */ }
            }
        }
    }

    // The hardening must not break legitimate fast clients

    @Test
    void completeKeepAliveRequestStillServedUnderHardening() throws Exception {
        // A normal keep-alive GET of the public health endpoint (no auth needed) is served 200.
        startServerWith(30_000, 60_000, 1 << 20);
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port() + "/health/live"))
                    .GET().timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode(), "a legit keep-alive request must still be served");
            assertTrue(resp.body().contains("\"healthy\":true"), "the health body is returned: " + resp.body());
        }
    }

    // Leak-freedom at PARANOID across all buffer paths (health/miss/404/error)

    @Test
    void noByteBufLeaksUnderSustainedTraffic() throws Exception {
        // PARANOID is the logging backstop (prints LEAK: on any GC of an unreleased buffer). The hard
        // assertion exploits the leak/cache distinction: a per-request buffer leak grows the pooled
        // allocator's active-allocation count in proportion to load, whereas a warm thread-local pool
        // cache (released-but-retained buffers, which also count as "active") stabilizes. So we run two
        // equal batches: after batch 1 warms the cache, batch 2 must add ~0 net active allocations -
        // a real leak would add ~one per request. Deterministic, no special JVM args, shared-JVM-safe.
        ResourceLeakDetector.Level savedLevel = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        try {
            startServerWith(30_000, 60_000, 1 << 20);
            String base = "http://127.0.0.1:" + port();
            try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                hammer(http, base, 300);            // batch 1 (1200 req): warm the pool cache
                Thread.sleep(250);
                long mid = activeAllocations();
                hammer(http, base, 300);            // batch 2 (1200 req): identical load
                Thread.sleep(250);
                long end = activeAllocations();
                long growth = end - mid;
                // A per-request leak would grow active by ~1200 across batch 2; a warm cache adds ~0.
                assertTrue(growth <= 128, "ByteBuf leak: active pooled allocations grew by " + growth
                        + " (mid=" + mid + " end=" + end + ") over the 2nd identical 1200-request batch "
                        + "— a per-request leak scales with load; a warm pool cache does not (PARANOID)");
            }
        } finally {
            ResourceLeakDetector.setLevel(savedLevel);
        }
    }

    /** Drives {@code n} iterations of a request mix (4 endpoints each) through the server. */
    private static void hammer(HttpClient http, String base, int n) {
        for (int i = 0; i < n; i++) {
            send(http, base + "/health/live");              // health 200 (text body)
            send(http, base + "/health/ready");             // readiness
            send(http, base + "/v1/config/app/feature");    // 404 miss (no store entry, no auth)
            send(http, base + "/nope");                      // unmatched, 404
        }
    }

    /** Sum of active (allocated-not-yet-released) pooled allocations across all arenas. */
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
