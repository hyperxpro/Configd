package io.configd.bench;

import org.HdrHistogram.Histogram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>Open-loop, coordinated-omission-corrected</b> HTTP write
 * load driver against a live Configd control-plane cluster (methodology section 3b).
 *
 * <h2>Coordinated omission handling (methodology section 3b - open-loop intended-time scheduling)</h2>
 * Each request {@code i} has a <b>scheduled send time</b> {@code t_i = startNanos + i / rate}.
 * A submitter releases request {@code i} at its scheduled slot <em>regardless of how many prior
 * requests are still in flight</em>. Latency is recorded as {@code completion - t_i} (the
 * SCHEDULED time, not the actual send time). A stall therefore inflates the measured latency of
 * every request whose scheduled slot fell inside the stall - exactly the requests CO would drop.
 * This is NOT a naive closed-loop "send-next-after-previous-returns" generator (forbidden).
 *
 * <p>Because each {@code PUT /v1/config} BLOCKS until quorum commit, the driver uses a
 * bounded worker pool to carry concurrent in-flight requests; the SCHEDULE is owned by the main
 * submitter thread which does not wait on completions.
 *
 * <h2>Leader following (operational reality on the throttled box)</h2>
 * On a CPU-credit-throttled 2-vCPU box, Raft leadership churns (election timeout 150 - 300 ms can
 * fire faster than load settles). A write to a non-leader returns <b>503 with an
 * {@code X-Leader-Hint: &lt;nodeId&gt;}</b> header. The driver is given the full node-id -> API-URL
 * map and, on a 503 hint, retargets to the hinted leader. This is a legitimate client behaviour
 * (a real client follows the hint), and the driver <b>counts and reports</b> retargets +
 * per-status counts so churn is visible, never hidden. The CO clock still uses the scheduled send
 * time, so a churn stall inflates the recorded latency rather than dropping it.
 *
 * <h2>Self-calibration</h2>
 * Mode {@code calibrate} drives as fast as it can (closed loop, N workers) to find the ceiling
 * commit rate the harness+server sustain; an at-rate run must stay below that ceiling with
 * headroom or it is a generator/server-saturation finding, not a clean latency number.
 *
 * <h2>Modes (nodeMap = "1=http://127.0.0.1:8181,2=...,3=...")</h2>
 * <pre>
 *   calibrate &lt;nodeMap&gt; &lt;durationSec&gt; &lt;concurrency&gt; [valueBytes]
 *   atrate    &lt;nodeMap&gt; &lt;targetRate&gt; &lt;durationSec&gt; &lt;concurrency&gt; [valueBytes]
 * </pre>
 */
public final class OpenLoopWriteDriver {

