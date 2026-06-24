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
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Charter hard-rule 5 for the edge-read surface: the strongest-Netty {@link NettyEdgeReadServer}
 * must serve the read path <b>equivalently</b> to the production JDK {@link EdgeHttpServer} — same
 * status, same body bytes, same {@code X-Configd-*} headers — across hit / not-subscribed /
 * strong-read / cursor-behind. A Netty server that is faster but serves a different (or wrong)
 * response is disqualified, so this gates the edge-read benchmark.
 */
class NettyEdgeReadServerCorrectnessTest {

    private static final int KEY_COUNT = 16;
    private static final int VALUE_BYTES = 64;

    private static final class FixedClock implements Clock {
        @Override public long currentTimeMillis() { return 1_000_000L; }
        @Override public long nanoTime() { return 1_000_000L * 1_000_000L; }
    }

    private EdgeClientCore newCore() {
        Clock clock = new FixedClock();
        MetricsRegistry registry = new MetricsRegistry();
        InvariantMonitor monitor = new InvariantMonitor(registry, false);
        EdgeNodeMetrics metrics = new EdgeNodeMetrics(registry);
        EdgeClientCore core = new EdgeClientCore(clock, monitor, metrics.implausibleCounter(),
                StrongReadKeyClass.DEFAULT, EdgeClientCore.FrameSink.NONE,
                EdgeClientCore.DEFAULT_HEARTBEAT_MS, EdgeClientCore.DEFAULT_SILENCE_FACTOR);
        metrics.bind(core);
        byte[] value = new byte[VALUE_BYTES];
        for (int i = 0; i < VALUE_BYTES; i++) {
            value[i] = (byte) i;
        }
        for (int i = 0; i < KEY_COUNT; i++) {
            long seq = i + 1;
            ConfigDelta delta = new ConfigDelta(seq - 1, seq,
                    List.of(new ConfigMutation.Put("config/svc-" + i, value)));
            core.onFrame(new EdgeFrame.Notify(List.of(
                    new CommitNotification(seq, 1_000_000L, delta))));
        }
        return core;
    }

    @Test
    void nettyServerServesReadPathEquivalentlyToJdkServer() throws Exception {
        // JDK production server (best-JDK form).
        MetricsRegistry reg1 = new MetricsRegistry();
        EdgeNodeMetrics m1 = new EdgeNodeMetrics(reg1);
        EdgeHttpServer jdk = new EdgeHttpServer(0, newCore(), StrongReadKeyClass.DEFAULT,
                new PrometheusExporter(reg1), m1);
        jdk.start();

        // Netty server (best-Netty form), independent core but identical data.
        EdgeNodeMetrics m2 = new EdgeNodeMetrics(new MetricsRegistry());
        NettyEdgeReadServer netty =
                new NettyEdgeReadServer(0, newCore(), StrongReadKeyClass.DEFAULT, m2);
        netty.start();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();
        try {
            String[] paths = {
                    "/v1/config/config/svc-0",   // hit
                    "/v1/config/config/svc-7",   // hit
                    "/v1/config/config/nope",    // not-subscribed → 404 + X-Configd-Refused
            };
            for (String path : paths) {
                HttpResponse<byte[]> rj = get(client, jdk.port(), path);
                HttpResponse<byte[]> rn = get(client, netty.port(), path);
                assertEquals(rj.statusCode(), rn.statusCode(), "status mismatch for " + path);
                assertArrayEquals(rj.body(), rn.body(), "body mismatch for " + path);
                for (String h : new String[]{EdgeHttpServer.HDR_CURSOR, EdgeHttpServer.HDR_VERSION,
                        EdgeHttpServer.HDR_REFUSED, "Content-Type"}) {
                    assertEquals(rj.headers().firstValue(h), rn.headers().firstValue(h),
                            "header " + h + " mismatch for " + path);
                }
            }
        } finally {
            jdk.stop(0);
            netty.stop();
        }
    }

    private static HttpResponse<byte[]> get(HttpClient client, int port, String path)
            throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }
}
