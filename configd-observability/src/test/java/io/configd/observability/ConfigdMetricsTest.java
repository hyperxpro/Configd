package io.configd.observability;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F5 (Tier-1-METRIC-DRIFT) — verifies that every metric referenced by
 * {@code ops/alerts/configd-slo-alerts.yaml} is registered with the
 * correct type after {@link ConfigdMetrics} construction, and that the
 * Prometheus exposition emits the {@code _bucket{le="X"}} labels that
 * the alert expressions query.
 *
 * <p>Per the F5 prompt: tests use the real {@link MetricsRegistry}
 * instance (no mocks) per the codebase's testing convention.
 */
class ConfigdMetricsTest {

    @Test
    void registersAllSloCitedMetrics() {
        MetricsRegistry registry = new MetricsRegistry();
        AtomicLong pendingApply = new AtomicLong(7);
        new ConfigdMetrics(registry, pendingApply::get);

        // Snapshot the registry and ensure every alert-cited name is present.
        var snapshot = registry.snapshot().metrics();
        assertEquals("counter",
                snapshot.get(ConfigdMetrics.NAME_WRITE_COMMIT_TOTAL).type());
        assertEquals("counter",
                snapshot.get(ConfigdMetrics.NAME_WRITE_COMMIT_FAILED).type());
        assertEquals("histogram",
                snapshot.get(ConfigdMetrics.NAME_WRITE_COMMIT_SECONDS).type());
        assertEquals("histogram",
                snapshot.get(ConfigdMetrics.NAME_APPLY_SECONDS).type());
        assertEquals("counter",
                snapshot.get(ConfigdMetrics.NAME_EDGE_READ_TOTAL).type());
        assertEquals("histogram",
                snapshot.get(ConfigdMetrics.NAME_EDGE_READ_SECONDS).type());
        assertEquals("histogram",
                snapshot.get(ConfigdMetrics.NAME_PROPAGATION_DELAY_SECONDS).type());
        assertEquals("counter",
                snapshot.get(ConfigdMetrics.NAME_SNAPSHOT_INSTALL_FAILED).type());
        assertEquals("counter",
                snapshot.get(ConfigdMetrics.NAME_SNAPSHOT_REBUILD).type());
        assertEquals("gauge",
                snapshot.get(ConfigdMetrics.NAME_RAFT_PENDING_APPLY).type());
        assertEquals(7L,
                snapshot.get(ConfigdMetrics.NAME_RAFT_PENDING_APPLY).value());
    }

    @Test
    void gaugeBindingIsLateBindable() {
        MetricsRegistry registry = new MetricsRegistry();
        ConfigdMetrics metrics = new ConfigdMetrics(registry, null);
        // Pre-binding: the gauge has not been registered.
        assertNull(registry.snapshot().metrics().get(ConfigdMetrics.NAME_RAFT_PENDING_APPLY));

        AtomicLong pending = new AtomicLong(42);
        metrics.bindRaftPendingApplyGauge(pending::get);
        assertEquals(42L,
                registry.snapshot().metrics().get(ConfigdMetrics.NAME_RAFT_PENDING_APPLY).value());

        pending.set(99);
        assertEquals(99L,
                registry.snapshot().metrics().get(ConfigdMetrics.NAME_RAFT_PENDING_APPLY).value());
    }

    @Test
    void prometheusOutputContainsAlertQueriedSeries() {
        MetricsRegistry registry = new MetricsRegistry();
        ConfigdMetrics metrics = new ConfigdMetrics(registry, () -> 0L);

        // Drive each metric so the exporter has values to render. Record
        // a sample at 100ms (under the 150ms write-commit budget) so the
        // alert query against le="0.150" sees a non-zero count.
        metrics.writeCommitTotal().increment();
        metrics.writeCommitSeconds().record(100_000_000L); // 100 ms in ns
        metrics.edgeReadTotal().increment();
        metrics.edgeReadSeconds().record(500_000L);        // 0.5 ms in ns
        metrics.propagationDelaySeconds().record(50_000_000L); // 50 ms in ns

        PrometheusExporter exporter = new PrometheusExporter(
                registry, ConfigdMetrics.histogramSchedules());
        String text = exporter.export();

        // The alert query is:
        //   configd_write_commit_seconds_bucket{le="0.150"}
        // So that exact line MUST appear in the exposition.
        assertTrue(text.contains("configd_write_commit_seconds_bucket{le=\"0.150\"}"),
                "missing le=\"0.150\" bucket on write-commit histogram\n" + text);
        assertTrue(text.contains("configd_edge_read_seconds_bucket{le=\"0.001\"}"),
                "missing le=\"0.001\" bucket on edge-read histogram\n" + text);
        assertTrue(text.contains("configd_edge_read_seconds_bucket{le=\"0.005\"}"),
                "missing le=\"0.005\" bucket on edge-read histogram\n" + text);
        assertTrue(text.contains("configd_propagation_delay_seconds_bucket{le=\"0.500\"}"),
                "missing le=\"0.500\" bucket on propagation-delay histogram\n" + text);

        // Counter alert series:
        //   configd_write_commit_total
        //   configd_write_commit_failed_total
        //   configd_snapshot_install_failed_total
        assertTrue(text.contains("configd_write_commit_total 1"),
                "missing write_commit_total counter\n" + text);
        assertTrue(text.contains("configd_write_commit_failed_total 0"),
                "missing write_commit_failed_total counter\n" + text);
        assertTrue(text.contains("configd_snapshot_install_failed_total 0"),
                "missing snapshot_install_failed_total counter\n" + text);

        // Gauge series queried by ConfigdRaftPipelineSaturation:
        //   configd_raft_pending_apply_entries
        assertTrue(text.contains("configd_raft_pending_apply_entries 0"),
                "missing raft_pending_apply_entries gauge\n" + text);

        // Histogram count and bucket counts must reflect the recorded sample:
        //   100 ms < 150 ms → le="0.150" bucket count = 1
        assertTrue(text.contains("configd_write_commit_seconds_bucket{le=\"0.150\"} 1"),
                "100ms sample should fall inside le=\"0.150\" bucket\n" + text);
    }

    @Test
    void bucketScheduleCutoffsAreStrictlyIncreasing() {
        // Defensive — schedule constructors validate this, but assert
        // here so a future PR that reorders entries breaks the test
        // before it lands in production.
        var schedules = ConfigdMetrics.histogramSchedules();
        for (var entry : schedules.entrySet()) {
            PrometheusExporter.BucketSchedule s = entry.getValue();
            for (int i = 1; i < s.size(); i++) {
                assertTrue(s.cutoffAt(i) > s.cutoffAt(i - 1),
                        "schedule " + entry.getKey() + " not increasing at index " + i);
            }
        }
    }
}
