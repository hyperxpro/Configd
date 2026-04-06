package io.configd.bench;

import io.configd.observability.MetricsRegistry;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks {@link MetricsRegistry.Histogram#record(long)} on the
 * production {@code DefaultHistogram} (ring buffer + LongAdder sum +
 * synchronized min/max CAS).
 *
 * <p>Gap-closure O5 (PA-5008/9/10) targets this class for a lock-free
 * rewrite. The histogram is on the recording hot path of every metric in
 * the system — its tail latency directly bounds how fine-grained the
 * observability signal can be without backpressuring the workload.
 *
 * <p>Two scenarios:
 * <ul>
 *   <li><b>recordSingleThreaded</b> — uncontended baseline. The current
 *       implementation should be cheap here; the synchronized min/max CAS
 *       only pays cost on actual updates.</li>
 *   <li><b>recordContended</b> — many threads racing on the same
 *       histogram. Surfaces the synchronized-block scaling cliff that O5
 *       is meant to remove.</li>
 * </ul>
 *
 * <p>Run with {@code -prof gc} to verify the recording path is
 * allocation-free on the steady-state path.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1)
public class HistogramBenchmark {

    @State(Scope.Benchmark)
    public static class SharedHistogram {
        MetricsRegistry registry;
        MetricsRegistry.Histogram histogram;

        @Setup(Level.Trial)
        public void setUp() {
            registry = new MetricsRegistry();
            histogram = registry.histogram("bench.hot");
        }
    }

    @State(Scope.Thread)
    public static class PerThreadValues {
        long[] values;
        int cursor;

        @Setup(Level.Trial)
        public void setUp() {
            values = new long[8192];
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            for (int i = 0; i < values.length; i++) {
                values[i] = rng.nextLong(1, 1_000_000);
            }
            cursor = 0;
        }
    }

    /** Single-thread baseline: pure record path, no contention. */
    @Benchmark
    @Threads(1)
    public void recordSingleThreaded(SharedHistogram h, PerThreadValues v) {
        h.histogram.record(v.values[v.cursor++ & 8191]);
    }

    /**
     * Contended record path. {@code @Threads(8)} forces JMH to spawn
     * eight worker threads racing on the same shared histogram — this is
     * the workload that should expose the synchronized min/max CAS cost
     * on multi-socket machines.
     */
    @Benchmark
    @Threads(8)
    public void recordContended(SharedHistogram h, PerThreadValues v) {
        h.histogram.record(v.values[v.cursor++ & 8191]);
    }

    /**
     * Percentile read path. Off the critical path (called by exporters,
     * not on every metric record), but bounded so a slow exporter cannot
     * starve recorders.
     */
    @Benchmark
    @Threads(1)
    public void percentileP99(SharedHistogram h, Blackhole bh) {
        bh.consume(h.histogram.percentile(0.99));
    }
}
