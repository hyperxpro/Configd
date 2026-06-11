package io.configd.server.fanout;

import io.configd.distribution.fanout.DemotionEvent;
import io.configd.distribution.fanout.FanOutSessionMetrics;
import io.configd.observability.MetricsRegistry;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link FanOutSessionMetrics} backed by the server's {@link MetricsRegistry} (C1 design §4
 * metric table; charter §6 rule 8). Mirrors the leaf-module metrics-sink idiom: the
 * distribution-service stays observability-free behind the {@link FanOutSessionMetrics}
 * interface, and the live server bridges each callback to a registry counter / gauge here.
 *
 * <h2>Eager registration (RR-013)</h2>
 * Every series this class can ever write is registered in the constructor — including the
 * per-reason demotion / close counters and the connected-subscribers / queue-depth gauges —
 * so {@code PrometheusExporter} emits a zero-valued time series from the first scrape rather
 * than a metric that only blinks into existence after the first event (the RR-013 lesson: no
 * metric that production code never writes, and conversely no metric a scrape can't find until
 * it first fires).
 *
 * <h2>Label encoding</h2>
 * {@link MetricsRegistry} has no native label support, so the design's
 * {@code edge_fanout_demotions_total{reason=ack_lag}} becomes a distinct counter per reason
 * (e.g. {@code edge.fanout.demotions.ack_lag} → {@code edge_fanout_demotions_ack_lag_total}).
 * An unknown reason falls back to an {@code other} bucket so a new reason can never silently
 * vanish.
 *
 * <h2>Per-session gauges</h2>
 * {@code edge_fanout_queue_depth} is process-wide here (the max observed across live sessions),
 * not per-session — the registry is a flat process registry with no per-session label. The
 * connected-subscribers gauge is the live count. Both are honest process-level aggregates; a
 * per-session breakdown would need a label-capable backend (priced, not built).
 *
 * <p>Thread-safe: counters are themselves thread-safe; the gauge backing fields are atomics
 * written from session/writer virtual threads and read by the exporter snapshot thread.
 */
public final class RegistryFanOutSessionMetrics implements FanOutSessionMetrics {

    // --- Counters (eagerly registered) ---
    private final MetricsRegistry.Counter heartbeats;
    private final MetricsRegistry.Counter slowConsumerWarnings;
    private final MetricsRegistry.Counter notifyBatches;        // count of NOTIFY frames
    private final MetricsRegistry.Histogram notifyBatchSize;    // notifications per batch
    private final MetricsRegistry.Counter snapshotTransfers;
    private final Map<String, MetricsRegistry.Counter> demotionsByReason;
    private final Map<String, MetricsRegistry.Counter> closedByReason;
    private final MetricsRegistry.Counter demotionsOther;
    private final MetricsRegistry.Counter closedOther;

    // --- Gauge backing state (process-level aggregates) ---
    private final AtomicLong queueDepth = new AtomicLong(0);
    private final AtomicInteger connectedSubscribers = new AtomicInteger(0);

    public RegistryFanOutSessionMetrics(MetricsRegistry registry) {
        // Counters / histogram — registering them touches the registry so the series exists.
        this.heartbeats = registry.counter("edge.fanout.heartbeats");
        this.slowConsumerWarnings = registry.counter("edge.fanout.slow_consumer_warnings");
        this.notifyBatches = registry.counter("edge.fanout.notify_batches");
        this.notifyBatchSize = registry.histogram("edge.fanout.notify_batch_size");
        this.snapshotTransfers = registry.counter("edge.fanout.snapshot_transfers");

        // Pre-register one demotion + close counter per known reason (RR-013: no metric that
        // only appears after the first event). The DemotionEvent reasons are the canonical set.
        this.demotionsByReason = Map.of(
                DemotionEvent.REASON_QUEUE_OVERFLOW,
                registry.counter("edge.fanout.demotions." + DemotionEvent.REASON_QUEUE_OVERFLOW),
                DemotionEvent.REASON_ACK_LAG,
                registry.counter("edge.fanout.demotions." + DemotionEvent.REASON_ACK_LAG),
                DemotionEvent.REASON_GAP,
                registry.counter("edge.fanout.demotions." + DemotionEvent.REASON_GAP),
                DemotionEvent.REASON_TRANSPORT_BLOCK,
                registry.counter("edge.fanout.demotions." + DemotionEvent.REASON_TRANSPORT_BLOCK));
        this.demotionsOther = registry.counter("edge.fanout.demotions.other");

        // Close reasons map to the ErrorCode.name() the session passes; pre-register the
        // common ones, plus an other bucket.
        this.closedByReason = Map.of(
                "SERVER_SHUTDOWN", registry.counter("edge.fanout.sessions_closed.server_shutdown"),
                "PROTOCOL_VIOLATION", registry.counter("edge.fanout.sessions_closed.protocol_violation"),
                "FRAME_CORRUPT", registry.counter("edge.fanout.sessions_closed.frame_corrupt"),
                "BAD_WIRE_VERSION", registry.counter("edge.fanout.sessions_closed.bad_wire_version"),
                "AUTH_FAIL", registry.counter("edge.fanout.sessions_closed.auth_fail"),
                "GAP_UNRECOVERABLE", registry.counter("edge.fanout.sessions_closed.gap_unrecoverable"),
                "transport_gone", registry.counter("edge.fanout.sessions_closed.transport_gone"));
        this.closedOther = registry.counter("edge.fanout.sessions_closed.other");

        // Gauges (process-level aggregates; eagerly registered so the series exists at scrape 0).
        registry.gauge("edge.fanout.queue_depth", queueDepth::get);
        registry.gauge("edge.fanout.connected_subscribers", connectedSubscribers::get);
    }

    // --- Session lifecycle hooks the FanOutServer drives directly (not via the session) ---

    /** A subscriber connected (FanOutServer drives this on accept+subscribe). */
    public void onSubscriberConnected() {
        connectedSubscribers.incrementAndGet();
    }

    /** A subscriber disconnected (FanOutServer drives this on teardown). */
    public void onSubscriberDisconnected() {
        connectedSubscribers.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    /** Current connected-subscriber count (for tests / diagnostics). */
    public int connectedSubscribers() {
        return connectedSubscribers.get();
    }

    // --- FanOutSessionMetrics ---

    @Override
    public void onNotifyBatch(int n, int bytes) {
        notifyBatches.increment();
        notifyBatchSize.record(n);
    }

    @Override
    public void onQueueDepth(int depth) {
        // Process-level gauge: track the high-water mark of live unacked-frame depth.
        queueDepth.accumulateAndGet(depth, Math::max);
    }

    @Override
    public void onSlowConsumerWarning() {
        slowConsumerWarnings.increment();
    }

    @Override
    public void onDemotion(String reason) {
        demotionsByReason.getOrDefault(reason, demotionsOther).increment();
    }

    @Override
    public void onSnapshotTransfer() {
        // Snapshot transfers are observable via the demotion that precedes them; a dedicated
        // counter is registered for symmetry with the design table.
        snapshotTransfers.increment();
    }

    @Override
    public void onHeartbeat() {
        heartbeats.increment();
    }

    @Override
    public void onSessionClosed(String reason) {
        closedByReason.getOrDefault(reason, closedOther).increment();
    }
}
