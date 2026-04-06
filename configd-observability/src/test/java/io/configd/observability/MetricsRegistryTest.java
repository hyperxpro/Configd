package io.configd.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MetricsRegistry} — counters, gauges, and histograms.
 */
class MetricsRegistryTest {

    private MetricsRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MetricsRegistry();
    }

    // -----------------------------------------------------------------------
    // Counter
    // -----------------------------------------------------------------------

    @Nested
    class CounterTests {

        @Test
        void initialCountIsZero() {
            MetricsRegistry.Counter counter = registry.counter("requests");
            assertEquals(0, counter.get());
        }

        @Test
        void incrementByOne() {
            MetricsRegistry.Counter counter = registry.counter("requests");
            counter.increment();
            assertEquals(1, counter.get());
        }

        @Test
        void incrementByN() {
            MetricsRegistry.Counter counter = registry.counter("requests");
            counter.increment(10);
            assertEquals(10, counter.get());
        }

        @Test
        void multipleIncrements() {
            MetricsRegistry.Counter counter = registry.counter("requests");
            counter.increment();
            counter.increment(5);
            counter.increment();
            assertEquals(7, counter.get());
        }

        @Test
        void sameNameReturnsSameInstance() {
            MetricsRegistry.Counter c1 = registry.counter("requests");
            MetricsRegistry.Counter c2 = registry.counter("requests");
            assertSame(c1, c2);
        }

        @Test
        void differentNamesReturnDifferentInstances() {
            MetricsRegistry.Counter c1 = registry.counter("requests");
            MetricsRegistry.Counter c2 = registry.counter("errors");
            assertNotSame(c1, c2);
        }

        @Test
        void negativeIncrementThrows() {
            MetricsRegistry.Counter counter = registry.counter("requests");
            assertThrows(IllegalArgumentException.class, () -> counter.increment(-1));
        }

        @Test
        void concurrentIncrements() throws InterruptedException {
            MetricsRegistry.Counter counter = registry.counter("concurrent");
            int threadCount = 8;
            int incrementsPerThread = 10_000;
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < incrementsPerThread; i++) {
                        counter.increment();
                    }
                    latch.countDown();
                });
            }
            latch.await();

            assertEquals((long) threadCount * incrementsPerThread, counter.get());
        }
    }

    // -----------------------------------------------------------------------
    // Gauge
    // -----------------------------------------------------------------------

    @Nested
    class GaugeTests {

        @Test
        void gaugeReadsFromSupplier() {
            AtomicLong value = new AtomicLong(42);
            registry.gauge("pool.size", value::get);

            MetricsRegistry.MetricsSnapshot snapshot = registry.snapshot();
            assertEquals(42, snapshot.metrics().get("pool.size").value());
        }

        @Test
        void gaugeReflectsCurrentValue() {
            AtomicLong value = new AtomicLong(10);
            registry.gauge("active", value::get);

            assertEquals(10, registry.snapshot().metrics().get("active").value());

            value.set(50);
            assertEquals(50, registry.snapshot().metrics().get("active").value());
        }

        @Test
        void gaugeReplacesExisting() {
            AtomicLong v1 = new AtomicLong(1);
            AtomicLong v2 = new AtomicLong(2);
            registry.gauge("metric", v1::get);
            registry.gauge("metric", v2::get);

            assertEquals(2, registry.snapshot().metrics().get("metric").value());
        }
    }

    // -----------------------------------------------------------------------
    // Histogram
    // -----------------------------------------------------------------------

    @Nested
    class HistogramTests {

        @Test
        void emptyHistogram() {
            MetricsRegistry.Histogram hist = registry.histogram("latency");
            assertEquals(0, hist.count());
            assertEquals(0, hist.min());
            assertEquals(0, hist.max());
            assertEquals(0.0, hist.mean());
            assertEquals(0, hist.percentile(0.5));
        }

        @Test
        void singleValue() {
            MetricsRegistry.Histogram hist = registry.histogram("latency");
            hist.record(100);

            assertEquals(1, hist.count());
            assertEquals(100, hist.min());
            assertEquals(100, hist.max());
            assertEquals(100.0, hist.mean());
            assertEquals(100, hist.percentile(0.5));
            assertEquals(100, hist.percentile(0.99));
        }

        @Test
        void multipleValues() {
            MetricsRegistry.Histogram hist = registry.histogram("latency");
            for (int i = 1; i <= 100; i++) {
                hist.record(i);
            }

            assertEquals(100, hist.count());
            assertEquals(1, hist.min());
            assertEquals(100, hist.max());
            assertEquals(50.5, hist.mean(), 0.01);
        }

        @Test
        void p50Percentile() {
            MetricsRegistry.Histogram hist = registry.histogram("latency");
            for (int i = 1; i <= 100; i++) {
                hist.record(i);
            }

            long p50 = hist.percentile(0.5);
            assertEquals(50, p50);
        }

        @Test
        void p99Percentile() {
            MetricsRegistry.Histogram hist = registry.histogram("latency");
            for (int i = 1; i <= 1000; i++) {
                hist.record(i);
            }

            long p99 = hist.percentile(0.99);
            assertEquals(990, p99);
        }

        @Test
        void p999Percentile() {
            MetricsRegistry.Histogram hist = registry.histogram("latency");
            for (int i = 1; i <= 1000; i++) {
                hist.record(i);
            }

            long p999 = hist.percentile(0.999);
            assertEquals(999, p999);
        }

        @Test
        void p0ReturnsMin() {
            MetricsRegistry.Histogram hist = registry.histogram("latency");
            for (int i = 1; i <= 100; i++) {
                hist.record(i);
            }
            assertEquals(1, hist.percentile(0.0));
        }

        @Test
        void p100ReturnsMax() {
            MetricsRegistry.Histogram hist = registry.histogram("latency");
            for (int i = 1; i <= 100; i++) {
                hist.record(i);
            }
            assertEquals(100, hist.percentile(1.0));
        }

        @Test
        void invalidPercentileThrows() {
            MetricsRegistry.Histogram hist = registry.histogram("latency");
            hist.record(1);
            assertThrows(IllegalArgumentException.class, () -> hist.percentile(-0.1));
            assertThrows(IllegalArgumentException.class, () -> hist.percentile(1.1));
        }

        @Test
        void sameNameReturnsSameInstance() {
            MetricsRegistry.Histogram h1 = registry.histogram("latency");
            MetricsRegistry.Histogram h2 = registry.histogram("latency");
            assertSame(h1, h2);
        }
    }

    // -----------------------------------------------------------------------
    // Snapshot
    // -----------------------------------------------------------------------

    @Nested
    class SnapshotTests {

        @Test
        void snapshotContainsAllMetrics() {
            registry.counter("requests").increment(42);
            registry.gauge("pool.size", () -> 10);
            registry.histogram("latency").record(100);

            MetricsRegistry.MetricsSnapshot snapshot = registry.snapshot();
            assertEquals(3, snapshot.metrics().size());

            assertEquals("counter", snapshot.metrics().get("requests").type());
            assertEquals(42, snapshot.metrics().get("requests").value());

            assertEquals("gauge", snapshot.metrics().get("pool.size").type());
            assertEquals(10, snapshot.metrics().get("pool.size").value());

            assertEquals("histogram", snapshot.metrics().get("latency").type());
            assertEquals(1, snapshot.metrics().get("latency").value()); // count
        }

        @Test
        void emptySnapshot() {
            MetricsRegistry.MetricsSnapshot snapshot = registry.snapshot();
            assertTrue(snapshot.metrics().isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    @Nested
    class Validation {

        @Test
        void nullCounterNameThrows() {
            assertThrows(NullPointerException.class, () -> registry.counter(null));
        }

        @Test
        void blankCounterNameThrows() {
            assertThrows(IllegalArgumentException.class, () -> registry.counter("  "));
        }

        @Test
        void nullGaugeNameThrows() {
            assertThrows(NullPointerException.class, () -> registry.gauge(null, () -> 0));
        }

        @Test
        void nullGaugeSupplierThrows() {
            assertThrows(NullPointerException.class, () -> registry.gauge("name", null));
        }

        @Test
        void nullHistogramNameThrows() {
            assertThrows(NullPointerException.class, () -> registry.histogram(null));
        }

        @Test
        void blankHistogramNameThrows() {
            assertThrows(IllegalArgumentException.class, () -> registry.histogram("  "));
        }
    }
}
