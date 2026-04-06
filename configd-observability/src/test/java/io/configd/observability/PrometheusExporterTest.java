package io.configd.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrometheusExporterTest {

    @Test
    void exportCounters() {
        MetricsRegistry registry = new MetricsRegistry();
        registry.counter("requests_total").increment(42);

        PrometheusExporter exporter = new PrometheusExporter(registry);
        String output = exporter.export();

        assertTrue(output.contains("# TYPE requests_total_total counter"));
        assertTrue(output.contains("requests_total_total 42"));
    }

    @Test
    void exportGauges() {
        MetricsRegistry registry = new MetricsRegistry();
        registry.gauge("active_connections", () -> 7);

        PrometheusExporter exporter = new PrometheusExporter(registry);
        String output = exporter.export();

        assertTrue(output.contains("# TYPE active_connections gauge"));
        assertTrue(output.contains("active_connections 7"));
    }

    @Test
    void exportHistograms() {
        MetricsRegistry registry = new MetricsRegistry();
        MetricsRegistry.Histogram hist = registry.histogram("latency_ns");
        for (int i = 1; i <= 100; i++) {
            hist.record(i * 1000L);
        }

        PrometheusExporter exporter = new PrometheusExporter(registry);
        String output = exporter.export();

        assertTrue(output.contains("# TYPE latency_ns summary"));
        assertTrue(output.contains("latency_ns_count 100"));
        assertTrue(output.contains("quantile=\"0.5\""));
        assertTrue(output.contains("quantile=\"0.99\""));
    }

    @Test
    void sanitizesMetricNames() {
        MetricsRegistry registry = new MetricsRegistry();
        registry.counter("configd.raft.commit-count").increment(5);

        PrometheusExporter exporter = new PrometheusExporter(registry);
        String output = exporter.export();

        assertTrue(output.contains("configd_raft_commit_count_total 5"));
    }

    @Test
    void emptyRegistryProducesEmptyOutput() {
        MetricsRegistry registry = new MetricsRegistry();
        PrometheusExporter exporter = new PrometheusExporter(registry);

        assertEquals("", exporter.export());
    }

    @Test
    void nullRegistryThrows() {
        assertThrows(NullPointerException.class, () -> new PrometheusExporter(null));
    }
}
