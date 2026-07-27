package io.configd.observability;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

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
        // The inbound decode-drop counter is eager-created so it emits _total 0 from the first
        // scrape (anti-blind-dashboard), even before any frame is dropped.
        assertEquals("counter",
                snapshot.get(ConfigdMetrics.NAME_RAFT_DECODE_DROPPED).type());
        assertEquals(0L,
                snapshot.get(ConfigdMetrics.NAME_RAFT_DECODE_DROPPED).value());
        assertEquals("gauge",
                snapshot.get(ConfigdMetrics.NAME_RAFT_PENDING_APPLY).type());
        assertEquals(7L,
                snapshot.get(ConfigdMetrics.NAME_RAFT_PENDING_APPLY).value());
    }

    @Test
    void gaugeBindingIsLateBindable() {
        MetricsRegistry registry = new MetricsRegistry();
        ConfigdMetrics metrics = new ConfigdMetrics(registry, null);
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
        metrics.writeCommitTotal().increment();
        metrics.writeCommitSeconds().record(100_000_000L);
        metrics.edgeReadTotal().increment();
        metrics.edgeReadSeconds().record(500_000L);
        metrics.propagationDelaySeconds().record(50_000_000L);
        PrometheusExporter exporter = new PrometheusExporter(
                registry, ConfigdMetrics.histogramSchedules());
        String text = exporter.export();
        assertTrue(text.contains("configd_write_commit_seconds_bucket{le=\"0.150\"}"),
                "missing le=\"0.150\" bucket on write-commit histogram\n" + text);
        assertTrue(text.contains("configd_edge_read_seconds_bucket{le=\"0.001\"}"),
                "missing le=\"0.001\" bucket on edge-read histogram\n" + text);
        assertTrue(text.contains("configd_edge_read_seconds_bucket{le=\"0.005\"}"),
                "missing le=\"0.005\" bucket on edge-read histogram\n" + text);
        assertTrue(text.contains("configd_propagation_delay_seconds_bucket{le=\"0.500\"}"),
                "missing le=\"0.500\" bucket on propagation-delay histogram\n" + text);
        assertTrue(text.contains("configd_write_commit_total 1"),
                "missing write_commit_total counter\n" + text);
        assertTrue(text.contains("configd_write_commit_failed_total 0"),
                "missing write_commit_failed_total counter\n" + text);
        assertTrue(text.contains("configd_snapshot_install_failed_total 0"),
                "missing snapshot_install_failed_total counter\n" + text);
        assertTrue(text.contains("configd_raft_pending_apply_entries 0"),
                "missing raft_pending_apply_entries gauge\n" + text);
        assertTrue(text.contains("configd_write_commit_seconds_bucket{le=\"0.150\"} 1"),
                "100ms sample should fall inside le=\"0.150\" bucket\n" + text);
    }

    @Test
    void bucketScheduleCutoffsAreStrictlyIncreasing() {
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
