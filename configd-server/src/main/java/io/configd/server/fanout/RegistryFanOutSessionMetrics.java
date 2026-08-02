package io.configd.server.fanout;

import io.configd.distribution.fanout.DemotionEvent;
import io.configd.distribution.fanout.FanOutSessionMetrics;
import io.configd.observability.MetricsRegistry;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


public final class RegistryFanOutSessionMetrics implements FanOutSessionMetrics {

    private final MetricsRegistry.Counter heartbeats;
    private final MetricsRegistry.Counter slowConsumerWarnings;
    private final MetricsRegistry.Counter notifyBatches;
    private final MetricsRegistry.Histogram notifyBatchSize;
    private final MetricsRegistry.Counter snapshotTransfers;
    private final Map<String, MetricsRegistry.Counter> demotionsByReason;
    private final Map<String, MetricsRegistry.Counter> closedByReason;
    private final MetricsRegistry.Counter demotionsOther;
    private final MetricsRegistry.Counter closedOther;
    private final MetricsRegistry.Counter sessionsRefused;
    private final MetricsRegistry.Counter firstFrameTimeouts;
    private final MetricsRegistry.Counter subscribeTail;
    private final MetricsRegistry.Counter subscribeSnapshotFirst;
    private final MetricsRegistry.Counter revocationFailOpenAdmits;

    private final MetricsRegistry.Counter filteredDeltas;
    private final MetricsRegistry.Counter deliveredDeltas;
    private final MetricsRegistry.Counter cursorAdvances;
    private final MetricsRegistry.Counter filteredSessions;

    private final MetricsRegistry.Counter slowTransitions;
    private final MetricsRegistry.Counter quarantines;
    private final MetricsRegistry.Counter unhealthy;
    private final MetricsRegistry.Counter reconnectsRefused;
    private final MetricsRegistry.Counter readmissions;

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

        // Close reasons map to the ErrorCode.name() the session passes (plus the two synthetic reasons
        // AUTH_UNAVAILABLE and transport_gone that carry no ErrorCode); pre-register the common ones,
        // plus an other bucket. More than 10 entries, so Map.ofEntries (Map.of tops out at 10).
        this.closedByReason = Map.ofEntries(
                Map.entry("SERVER_SHUTDOWN", registry.counter("edge.fanout.sessions_closed.server_shutdown")),
                Map.entry("PROTOCOL_VIOLATION", registry.counter("edge.fanout.sessions_closed.protocol_violation")),
                Map.entry("FRAME_CORRUPT", registry.counter("edge.fanout.sessions_closed.frame_corrupt")),
                Map.entry("BAD_WIRE_VERSION", registry.counter("edge.fanout.sessions_closed.bad_wire_version")),
                Map.entry("AUTH_FAIL", registry.counter("edge.fanout.sessions_closed.auth_fail")),
                // AUTH_UNAVAILABLE: the credential could not be VERIFIED because the authenticator's backend
                // (OIDC JWKS, LDAP, ...) was unreachable - a distinct series from AUTH_FAIL (a bad credential)
                // so an operator can alert on "a down IdP is locking out legitimate clients" at 3am. The wire
                // still closes AUTH_FAIL (the taxonomy is golden-pinned); only the server metric distinguishes.
                Map.entry("AUTH_UNAVAILABLE", registry.counter("edge.fanout.sessions_closed.auth_unavailable")),
                // CREDENTIAL_EXPIRED: token TTL elapsed / a REFRESH_AUTH presented an unacceptable credential /
                // a cert notAfter reached mid-connection. Its own series so an aged-out close is not folded
                // into the generic `other` bucket.
                Map.entry("CREDENTIAL_EXPIRED", registry.counter("edge.fanout.sessions_closed.credential_expired")),
                Map.entry("GAP_UNRECOVERABLE", registry.counter("edge.fanout.sessions_closed.gap_unrecoverable")),
                Map.entry("QUARANTINED", registry.counter("edge.fanout.sessions_closed.quarantined")),
                Map.entry("BAD_SUBSCRIBE", registry.counter("edge.fanout.sessions_closed.bad_subscribe")),
                Map.entry("transport_gone", registry.counter("edge.fanout.sessions_closed.transport_gone")));
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

        // Pre-SUBSCRIBE first-frame-deadline reaps: an admitted (post-mTLS) connection that
        // did not send its first routed control frame (SUBSCRIBE / WATCH_CREATE) within the
        // first-frame window, closed as a slow-loris.
        this.firstFrameTimeouts = registry.counter("edge.fanout.first_frame_timeouts");

        // Revocation fail-open admits: under LAX with the responder unreachable, an edge client
        // cert is ADMITTED (fail-open) rather than rejected. A degraded-revocation posture must be
        // alertable - a rising rate means the cluster is admitting certs it could not check for revocation.
        this.revocationFailOpenAdmits = registry.counter("edge.fanout.revocation_fail_open_admits");

        // The subscribe-time replay-vs-re-bootstrap decision (per-reason-suffix
        // convention) + the horizon-distance input as a last-decision gauge.
        this.subscribeTail = registry.counter("edge.fanout.subscribe.tail");
        this.subscribeSnapshotFirst = registry.counter("edge.fanout.subscribe.snapshot_first");

        // Server-side prefix-filtering series. Deltas dropped vs delivered gives the
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

        registry.gauge("edge.fanout.consumer_state.healthy", consumersHealthy::get);
        registry.gauge("edge.fanout.consumer_state.slow", consumersSlow::get);
        registry.gauge("edge.fanout.consumer_state.catchup", consumersCatchup::get);
        registry.gauge("edge.fanout.consumer_state.quarantined", consumersQuarantined::get);
        registry.gauge("edge.fanout.consumer_state.unhealthy", consumersUnhealthy::get);
    }

    // Session lifecycle hooks the FanOutServer drives directly, not through the session interface.

    
    public void onSubscriberConnected() {
        connectedSubscribers.incrementAndGet();
    }

    
    public void onSessionRefused() {
        sessionsRefused.increment();
    }

    
    public void onFirstFrameTimeout() {
        firstFrameTimeouts.increment();
    }

    
    public void onSubscriberDisconnected() {
        connectedSubscribers.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    
    public void onRevocationFailOpenAdmit() {
        revocationFailOpenAdmits.increment();
    }

    
    public int connectedSubscribers() {
        return connectedSubscribers.get();
    }

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
        // counter is registered anyway for symmetry with the other event counters.
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
