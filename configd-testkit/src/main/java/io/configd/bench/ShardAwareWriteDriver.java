package io.configd.bench;

import org.HdrHistogram.Histogram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Multi-Raft <b>shard-aware</b> open-loop write driver - the Nxknee load generator.
 *
 * <p>{@link OpenLoopWriteDriver} keeps a single leader pointer, which thrashes against an
 * N-group cluster whose N shard leaders are scattered across the nodes (each PUT to the wrong
 * shard leader gets a 503 + hint). This driver instead replicates the server's routing
 * ({@link io.configd.replication.StaticShardMap}: {@code shardFor = floorMod(SplitMix64(FNV1a(scope,
 * key)), N)}) and keeps a <b>per-shard leader pointer</b>, learned from the {@code X-Leader-Hint}
 * on a 503. So every write is sent to the node that currently leads that key's shard - the
 * production-faithful sharded-client pattern. The open-loop, coordinated-omission-corrected
 * scheduling and the ATRATE-RESULT/STATUS/HISTOGRAM output are identical to {@link OpenLoopWriteDriver}
 * so the existing harness parsing is unchanged.
 *
 * <p>Election timeouts on the cluster stay realistic + symmetric (leaders scatter naturally); this
 * driver does NOT depend on any leader-placement trick, so the measured knee is the real one.
 *
 * <pre>
 *   atrate-sharded &lt;nodeMap&gt; &lt;N&gt; &lt;targetRate&gt; &lt;durationSec&gt; &lt;concurrency&gt; [valueBytes]
 *     nodeMap = "1=http://127.0.0.1:8281,2=http://127.0.0.1:8282,3=..."
 * </pre>
 */
public final class ShardAwareWriteDriver {

    private static final long FNV_OFFSET_BASIS = 1469598103934665603L;
    private static final long FNV_PRIME = 1099511628211L;
    private static final int SCOPE_ORDINAL = 0; // ConfigScope.GLOBAL - the default write scope

    private ShardAwareWriteDriver() {}

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        if (!"atrate-sharded".equals(mode) && !"calibrate-sharded".equals(mode)) {
            System.err.println("usage: ShardAwareWriteDriver atrate-sharded    <nodeMap> <N> <targetRate> <durationSec> <concurrency> [valueBytes]");
            System.err.println("       ShardAwareWriteDriver calibrate-sharded <nodeMap> <N> <durationSec> <concurrency> [valueBytes]");
            System.exit(2);
        }
        if ("calibrate-sharded".equals(mode)) { calibrate(args); return; }
        Map<Integer, String> nodes = parseNodeMap(args[1]);
        int n = Integer.parseInt(args[2]);
        double targetRate = Double.parseDouble(args[3]);
        int durationSec = Integer.parseInt(args[4]);
        int concurrency = Integer.parseInt(args[5]);
        int valueBytes = args.length > 6 ? Integer.parseInt(args[6]) : 256;
        byte[] value = makeValue(valueBytes);