    private OpenLoopWriteDriver() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: OpenLoopWriteDriver <calibrate|atrate> <nodeMap> ...");
            System.exit(2);
        }
        switch (args[0]) {
            case "calibrate" -> calibrate(args);
            case "atrate" -> atRate(args);
            default -> {
                System.err.println("unknown mode: " + args[0]);
                System.exit(2);
            }
        }
    }

    /** Parse "1=http://h:8181,2=http://h:8182,3=..." -> ordered list of base URLs + id map. */
    private static Map<Integer, String> parseNodeMap(String spec) {
        Map<Integer, String> m = new HashMap<>();
        for (String part : spec.split(",")) {
            int eq = part.indexOf('=');
            m.put(Integer.parseInt(part.substring(0, eq).trim()), part.substring(eq + 1).trim());
        }
        return m;
    }

    private static HttpClient newClient() {
        // Do NOT pin a small fixed executor: synchronous send() uses the client executor for its
        // internal IO; starving it deadlocks the client. Let the client manage its own executor.
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static HttpRequest put(String baseUrl, long i, byte[] value) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/config/perf/wsB/" + (i & 0xFFFFF)))
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(value))
                .build();
    }

    /** Resolve the current leader URL by probing each node with a real committed PUT. */
    private static String resolveLeader(HttpClient client, Map<Integer, String> nodes, byte[] value) {
        for (var e : nodes.entrySet()) {
            try {
                HttpResponse<Void> r = client.send(put(e.getValue(), -1, value), HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() == 200) return e.getValue();
                // follow a hint if present
                var hint = r.headers().firstValue("X-Leader-Hint");
                if (hint.isPresent()) {
                    String url = nodes.get(Integer.parseInt(hint.get()));
                    if (url != null) return url;
                }
            } catch (Exception ignored) {}
        }
        return nodes.values().iterator().next(); // fall back to any node
    }

    // ------------------------------------------------------------------
    // calibrate: closed-loop max sustainable commit rate (precondition for at-rate runs)
    // ------------------------------------------------------------------
    private static void calibrate(String[] args) throws Exception {
        Map<Integer, String> nodes = parseNodeMap(args[1]);
        int durationSec = Integer.parseInt(args[2]);
        int concurrency = args.length > 3 ? Integer.parseInt(args[3]) : 32;
        int valueBytes = args.length > 4 ? Integer.parseInt(args[4]) : 256;
        byte[] value = makeValue(valueBytes);

        HttpClient client = newClient();
        AtomicReference<String> leader = new AtomicReference<>(resolveLeader(client, nodes, value));
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        AtomicLong ok = new AtomicLong();
        AtomicLong nonOk = new AtomicLong();
        AtomicLong retargets = new AtomicLong();
        AtomicLong seq = new AtomicLong();
        ConcurrentHashMap<Integer, AtomicInteger> status = new ConcurrentHashMap<>();
        long start = System.nanoTime();
        long warmEnd = start + 3_000_000_000L; // 3 s warmup not counted
        long deadline = start + durationSec * 1_000_000_000L;

        Runnable worker = () -> {
            while (System.nanoTime() < deadline) {
                long i = seq.getAndIncrement();
                String url = leader.get();
                boolean counted = System.nanoTime() >= warmEnd;
                try {
                    HttpResponse<Void> r = client.send(put(url, i, value), HttpResponse.BodyHandlers.discarding());
                    if (counted) status.computeIfAbsent(r.statusCode(), k -> new AtomicInteger()).incrementAndGet();
                    if (r.statusCode() == 200) {
                        if (counted) ok.incrementAndGet();
                    } else {
                        if (counted) nonOk.incrementAndGet();
                        followHint(r, nodes, leader, retargets);
                    }
                } catch (Exception e) {
                    if (counted) nonOk.incrementAndGet();
                }
            }
        };
        for (int w = 0; w < concurrency; w++) pool.submit(worker);
        pool.shutdown();
        pool.awaitTermination(durationSec + 30L, TimeUnit.SECONDS);
        double measureSec = (deadline - warmEnd) / 1e9;
        double rate = ok.get() / measureSec;
        System.out.printf("CALIBRATE-RESULT concurrency=%d measureSec=%.1f committed_200=%d non200=%d retargets=%d sustained_commit_rate_per_sec=%.0f%n",
                concurrency, measureSec, ok.get(), nonOk.get(), retargets.get(), rate);
        System.out.printf("CALIBRATE-STATUS %s%n", statusString(status));
    }

    // ------------------------------------------------------------------
    // atrate: open-loop, CO-corrected, latency-at-rate
    // ------------------------------------------------------------------
    private static void atRate(String[] args) throws Exception {
        Map<Integer, String> nodes = parseNodeMap(args[1]);
        double targetRate = Double.parseDouble(args[2]);
        int durationSec = Integer.parseInt(args[3]);
        int concurrency = args.length > 4 ? Integer.parseInt(args[4]) : 256;
        int valueBytes = args.length > 5 ? Integer.parseInt(args[5]) : 256;
        byte[] value = makeValue(valueBytes);

        HttpClient client = newClient();
        AtomicReference<String> leader = new AtomicReference<>(resolveLeader(client, nodes, value));

        // Bounded worker pool; a full queue means generator/server backpressure (counted), not
        // a stalled schedule (which would reintroduce CO).
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                concurrency, concurrency, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(concurrency * 8));

        Histogram h = new Histogram(1L, 120_000_000L, 3); // microseconds
        Object hLock = new Object();
        AtomicLong ok = new AtomicLong();
        AtomicLong rejected = new AtomicLong();
        AtomicLong exceptions = new AtomicLong();
        AtomicLong retargets = new AtomicLong();
        ConcurrentHashMap<Integer, AtomicInteger> status = new ConcurrentHashMap<>();

        long total = (long) (targetRate * durationSec);
        double intervalNanos = 1_000_000_000.0 / targetRate;
        long start = System.nanoTime();

        for (long i = 0; i < total; i++) {
            long scheduled = start + (long) (i * intervalNanos);
            long waitNs = scheduled - System.nanoTime();
            if (waitNs > 2_000_000L) {
                try { Thread.sleep((waitNs - 1_000_000L) / 1_000_000L); } catch (InterruptedException ignored) {}
            }
            while (System.nanoTime() < scheduled) { Thread.onSpinWait(); }

            final long fi = i;
            final long fScheduled = scheduled;
            try {
                pool.execute(() -> {
                    String url = leader.get();
                    try {
                        HttpResponse<Void> r = client.send(put(url, fi, value), HttpResponse.BodyHandlers.discarding());
                        long latencyUs = (System.nanoTime() - fScheduled) / 1000; // CO: vs SCHEDULED time
                        record(h, hLock, latencyUs);
                        status.computeIfAbsent(r.statusCode(), k -> new AtomicInteger()).incrementAndGet();
                        if (r.statusCode() == 200) ok.incrementAndGet();
                        else followHint(r, nodes, leader, retargets);
                    } catch (Exception e) {
                        exceptions.incrementAndGet();
                        record(h, hLock, (System.nanoTime() - fScheduled) / 1000);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException rex) {
                // Worker pool + queue full = generator/server backpressure. Record the intended-time
                // latency anyway (CO: the request SHOULD have completed) and count the rejection.
                rejected.incrementAndGet();
                record(h, hLock, (System.nanoTime() - fScheduled) / 1000);
            }
        }
        pool.shutdown();
        pool.awaitTermination(180, TimeUnit.SECONDS);
        double elapsedSec = (System.nanoTime() - start) / 1e9;
        double achievedRate = ok.get() / elapsedSec;

        System.out.printf("ATRATE-RESULT targetRate=%.0f durationSec=%d concurrency=%d valueBytes=%d intended=%d committed_200=%d rejected_backpressure=%d exceptions=%d retargets=%d achieved_commit_rate=%.0f elapsedSec=%.1f%n",
                targetRate, durationSec, concurrency, valueBytes, total, ok.get(), rejected.get(), exceptions.get(), retargets.get(), achievedRate, elapsedSec);
        System.out.printf("ATRATE-STATUS %s%n", statusString(status));
        synchronized (hLock) {
            System.out.printf("ATRATE-HISTOGRAM unit=us co_corrected=intended-time count=%d p50=%d p90=%d p99=%d p999=%d p9999=%d max=%d mean=%.0f%n",
                    h.getTotalCount(),
                    h.getValueAtPercentile(50.0), h.getValueAtPercentile(90.0),
                    h.getValueAtPercentile(99.0), h.getValueAtPercentile(99.9),
                    h.getValueAtPercentile(99.99), h.getMaxValue(), h.getMean());
        }
    }

    // ------------------------------------------------------------------
    private static void record(Histogram h, Object lock, long latencyUs) {
        long v = Math.min(Math.max(latencyUs, 1), 120_000_000L);
        synchronized (lock) { h.recordValue(v); }
    }

    private static void followHint(HttpResponse<?> r, Map<Integer, String> nodes,
                                   AtomicReference<String> leader, AtomicLong retargets) {
        var hint = r.headers().firstValue("X-Leader-Hint");
        if (hint.isPresent()) {
            try {
                String url = nodes.get(Integer.parseInt(hint.get()));
                if (url != null && !url.equals(leader.get())) {
                    leader.set(url);
                    retargets.incrementAndGet();
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    private static String statusString(Map<Integer, AtomicInteger> status) {
        List<Integer> codes = new ArrayList<>(status.keySet());
        codes.sort(Integer::compareTo);
        StringBuilder sb = new StringBuilder();
        for (Integer c : codes) sb.append(c).append('=').append(status.get(c).get()).append(' ');
        return sb.toString().trim();
    }

    private static byte[] makeValue(int n) {
        byte[] v = new byte[n];
        for (int i = 0; i < n; i++) v[i] = (byte) (i * 17 + 3);
        return v;
    }
}
