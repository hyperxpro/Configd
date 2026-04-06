package io.configd.observability;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Lightweight in-process metrics registry. Tracks counters, gauges, and
 * histograms without any external dependencies.
 * <p>
 * Thread-safe via {@link ConcurrentHashMap} and lock-free atomic primitives
 * ({@link LongAdder}, {@link AtomicLong}). Designed to be replaced with
 * Micrometer in production deployments — the API surface is deliberately
 * minimal to make migration straightforward.
 * <p>
 * <b>Counter:</b> monotonically increasing count backed by {@link LongAdder}
 * for high-throughput concurrent increments.
 * <p>
 * <b>Gauge:</b> instantaneous value read from a caller-supplied
 * {@link LongSupplier} at snapshot time.
 * <p>
 * <b>Histogram:</b> records {@code long} values into a fixed-size ring buffer.
 * Supports count, min, max, mean, and arbitrary percentile queries (p50, p99,
 * p999). The ring buffer provides an approximate sliding window — old values
 * are overwritten as new values arrive. This is intentionally simple; for
 * production use, swap in Micrometer's {@code DistributionSummary}.
 *
 * @see MetricsSnapshot
 */
public final class MetricsRegistry {

    /** Default ring buffer capacity for histograms. */
    private static final int DEFAULT_HISTOGRAM_CAPACITY = 4096;

    private final ConcurrentHashMap<String, DefaultCounter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GaugeRegistration> gauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DefaultHistogram> histograms = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Counter
    // -----------------------------------------------------------------------

    /**
     * A monotonically increasing counter.
     */
    public interface Counter {
        /** Increments the counter by 1. */
        void increment();

        /** Increments the counter by the given amount. */
        void increment(long n);

        /** Returns the current count. */
        long get();
    }

    /**
     * Returns or creates a counter with the given name.
     * <p>
     * Repeated calls with the same name return the same counter instance.
     *
     * @param name the metric name (non-null, non-blank)
     * @return the counter
     */
    public Counter counter(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return counters.computeIfAbsent(name, k -> new DefaultCounter());
    }

    // -----------------------------------------------------------------------
    // Gauge
    // -----------------------------------------------------------------------

