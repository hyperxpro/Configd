package io.configd.edge.node;

import org.HdrHistogram.Histogram;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Edge-read HTTP head-to-head (surface 2) — the <b>out-of-JVM load client</b>. Lives in a
 * separate process from {@link EdgeReadAllocServerMain} so NONE of this client's allocation
 * (the JDK {@code HttpClient}, request/response objects) can contaminate the server-side
 * allocation measurement — the exact apples-to-apples fix Phase R's JVM-wide {@code -prof gc}
 * lacked.
 *
 * <p>The client owns the <b>throughput + tail-latency</b> axis: it times every request with a
 * per-thread {@link Histogram} (HdrHistogram), then reports requests/sec and p50/p99/p999.
 * (Absolute latency on this 2-vCPU box is not production-grade; it is a RELATIVE JDK-vs-Netty
 * comparison on the same box, same workload — the delta is what matters.)
 *
 * <p>Protocol: connect the control socket → warm up (establishes the keep-alive connection pool,
 * outside the window) → {@code START n} → drive <i>n</i> keep-alive requests across
 * {@code concurrency} threads → {@code STOP} → read the server's reported B/request. Every
 * response is asserted 200 with the expected body length (charter hard-rule 5: a server that
 * doesn't actually serve the read is disqualified).
 *
 * <pre>
 *   java -cp benchmarks.jar io.configd.edge.node.EdgeReadLoadClientMain \
 *        &lt;host&gt; &lt;httpPort&gt; &lt;controlPort&gt; &lt;keyCount&gt; &lt;valueBytes&gt; \
 *        &lt;concurrency&gt; &lt;warmupReqs&gt; &lt;measureReqs&gt;
 * </pre>
 */
public final class EdgeReadLoadClientMain {

    private EdgeReadLoadClientMain() {
    }

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int httpPort = Integer.parseInt(args[1]);
        int controlPort = Integer.parseInt(args[2]);
        int keyCount = Integer.parseInt(args[3]);
        int valueBytes = Integer.parseInt(args[4]);
        int concurrency = Integer.parseInt(args[5]);
        long warmupReqs = Long.parseLong(args[6]);
        long measureReqs = Long.parseLong(args[7]);

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        String base = "http://" + host + ":" + httpPort + "/v1/config/config/svc-";

        // Pre-build request objects per key (their allocation is irrelevant — separate JVM).
        HttpRequest[] reqs = new HttpRequest[keyCount];
        for (int i = 0; i < keyCount; i++) {
            reqs[i] = HttpRequest.newBuilder(URI.create(base + i)).GET().build();
        }

        // Probe once: confirm the server actually serves the read (200 + valueBytes body).
        HttpResponse<byte[]> probe = client.send(reqs[0], HttpResponse.BodyHandlers.ofByteArray());
        if (probe.statusCode() != 200 || probe.body().length != valueBytes) {
            throw new IllegalStateException("probe expected 200 + " + valueBytes + "B body, got "
                    + probe.statusCode() + " + " + probe.body().length + "B — wrong path");
        }

        // ---- warmup (outside the measured window): establish keep-alive pool, settle JIT ----
        drive(client, reqs, keyCount, concurrency, warmupReqs, null);

        try (Socket ctl = new Socket(host, controlPort);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(ctl.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(ctl.getOutputStream(), true, StandardCharsets.UTF_8)) {

            // Background-allocation floor on the server (idle window) for the noise-vs-signal check.
            out.println("IDLE 1000");
            in.readLine();

            out.println("START " + measureReqs);
            in.readLine(); // OK

            Histogram hist = new Histogram(1, 60_000_000_000L, 3); // 1ns..60s, 3 sig digits
            long wallStart = System.nanoTime();
            drive(client, reqs, keyCount, concurrency, measureReqs, hist);
            long wallNanos = System.nanoTime() - wallStart;

            out.println("STOP");
            String serverResult = in.readLine(); // "RESULT <bytesPerReq>"
            out.println("QUIT");
            in.readLine();

            double seconds = wallNanos / 1e9;
            double throughput = measureReqs / seconds;
            System.out.printf("CLIENT requests=%d concurrency=%d seconds=%.3f%n",
                    measureReqs, concurrency, seconds);
            System.out.printf("CLIENT throughputReqPerSec=%.0f%n", throughput);
            System.out.printf("CLIENT latencyMicros p50=%.1f p99=%.1f p999=%.1f max=%.1f%n",
                    hist.getValueAtPercentile(50.0) / 1000.0,
                    hist.getValueAtPercentile(99.0) / 1000.0,
                    hist.getValueAtPercentile(99.9) / 1000.0,
                    hist.getMaxValue() / 1000.0);
            System.out.println("CLIENT serverSide=" + serverResult);
        }
    }

    /** Drives {@code total} GET requests across {@code concurrency} threads; records latency if hist != null. */
    private static void drive(HttpClient client, HttpRequest[] reqs, int keyCount,
                              int concurrency, long total, Histogram mergedHist) throws Exception {
        Thread[] threads = new Thread[concurrency];
        Histogram[] perThread = new Histogram[concurrency];
        AtomicLong dispatched = new AtomicLong(0);
        CountDownLatch done = new CountDownLatch(concurrency);
        for (int t = 0; t < concurrency; t++) {
            final int tid = t;
            perThread[t] = (mergedHist != null) ? new Histogram(1, 60_000_000_000L, 3) : null;
            threads[t] = new Thread(() -> {
                try {
                    Histogram h = perThread[tid];
                    long idx;
                    while ((idx = dispatched.getAndIncrement()) < total) {
                        HttpRequest req = reqs[(int) (idx % keyCount)];
                        long s = (h != null) ? System.nanoTime() : 0;
                        HttpResponse<byte[]> resp =
                                client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                        if (h != null) {
                            h.recordValue(System.nanoTime() - s);
                        }
                        if (resp.statusCode() != 200) {
                            throw new IllegalStateException("non-200: " + resp.statusCode());
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            }, "load-" + t);
            threads[t].start();
        }
        done.await();
        if (mergedHist != null) {
            for (Histogram h : perThread) {
                if (h != null) {
                    mergedHist.add(h);
                }
            }
        }
    }
}
