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
import org.openjdk.jmh.annotations.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(value = 2)
public class EdgeHttpAllocBenchmark {

    /** A fixed clock: time never advances, so the edge core stays CURRENT (reads served). */
    private static final class FixedClock implements Clock {
        @Override public long currentTimeMillis() { return 1_000_000L; }
        @Override public long nanoTime() { return 1_000_000L * 1_000_000L; }
    }

    private static final int KEY_COUNT = 256;
    private static final int VALUE_BYTES = 64;

    private EdgeHttpServer server;
    private HttpClient client;
    private HttpRequest[] configRequests;
    private HttpRequest healthRequest;
    private int cursor;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        Clock clock = new FixedClock();
        MetricsRegistry registry = new MetricsRegistry();
        InvariantMonitor monitor = new InvariantMonitor(registry, false); // production: never throws
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

        server = new EdgeHttpServer(0, core, StrongReadKeyClass.DEFAULT,
                new PrometheusExporter(registry), metrics);
        server.start();
        String base = "http://127.0.0.1:" + server.port();

        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        configRequests = new HttpRequest[KEY_COUNT];
        for (int i = 0; i < KEY_COUNT; i++) {
            configRequests[i] = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/v1/config/config/svc-" + i)).GET().build();
        }
        healthRequest = HttpRequest.newBuilder()
                .uri(URI.create(base + "/health/live")).GET().build();

        HttpResponse<byte[]> probe = client.send(configRequests[0], HttpResponse.BodyHandlers.ofByteArray());
        if (probe.statusCode() != 200) {
            throw new IllegalStateException("edge configGet probe expected 200 but got "
                    + probe.statusCode() + " — benchmark would baseline the wrong (refused) path");
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Benchmark
    public int configGet() throws Exception {
        HttpRequest req = configRequests[(cursor++ & 0x7fffffff) % KEY_COUNT];
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        return resp.statusCode() + resp.body().length;
    }

    /** CONTROL: GET /health/live - the JDK shell + client round-trip floor (no read path). */
    @Benchmark
    public int healthLive() throws Exception {
        HttpResponse<byte[]> resp = client.send(healthRequest, HttpResponse.BodyHandlers.ofByteArray());
        return resp.statusCode() + resp.body().length;
    }
}