        HttpClient client = newClient();
        AtomicReferenceArray<String> shardLeader = resolveShardLeaders(client, nodes, n, value);

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                concurrency, concurrency, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(concurrency * 8));
        Histogram h = new Histogram(1L, 120_000_000L, 3);
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
                    String k = key(fi);
                    int s = shardFor(k, n);
                    try {
                        String url = shardLeader.get(s);
                        HttpResponse<Void> r = client.send(put(url, k, value), HttpResponse.BodyHandlers.discarding());
                        long latencyUs = (System.nanoTime() - fScheduled) / 1000;
                        record(h, hLock, latencyUs);
                        status.computeIfAbsent(r.statusCode(), x -> new AtomicInteger()).incrementAndGet();
                        if (r.statusCode() == 200) ok.incrementAndGet();
                        else followHint(r, nodes, shardLeader, s, retargets);
                    } catch (Exception e) {
                        exceptions.incrementAndGet();
                        record(h, hLock, (System.nanoTime() - fScheduled) / 1000);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException rex) {
                rejected.incrementAndGet();
                record(h, hLock, (System.nanoTime() - fScheduled) / 1000);
            }
        }
        pool.shutdown();
        pool.awaitTermination(180, TimeUnit.SECONDS);
        double elapsedSec = (System.nanoTime() - start) / 1e9;
        double achievedRate = ok.get() / elapsedSec;

        System.out.printf("ATRATE-RESULT targetRate=%.0f durationSec=%d concurrency=%d valueBytes=%d shards=%d intended=%d committed_200=%d rejected_backpressure=%d exceptions=%d retargets=%d achieved_commit_rate=%.0f elapsedSec=%.1f%n",
                targetRate, durationSec, concurrency, valueBytes, n, total, ok.get(), rejected.get(), exceptions.get(), retargets.get(), achievedRate, elapsedSec);
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
    // calibrate-sharded: CLOSED-loop max sustainable commit rate. N workers each loop
    // send-as-fast-as-possible, routing per shard; the achieved 200/s is the cluster's real
    // throughput ceiling at this concurrency (no open-loop schedule to fall behind, so it is NOT
    // contaminated by driver backpressure the way an over-driven open-loop atrate run is).
    // ------------------------------------------------------------------
    private static void calibrate(String[] args) throws Exception {
        Map<Integer, String> nodes = parseNodeMap(args[1]);
        int n = Integer.parseInt(args[2]);
        int durationSec = Integer.parseInt(args[3]);
        int concurrency = Integer.parseInt(args[4]);
        int valueBytes = args.length > 5 ? Integer.parseInt(args[5]) : 256;
        byte[] value = makeValue(valueBytes);

        HttpClient client = newClient();
        AtomicReferenceArray<String> shardLeader = resolveShardLeaders(client, nodes, n, value);

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        AtomicLong ok = new AtomicLong(), nonOk = new AtomicLong(), retargets = new AtomicLong(), seq = new AtomicLong();
        ConcurrentHashMap<Integer, AtomicInteger> status = new ConcurrentHashMap<>();
        long start = System.nanoTime();
        long warmEnd = start + 3_000_000_000L;          // 3s warmup not counted
        long deadline = start + durationSec * 1_000_000_000L;
        Runnable worker = () -> {
            while (System.nanoTime() < deadline) {
                long i = seq.getAndIncrement();
                String k = key(i);
                int s = shardFor(k, n);
                boolean counted = System.nanoTime() >= warmEnd;
                try {
                    HttpResponse<Void> r = client.send(put(shardLeader.get(s), k, value), HttpResponse.BodyHandlers.discarding());
                    if (counted) status.computeIfAbsent(r.statusCode(), x -> new AtomicInteger()).incrementAndGet();
                    if (r.statusCode() == 200) { if (counted) ok.incrementAndGet(); }
                    else { if (counted) nonOk.incrementAndGet(); followHint(r, nodes, shardLeader, s, retargets); }
                } catch (Exception e) {
                    if (counted) nonOk.incrementAndGet();
                }
            }
        };
        for (int w = 0; w < concurrency; w++) pool.submit(worker);
        pool.shutdown();
        pool.awaitTermination(durationSec + 60L, TimeUnit.SECONDS);
        double measureSec = (deadline - warmEnd) / 1e9;
        double rate = ok.get() / measureSec;
        System.out.printf("CALIBRATE-RESULT shards=%d concurrency=%d measureSec=%.1f committed_200=%d non200=%d retargets=%d sustained_commit_rate_per_sec=%.0f%n",
                n, concurrency, measureSec, ok.get(), nonOk.get(), retargets.get(), rate);
        System.out.printf("CALIBRATE-STATUS %s%n", statusString(status));
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Per-shard leader URLs, learned by probing one representative key per shard (follow hints). */
    private static AtomicReferenceArray<String> resolveShardLeaders(HttpClient client, Map<Integer, String> nodes, int n, byte[] value) {
        String firstUrl = nodes.values().iterator().next();
        AtomicReferenceArray<String> shardLeader = new AtomicReferenceArray<>(n);
        for (int s = 0; s < n; s++) shardLeader.set(s, firstUrl);
        String[] probeKey = new String[n];
        int found = 0;
        for (long i = 0; found < n && i < 50_000_000L; i++) {
            String k = key(i);
            int s = shardFor(k, n);
            if (probeKey[s] == null) { probeKey[s] = k; found++; }
        }
        long warmDeadline = System.nanoTime() + 30_000_000_000L;
        for (int s = 0; s < n; s++) {
            while (System.nanoTime() < warmDeadline) {
                if (sendOnce(client, shardLeader, s, probeKey[s], value, nodes)) break;
            }
        }
        return shardLeader;
    }

    /** One synchronous PUT for warmup; updates the shard leader on a hint; returns true on 200. */
    private static boolean sendOnce(HttpClient client, AtomicReferenceArray<String> shardLeader, int shard,
                                    String k, byte[] value, Map<Integer, String> nodes) {
        try {
            HttpResponse<Void> r = client.send(put(shardLeader.get(shard), k, value), HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() == 200) return true;
            followHint(r, nodes, shardLeader, shard, new AtomicLong());
        } catch (Exception ignored) {}
        return false;
    }

    private static Map<Integer, String> parseNodeMap(String spec) {
        Map<Integer, String> m = new HashMap<>();
        for (String part : spec.split(",")) {
            int eq = part.indexOf('=');
            m.put(Integer.parseInt(part.substring(0, eq).trim()), part.substring(eq + 1).trim());
        }
        return m;
    }

    private static String key(long i) {
        return "perf/nxk/" + (i & 0xFFFFF);
    }

    /** Exact replica of StaticShardMap.shardFor for ConfigScope.GLOBAL (ordinal 0). */
    private static int shardFor(String key, int n) {
        long h = FNV_OFFSET_BASIS;
        h ^= SCOPE_ORDINAL;
        h *= FNV_PRIME;
        for (int i = 0, len = key.length(); i < len; i++) {
            h ^= key.charAt(i);
            h *= FNV_PRIME;
        }
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return Math.floorMod(h, n);
    }

    private static HttpRequest put(String baseUrl, String key, byte[] value) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/config/" + key))
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(value))
                .build();
    }

    private static void followHint(HttpResponse<?> r, Map<Integer, String> nodes,
                                   AtomicReferenceArray<String> shardLeader, int shard, AtomicLong retargets) {
        var hint = r.headers().firstValue("X-Leader-Hint");
        if (hint.isPresent()) {
            try {
                String url = nodes.get(Integer.parseInt(hint.get()));
                if (url != null && !url.equals(shardLeader.get(shard))) {
                    shardLeader.set(shard, url);
                    retargets.incrementAndGet();
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    private static void record(Histogram h, Object lock, long latencyUs) {
        long v = Math.min(Math.max(latencyUs, 1), 120_000_000L);
        synchronized (lock) { h.recordValue(v); }
    }

    private static String statusString(Map<Integer, AtomicInteger> status) {
        StringBuilder sb = new StringBuilder();
        status.keySet().stream().sorted().forEach(c -> sb.append(c).append('=').append(status.get(c).get()).append(' '));
        return sb.toString().trim();
    }

    private static byte[] makeValue(int n) {
        byte[] v = new byte[n];
        for (int i = 0; i < n; i++) v[i] = (byte) (i * 17 + 3);
        return v;
    }
}