    /**
     * Registers a gauge with the given name and value supplier.
     * <p>
     * If a gauge with the same name already exists, it is replaced.
     * The supplier is invoked at snapshot time — it must be thread-safe
     * and should not block.
     *
     * @param name     the metric name (non-null, non-blank)
     * @param supplier the value supplier (non-null)
     */
    public void gauge(String name, LongSupplier supplier) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(supplier, "supplier must not be null");
        gauges.put(name, new GaugeRegistration(supplier));
    }

    // -----------------------------------------------------------------------
    // Histogram
    // -----------------------------------------------------------------------

    /**
     * A distribution of {@code long} values with percentile support.
     */
    public interface Histogram {
        /** Records a value. */
        void record(long value);

        /** Returns the total number of recorded values. */
        long count();

        /** Returns the minimum recorded value, or 0 if empty. */
        long min();

        /** Returns the maximum recorded value, or 0 if empty. */
        long max();

        /** Returns the arithmetic mean, or 0.0 if empty. */
        double mean();

        /**
         * Returns the value at the given percentile (0.0 to 1.0).
         * <p>
         * Uses nearest-rank interpolation on the current ring buffer contents.
         * For example, {@code percentile(0.99)} returns the p99 value.
         *
         * @param p percentile in [0.0, 1.0]
         * @return the value at the given percentile, or 0 if empty
         */
        long percentile(double p);

        /**
         * F5 (Tier-1-METRIC-DRIFT) — returns the cumulative count of samples
         * that fell at or below each cutoff in the supplied array. Used by
         * {@link PrometheusExporter} to emit {@code _bucket{le="X"}} lines
         * matching the SLO alert vocabulary. The returned array has the same
         * length as {@code cutoffs}; element {@code i} is the number of
         * recorded samples with {@code value <= cutoffs[i]}.
         *
         * <p>Counts are taken from the ring-buffer window (same window used
         * for percentile computation) and are therefore approximate when the
         * total recorded count exceeds the buffer capacity — this matches the
         * documented behavior of {@link #percentile(double)}.
         *
         * @param cutoffs strictly-increasing cutoffs in sample units
         * @return per-cutoff cumulative sample counts
         */
        long[] bucketCounts(long[] cutoffs);
    }

    /**
     * Returns or creates a histogram with the given name.
     * <p>
     * Repeated calls with the same name return the same histogram instance.
     *
     * @param name the metric name (non-null, non-blank)
     * @return the histogram
     */
    public Histogram histogram(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return histograms.computeIfAbsent(name, k -> new DefaultHistogram(DEFAULT_HISTOGRAM_CAPACITY));
    }

    // -----------------------------------------------------------------------
    // Snapshot
    // -----------------------------------------------------------------------

    /**
     * A point-in-time snapshot of a single metric's value.
     *
     * @param name  the metric name
     * @param type  the metric type ("counter", "gauge", "histogram")
     * @param value the numeric value (count for counters/gauges, count for histograms)
     */
    public record MetricValue(String name, String type, long value) {
        public MetricValue {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(type, "type must not be null");
        }
    }

    /**
     * An immutable snapshot of all metrics at a point in time.
     *
     * @param metrics unmodifiable map of metric name to metric value
     */
    public record MetricsSnapshot(Map<String, MetricValue> metrics) {
        public MetricsSnapshot {
            Objects.requireNonNull(metrics, "metrics must not be null");
            metrics = Collections.unmodifiableMap(metrics);
        }
    }

    /**
     * Takes a point-in-time snapshot of all registered metrics.
     *
     * @return metrics snapshot
     */
    public MetricsSnapshot snapshot() {
        var result = new java.util.LinkedHashMap<String, MetricValue>();

        counters.forEach((name, counter) ->
                result.put(name, new MetricValue(name, "counter", counter.get())));

        gauges.forEach((name, reg) ->
                result.put(name, new MetricValue(name, "gauge", reg.supplier().getAsLong())));

        histograms.forEach((name, histogram) ->
                result.put(name, new MetricValue(name, "histogram", histogram.count())));

        return new MetricsSnapshot(result);
    }

    // -----------------------------------------------------------------------
    // Internal implementations
    // -----------------------------------------------------------------------

    /**
     * Lock-free counter backed by {@link LongAdder} for high-throughput
     * concurrent increments.
     */
    private static final class DefaultCounter implements Counter {

        private final LongAdder adder = new LongAdder();

        @Override
        public void increment() {
            adder.increment();
        }

        @Override
        public void increment(long n) {
            if (n < 0) {
                throw new IllegalArgumentException("increment amount must be non-negative: " + n);
            }
            adder.add(n);
        }

        @Override
        public long get() {
            return adder.sum();
        }
    }

    private record GaugeRegistration(LongSupplier supplier) {
        GaugeRegistration {
            Objects.requireNonNull(supplier, "supplier must not be null");
        }
    }

    /**
     * Ring-buffer-based histogram. Thread-safe via synchronized write and
     * volatile/snapshot reads. The ring buffer provides an approximate
     * sliding window of the most recent {@code capacity} values.
     * <p>
     * Percentile computation copies the current buffer contents and sorts
     * them — this is O(n log n) but only happens on explicit query, not on
     * the recording hot path.
     */
    private static final class DefaultHistogram implements Histogram {

        private final long[] buffer;
        private final int capacity;

        /** Total number of values ever recorded. */
        private final AtomicLong totalCount = new AtomicLong(0);

        /** Tracks min/max across all time (not just the ring buffer window). */
        private volatile long minValue = Long.MAX_VALUE;
        private volatile long maxValue = Long.MIN_VALUE;

        /** Sum of all values ever recorded, for mean computation. */
        private final LongAdder sum = new LongAdder();

        /** Write cursor into the ring buffer (monotonically increasing). */
        private final AtomicLong cursor = new AtomicLong(0);

        DefaultHistogram(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive: " + capacity);
            }
            this.capacity = capacity;
            this.buffer = new long[capacity];
        }

        @Override
        public void record(long value) {
            long idx = cursor.getAndIncrement();
            buffer[(int) (idx % capacity)] = value;
            totalCount.incrementAndGet();
            sum.add(value);

            // Update min/max with simple spin — acceptable for monitoring
            updateMin(value);
            updateMax(value);
        }

        private void updateMin(long value) {
            long current;
            do {
                current = minValue;
                if (value >= current) return;
            } while (!compareAndSetMin(current, value));
        }

        private void updateMax(long value) {
            long current;
            do {
                current = maxValue;
                if (value <= current) return;
            } while (!compareAndSetMax(current, value));
        }

        /**
         * CAS-like update for volatile min. Uses a synchronized block
         * since VarHandle on a plain field is more ceremony than warranted
         * for monitoring code. The synchronized block is only contended
         * when a new min is discovered, which is rare after warmup.
         */
        private synchronized boolean compareAndSetMin(long expected, long update) {
            if (minValue == expected) {
                minValue = update;
                return true;
            }
            return false;
        }

        private synchronized boolean compareAndSetMax(long expected, long update) {
            if (maxValue == expected) {
                maxValue = update;
                return true;
            }
            return false;
        }

        @Override
        public long count() {
            return totalCount.get();
        }

        @Override
        public long min() {
            long c = totalCount.get();
            return (c == 0) ? 0 : minValue;
        }

        @Override
        public long max() {
            long c = totalCount.get();
            return (c == 0) ? 0 : maxValue;
        }

        @Override
        public double mean() {
            long c = totalCount.get();
            return (c == 0) ? 0.0 : (double) sum.sum() / c;
        }

        @Override
        public long percentile(double p) {
            if (p < 0.0 || p > 1.0) {
                throw new IllegalArgumentException("percentile must be in [0.0, 1.0]: " + p);
            }
            long c = totalCount.get();
            if (c == 0) {
                return 0;
            }

            // Snapshot the ring buffer
            int n = (int) Math.min(c, capacity);
            long[] snapshot = new long[n];
            long cursorVal = cursor.get();

            if (c <= capacity) {
                // Buffer is not yet full — copy from start
                System.arraycopy(buffer, 0, snapshot, 0, n);
            } else {
                // Buffer has wrapped — copy from current write position
                int start = (int) (cursorVal % capacity);
                int tailLen = capacity - start;
                System.arraycopy(buffer, start, snapshot, 0, tailLen);
                System.arraycopy(buffer, 0, snapshot, tailLen, start);
            }

            Arrays.sort(snapshot);

            // Nearest-rank method
            int rank = (int) Math.ceil(p * n) - 1;
            if (rank < 0) rank = 0;
            if (rank >= n) rank = n - 1;
            return snapshot[rank];
        }

        @Override
        public long[] bucketCounts(long[] cutoffs) {
            Objects.requireNonNull(cutoffs, "cutoffs must not be null");
            long[] result = new long[cutoffs.length];
            long c = totalCount.get();
            if (c == 0 || cutoffs.length == 0) {
                return result;
            }

            int n = (int) Math.min(c, capacity);
            long[] snapshot = new long[n];
            long cursorVal = cursor.get();
            if (c <= capacity) {
                System.arraycopy(buffer, 0, snapshot, 0, n);
            } else {
                int start = (int) (cursorVal % capacity);
                int tailLen = capacity - start;
                System.arraycopy(buffer, start, snapshot, 0, tailLen);
                System.arraycopy(buffer, 0, snapshot, tailLen, start);
            }
            Arrays.sort(snapshot);

            // For each cutoff, binary-search the upper bound and record the
            // count of samples <= cutoff. Cutoffs are strictly increasing so
            // we can sweep the cursor forward once.
            int idx = 0;
            for (int i = 0; i < cutoffs.length; i++) {
                long cutoff = cutoffs[i];
                while (idx < n && snapshot[idx] <= cutoff) {
                    idx++;
                }
                result[i] = idx;
            }
            return result;
        }
    }
}
