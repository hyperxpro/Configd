package io.configd.server.fanout;

import io.configd.distribution.fanout.DemotionEvent;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryFanOutSessionMetricsTest {

    @Test
    void allSeriesAreEagerlyRegisteredAndExportedWithExactNames() {
        MetricsRegistry registry = new MetricsRegistry();
        new RegistryFanOutSessionMetrics(registry);

        String out = new PrometheusExporter(registry).export();

        assertTrue(out.contains("edge_fanout_heartbeats_total"), out);
        assertTrue(out.contains("edge_fanout_slow_consumer_warnings_total"), out);
        assertTrue(out.contains("edge_fanout_notify_batches_total"), out);
        assertTrue(out.contains("edge_fanout_snapshot_transfers_total"), out);
        assertTrue(out.contains("edge_fanout_notify_batch_size"), out);
        assertTrue(out.contains("edge_fanout_queue_depth"), out);
        assertTrue(out.contains("edge_fanout_connected_subscribers"), out);
        // Per-reason demotion counters (label encoded as a name suffix, registry has no labels).
        assertTrue(out.contains("edge_fanout_demotions_" + DemotionEvent.REASON_ACK_LAG + "_total"), out);
        assertTrue(out.contains("edge_fanout_demotions_" + DemotionEvent.REASON_QUEUE_OVERFLOW + "_total"), out);
        assertTrue(out.contains("edge_fanout_demotions_" + DemotionEvent.REASON_GAP + "_total"), out);
        assertTrue(out.contains("edge_fanout_demotions_" + DemotionEvent.REASON_TRANSPORT_BLOCK + "_total"), out);
        assertTrue(out.contains("edge_fanout_sessions_closed_server_shutdown_total"), out);
        assertTrue(out.contains("edge_fanout_sessions_closed_quarantined_total"), out);
        // The legacy-SUBSCRIBE refusal at N>1 gets its own series (not folded into other).
        assertTrue(out.contains("edge_fanout_sessions_closed_bad_subscribe_total"), out);
        assertTrue(out.contains("edge_fanout_slow_transitions_total"), out);
        assertTrue(out.contains("edge_fanout_quarantines_total"), out);
        assertTrue(out.contains("edge_fanout_unhealthy_total"), out);
        assertTrue(out.contains("edge_fanout_reconnects_refused_total"), out);
        assertTrue(out.contains("edge_fanout_readmissions_total"), out);
        assertTrue(out.contains("edge_fanout_consumer_state_healthy"), out);
        assertTrue(out.contains("edge_fanout_consumer_state_slow"), out);
        assertTrue(out.contains("edge_fanout_consumer_state_catchup"), out);
        assertTrue(out.contains("edge_fanout_consumer_state_quarantined"), out);
        assertTrue(out.contains("edge_fanout_consumer_state_unhealthy"), out);
    }

    @Test
    void slowConsumerPolicyCallbacksMoveTheRightSeries() {
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics m = new RegistryFanOutSessionMetrics(registry);

        m.onSlowTransition();
        m.onQuarantine();
        m.onQuarantine();
        m.onUnhealthy();
        m.onReconnectRefused();
        m.onReconnectRefused();
        m.onReconnectRefused();
        m.onReadmission();
        m.onConsumerStates(4, 3, 2, 1, 5);

        assertEquals(1, registry.counter("edge.fanout.slow_transitions").get());
        assertEquals(2, registry.counter("edge.fanout.quarantines").get());
        assertEquals(1, registry.counter("edge.fanout.unhealthy").get());
        assertEquals(3, registry.counter("edge.fanout.reconnects_refused").get());
        assertEquals(1, registry.counter("edge.fanout.readmissions").get());

        String out = new PrometheusExporter(registry).export();
        assertTrue(out.contains("edge_fanout_consumer_state_healthy 4"), out);
        assertTrue(out.contains("edge_fanout_consumer_state_slow 3"), out);
        assertTrue(out.contains("edge_fanout_consumer_state_catchup 2"), out);
        assertTrue(out.contains("edge_fanout_consumer_state_quarantined 1"), out);
        assertTrue(out.contains("edge_fanout_consumer_state_unhealthy 5"), out);
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
        assertEquals(1, m.connectedSubscribers());
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
