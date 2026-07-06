package io.configd.server.fanout;

import io.configd.distribution.fanout.DemotionEvent;
import io.configd.distribution.fanout.FanOutSessionMetrics;
import io.configd.observability.MetricsRegistry;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link FanOutSessionMetrics} backed by the server's {@link MetricsRegistry}. Mirrors the leaf-module metrics-sink idiom: the
 * distribution-service stays observability-free behind the {@link FanOutSessionMetrics}
 * interface, and the live server bridges each callback to a registry counter / gauge here.
 *
 * <h2>Eager registration</h2>
 * Every series this class can ever write is registered in the constructor - including the
 * per-reason demotion / close counters and the connected-subscribers / queue-depth gauges -
 * so {@code PrometheusExporter} emits a zero-valued time series from the first scrape rather
 * than a metric that only blinks into existence after the first event (no
 * metric that production code never writes, and conversely no metric a scrape can't find until
 * it first fires).
 *
 * <h2>Label encoding</h2>
 * {@link MetricsRegistry} has no native label support, so the design's
 * {@code edge_fanout_demotions_total{reason=ack_lag}} becomes a distinct counter per reason
 * (e.g. {@code edge.fanout.demotions.ack_lag} -> {@code edge_fanout_demotions_ack_lag_total}).
 * An unknown reason falls back to an {@code other} bucket so a new reason can never silently
 * vanish.
 *
 * <h2>Per-session gauges</h2>
 * {@code edge_fanout_queue_depth} is process-wide here (the max observed across live sessions),
 * not per-session - the registry is a flat process registry with no per-session label. The
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
    private final MetricsRegistry.Counter sessionsRefused;
    private final MetricsRegistry.Counter firstFrameTimeouts;
    private final MetricsRegistry.Counter subscribeTail;
    private final MetricsRegistry.Counter subscribeSnapshotFirst;

    // --- Server-side prefix-filtering counters (ADR-0045) ---
    private final MetricsRegistry.Counter filteredDeltas;
    private final MetricsRegistry.Counter deliveredDeltas;
    private final MetricsRegistry.Counter cursorAdvances;
    private final MetricsRegistry.Counter filteredSessions;

    // --- Slow-consumer policy counters (SlowConsumerGovernor) ---
    private final MetricsRegistry.Counter slowTransitions;
    private final MetricsRegistry.Counter quarantines;
    private final MetricsRegistry.Counter unhealthy;
    private final MetricsRegistry.Counter reconnectsRefused;
    private final MetricsRegistry.Counter readmissions;

    // --- Gauge backing state (process-level aggregates) ---
    private final AtomicLong queueDepth = new AtomicLong(0);
    private final AtomicInteger connectedSubscribers = new AtomicInteger(0);
    private final AtomicLong subscribeHorizonDistance = new AtomicLong(0);

    // Per-state tracked-identity tallies (per-suffix encoded for the label-free registry).
    private final AtomicInteger consumersHealthy = new AtomicInteger(0);
    private final AtomicInteger consumersSlow = new AtomicInteger(0);
    private final AtomicInteger consumersCatchup = new AtomicInteger(0);
    private final AtomicInteger consumersQuarantined = new AtomicInteger(0);
    private final AtomicInteger consumersUnhealthy = new AtomicInteger(0);

    public RegistryFanOutSessionMetrics(MetricsRegistry registry) {
        // Counters / histogram - registering them touches the registry so the series exists.
        this.heartbeats = registry.counter("edge.fanout.heartbeats");
        this.slowConsumerWarnings = registry.counter("edge.fanout.slow_consumer_warnings");
        this.notifyBatches = registry.counter("edge.fanout.notify_batches");
        this.notifyBatchSize = registry.histogram("edge.fanout.notify_batch_size");
        this.snapshotTransfers = registry.counter("edge.fanout.snapshot_transfers");

        // Pre-register one demotion + close counter per known reason (no metric that
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
                "QUARANTINED", registry.counter("edge.fanout.sessions_closed.quarantined"),
                "BAD_SUBSCRIBE", registry.counter("edge.fanout.sessions_closed.bad_subscribe"),
                "transport_gone", registry.counter("edge.fanout.sessions_closed.transport_gone"));
        this.closedOther = registry.counter("edge.fanout.sessions_closed.other");

        // Slow-consumer policy series (eagerly registered). One counter per governor
        // transition family: SLOW promotion, quarantine, unhealthy escalation, cooldown
        // refusal, cooldown readmission.
        this.slowTransitions = registry.counter("edge.fanout.slow_transitions");
        this.quarantines = registry.counter("edge.fanout.quarantines");
        this.unhealthy = registry.counter("edge.fanout.unhealthy");
        this.reconnectsRefused = registry.counter("edge.fanout.reconnects_refused");
        this.readmissions = registry.counter("edge.fanout.readmissions");

        // Admission-bound refusals (edge.fanout.transport.maxSessions).
        this.sessionsRefused = registry.counter("edge.fanout.sessions_refused");

        // WH-11 pre-SUBSCRIBE first-frame-deadline reaps: an admitted (post-mTLS) connection that
        // did not send its first routed control frame (SUBSCRIBE / WATCH_CREATE) within the
        // first-frame window, closed as a slow-loris.
        this.firstFrameTimeouts = registry.counter("edge.fanout.first_frame_timeouts");

        // The subscribe-time replay-vs-re-bootstrap decision (per-reason-suffix
        // convention) + the horizon-distance input as a last-decision gauge.
        this.subscribeTail = registry.counter("edge.fanout.subscribe.tail");
        this.subscribeSnapshotFirst = registry.counter("edge.fanout.subscribe.snapshot_first");

        // Server-side prefix-filtering series (ADR-0045). deltas dropped vs delivered gives the
        // measured egress reduction; cursor advances count the coalesced covered-S heartbeats;
        // filtered sessions is the cumulative count of subscribers that opted into filtering.
        this.filteredDeltas = registry.counter("edge.fanout.filtered_deltas");
        this.deliveredDeltas = registry.counter("edge.fanout.delivered_deltas");
        this.cursorAdvances = registry.counter("edge.fanout.cursor_advances");
        this.filteredSessions = registry.counter("edge.fanout.filtered_sessions");

        // Gauges (process-level aggregates; eagerly registered so the series exists at scrape 0).
        registry.gauge("edge.fanout.queue_depth", queueDepth::get);
        registry.gauge("edge.fanout.connected_subscribers", connectedSubscribers::get);
        registry.gauge("edge.fanout.subscribe.horizon_distance", subscribeHorizonDistance::get);

        // Per-state consumer tallies (per-suffix encoded).
        registry.gauge("edge.fanout.consumer_state.healthy", consumersHealthy::get);
        registry.gauge("edge.fanout.consumer_state.slow", consumersSlow::get);
        registry.gauge("edge.fanout.consumer_state.catchup", consumersCatchup::get);
        registry.gauge("edge.fanout.consumer_state.quarantined", consumersQuarantined::get);
        registry.gauge("edge.fanout.consumer_state.unhealthy", consumersUnhealthy::get);
    }

    // --- Session lifecycle hooks the FanOutServer drives directly (not via the session) ---

    /** A subscriber connected (FanOutServer drives this on accept+subscribe). */
    public void onSubscriberConnected() {
        connectedSubscribers.incrementAndGet();
    }

    /** A connection refused at the admission bound ({@code maxSessions}), pre-handshake. */
    public void onSessionRefused() {
        sessionsRefused.increment();
    }

    /**
     * An admitted (post-mTLS) connection was reaped for missing the pre-SUBSCRIBE first-frame
     * deadline (WH-11 slow-loris). Driven by the transport (both FanOutServer and NettyFanOutServer),
     * not the session core, so it lives here alongside {@link #onSessionRefused()}.
     */
    public void onFirstFrameTimeout() {
        firstFrameTimeouts.increment();
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

    @Override
    public void onSubscribeMode(boolean snapshotFirst, long horizonDistance) {
        (snapshotFirst ? subscribeSnapshotFirst : subscribeTail).increment();
        subscribeHorizonDistance.set(horizonDistance);
    }

    // --- Server-side prefix filtering (ADR-0045) ---

    @Override
    public void onFilteredDeltas(int n) {
        filteredDeltas.increment(n);
    }

    @Override
    public void onDeliveredDeltas(int n) {
        deliveredDeltas.increment(n);
    }

    @Override
    public void onCursorAdvance() {
        cursorAdvances.increment();
    }

    @Override
    public void onFilterActive(boolean active) {
        if (active) {
            filteredSessions.increment();
        }
    }

    // --- Slow-consumer policy (SlowConsumerGovernor) ---

    @Override
    public void onSlowTransition() {
        slowTransitions.increment();
    }

    @Override
    public void onQuarantine() {
        quarantines.increment();
    }

    @Override
    public void onUnhealthy() {
        unhealthy.increment();
    }

    @Override
    public void onReconnectRefused() {
        reconnectsRefused.increment();
    }

    @Override
    public void onReadmission() {
        readmissions.increment();
    }

    @Override
    public void onConsumerStates(int healthy, int slow, int catchup,
                                 int quarantined, int unhealthy) {
        consumersHealthy.set(healthy);
        consumersSlow.set(slow);
        consumersCatchup.set(catchup);
        consumersQuarantined.set(quarantined);
        this.consumersUnhealthy.set(unhealthy);
    }
}
