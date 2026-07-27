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
 * Micrometer in production deployments - the API surface is deliberately
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
 * p999). The ring buffer provides an approximate sliding window - old values
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
    private final ConcurrentHashMap<String, InfoGaugeRegistration> infoGauges = new ConcurrentHashMap<>();

    public interface Counter {
        void increment();
        void increment(long n);
        long get();
    }

    public Counter counter(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return counters.computeIfAbsent(name, k -> new DefaultCounter());
    }

    /** Supplier is invoked at snapshot time; must be thread-safe and non-blocking. */
    public void gauge(String name, LongSupplier supplier) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(supplier, "supplier must not be null");
        gauges.put(name, new GaugeRegistration(supplier));
    }

    /**
     * Registers an <em>info gauge</em> - a metric whose payload is a single string carried in a label,
     * rendered by {@link PrometheusExporter} as {@code name{labelName="value"} 1} (the Prometheus
     * convention for exporting a string value, e.g. a build sha or a state digest). The supplier is
     * called at scrape time; only the CURRENT value is ever emitted (the registration is keyed by
     * {@code name} and replaced on re-registration), so a value that changes over time yields one series,
     * not a new series per distinct value - no label cardinality growth.
     *
     * @param name          the metric name (non-null, non-blank)
     * @param labelName     the label the string value is carried in (non-null, non-blank)
     * @param valueSupplier supplies the current string value at scrape time (non-null); must be
     *                      thread-safe and should not block
     */
    public void infoGauge(String name, String labelName, java.util.function.Supplier<String> valueSupplier) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(labelName, "labelName must not be null");
        if (labelName.isBlank()) {
            throw new IllegalArgumentException("labelName must not be blank");
        }
        Objects.requireNonNull(valueSupplier, "valueSupplier must not be null");
        infoGauges.put(name, new InfoGaugeRegistration(labelName, valueSupplier));
    }

    public record InfoSample(String name, String labelName, String value) {}

    public java.util.List<InfoSample> infoGaugeSamples() {
        var out = new java.util.ArrayList<InfoSample>(infoGauges.size());
        infoGauges.forEach((name, reg) -> out.add(new InfoSample(name, reg.labelName(), reg.supplier().get())));
        return out;
    }

    public interface Histogram {
        void record(long value);
        long count();
        long min();
        long max();
        double mean();
        long percentile(double p);
        long[] bucketCounts(long[] cutoffs);
    }

    public Histogram histogram(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return histograms.computeIfAbsent(name, k -> new DefaultHistogram(DEFAULT_HISTOGRAM_CAPACITY));
    }

    public record MetricValue(String name, String type, long value) {
        public MetricValue {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(type, "type must not be null");
        }
    }

    public record MetricsSnapshot(Map<String, MetricValue> metrics) {
        public MetricsSnapshot {
            Objects.requireNonNull(metrics, "metrics must not be null");
            metrics = Collections.unmodifiableMap(metrics);
        }
    }

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

    private record InfoGaugeRegistration(String labelName, java.util.function.Supplier<String> supplier) {
        InfoGaugeRegistration {
            Objects.requireNonNull(labelName, "labelName must not be null");
            Objects.requireNonNull(supplier, "supplier must not be null");
        }
    }

    private static final class DefaultHistogram implements Histogram {

        private final long[] buffer;
        private final int capacity;
        private final AtomicLong totalCount = new AtomicLong(0);
        private volatile long minValue = Long.MAX_VALUE;
        private volatile long maxValue = Long.MIN_VALUE;
        private final LongAdder sum = new LongAdder();
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
