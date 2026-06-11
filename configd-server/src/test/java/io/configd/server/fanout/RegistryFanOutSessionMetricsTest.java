package io.configd.server.fanout;

import io.configd.distribution.fanout.DemotionEvent;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RegistryFanOutSessionMetrics} registers the design §4 series eagerly (RR-013:
 * a metric the exporter can find from the first scrape, not one that blinks in on first event)
 * and that they render with the exact {@code edge_fanout_*} Prometheus names.
 */
class RegistryFanOutSessionMetricsTest {

    @Test
    void allSeriesAreEagerlyRegisteredAndExportedWithExactNames() {
        MetricsRegistry registry = new MetricsRegistry();
        new RegistryFanOutSessionMetrics(registry); // constructor registers everything

        String out = new PrometheusExporter(registry).export();

        // Counters -> *_total; gauges bare. (RR-013: present at value 0 before any event.)
        assertTrue(out.contains("edge_fanout_heartbeats_total"), out);
        assertTrue(out.contains("edge_fanout_slow_consumer_warnings_total"), out);
        assertTrue(out.contains("edge_fanout_notify_batches_total"), out);
        assertTrue(out.contains("edge_fanout_snapshot_transfers_total"), out);
        assertTrue(out.contains("edge_fanout_notify_batch_size"), out); // histogram _count
        assertTrue(out.contains("edge_fanout_queue_depth"), out);       // gauge
        assertTrue(out.contains("edge_fanout_connected_subscribers"), out); // gauge
        // Per-reason demotion counters (label encoded as a name suffix, registry has no labels).
        assertTrue(out.contains("edge_fanout_demotions_" + DemotionEvent.REASON_ACK_LAG + "_total"), out);
        assertTrue(out.contains("edge_fanout_demotions_" + DemotionEvent.REASON_QUEUE_OVERFLOW + "_total"), out);
        assertTrue(out.contains("edge_fanout_demotions_" + DemotionEvent.REASON_GAP + "_total"), out);
        assertTrue(out.contains("edge_fanout_demotions_" + DemotionEvent.REASON_TRANSPORT_BLOCK + "_total"), out);
        // Per-reason session-closed counters.
        assertTrue(out.contains("edge_fanout_sessions_closed_server_shutdown_total"), out);
    }

    @Test
    void callbacksMoveTheRightCounters() {
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics m = new RegistryFanOutSessionMetrics(registry);

        m.onHeartbeat();
        m.onHeartbeat();
        m.onNotifyBatch(5, 100);
        m.onSlowConsumerWarning();
        m.onDemotion(DemotionEvent.REASON_ACK_LAG);
        m.onSnapshotTransfer();
        m.onSubscriberConnected();
        m.onSubscriberConnected();
        m.onSubscriberDisconnected();
        m.onSessionClosed("SERVER_SHUTDOWN");
        m.onQueueDepth(7);

        assertEquals(2, registry.counter("edge.fanout.heartbeats").get());
        assertEquals(1, registry.counter("edge.fanout.notify_batches").get());
        assertEquals(1, registry.counter("edge.fanout.slow_consumer_warnings").get());
        assertEquals(1, registry.counter("edge.fanout.demotions." + DemotionEvent.REASON_ACK_LAG).get());
        assertEquals(1, registry.counter("edge.fanout.snapshot_transfers").get());
        assertEquals(1, registry.counter("edge.fanout.sessions_closed.server_shutdown").get());
        assertEquals(1, m.connectedSubscribers()); // 2 up - 1 down
        // notify_batch_size histogram recorded the batch size.
        assertEquals(1, registry.histogram("edge.fanout.notify_batch_size").count());
    }

    @Test
    void unknownDemotionAndCloseReasonsFallToOtherBucket() {
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics m = new RegistryFanOutSessionMetrics(registry);
        m.onDemotion("a_brand_new_reason");
        m.onSessionClosed("UNMAPPED_CODE");
        assertEquals(1, registry.counter("edge.fanout.demotions.other").get());
        assertEquals(1, registry.counter("edge.fanout.sessions_closed.other").get());
    }
}
