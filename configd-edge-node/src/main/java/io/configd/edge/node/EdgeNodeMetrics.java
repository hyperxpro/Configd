package io.configd.edge.node;

import io.configd.edge.EdgeClientCore;
import io.configd.edge.PoisonPillPolicy;
import io.configd.edge.StalenessTracker;
import io.configd.observability.MetricsRegistry;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The edge node's metric series (names are contractual - renaming a series must break the
 * metrics contract test loudly). Bridges the transport-agnostic {@link EdgeClientCore}
 * diagnostics into the process {@link MetricsRegistry}, mirroring
 * {@code RegistryFanOutSessionMetrics} on the server side.
 *
 * <h2>Eager registration</h2>
 * Every series is registered in the constructor so the very first {@code /metrics} scrape
 * returns a zero-valued time series, never a metric that blinks into existence after its
 * first event.
 *
 * <h2>Series (verbatim names after Prometheus mapping)</h2>
 * <ul>
 *   <li>{@code edge_staleness_ms} (gauge) — live wall_now minus frontier, read straight
 *       off the core's tracker at scrape time so an idle/disconnected edge cannot freeze
 *       the gauge;</li>
 *   <li>{@code edge_staleness_state} (gauge) — the staleness state ordinal
 *       (0=CURRENT 1=STALE 2=DEGRADED 3=DISCONNECTED);</li>
 *   <li>{@code configd_edge_staleness_violation_total} — incremented on each transition
 *       from below-STALE into STALE+;</li>
 *   <li>{@code edge_staleness_implausible_total} — the implausible-frontier counter wired
 *       into THIS registry (the counter instance is created here and handed to the core's
 *       constructor);</li>
 *   <li>{@code edge_cursor_lag} (gauge), {@code edge_applied_total},
 *       {@code edge_gaps_total}, {@code edge_snapshots_applied_total},
 *       {@code edge_snapshot_chunks_rejected_total} (anti-exhaustion accumulation-cap
 *       rejections) — pumped from the core's single-writer diagnostics by {@link #syncFromCore};</li>
 *   <li>{@code edge_reads_total}, {@code edge_read_refusals_total{reason}} — the HTTP
 *       serving surface. {@link MetricsRegistry} has no label support, so the
 *       {@code {reason}} label is encoded per the established convention as a per-reason
 *       counter ({@code edge.read_refusals.cursor_behind} to
 *       {@code edge_read_refusals_cursor_behind_total}, likewise {@code strong_read});</li>
 *   <li>{@code edge_reconnects_total} — reconnect cycles initiated by the stream shell;</li>
 *   <li>{@code edge_verify_rejections_total} — signed-chain rejections (a rejecting edge
 *       must be visible);</li>
 *   <li>{@code edge_rebootstrap_triggered_total} — the DISCONNECTED re-bootstrap trigger
 *       (each entry into DISCONNECTED counts here and fires the orchestration seam).</li>
 * </ul>
 *
 * <p>Thread-safety: counters are {@code LongAdder}-backed; {@link #syncFromCore} is called
 * only from the stream client's session thread (the core's single writer); the gauge
 * suppliers read volatile/atomic state and are safe from the exporter thread.
 */
final class EdgeNodeMetrics {

    /** Refusal reason: cursor-behind monotonic-read refusal. */
    static final String REASON_CURSOR_BEHIND = "cursor_behind";
    /** Refusal reason: strong-read fail-close. */
    static final String REASON_STRONG_READ = "strong_read";
    /** Refusal reason: key outside the subscribed slice. */
    static final String REASON_NOT_SUBSCRIBED = "not_subscribed";

    private final MetricsRegistry registry;

    private final MetricsRegistry.Counter applied;
    private final MetricsRegistry.Counter gaps;
    private final MetricsRegistry.Counter snapshotsApplied;
    private final MetricsRegistry.Counter snapshotChunksRejected;
    private final MetricsRegistry.Counter verifyRejections;
    private final MetricsRegistry.Counter reads;
    private final MetricsRegistry.Counter refusalsCursorBehind;
    private final MetricsRegistry.Counter refusalsStrongRead;
    private final MetricsRegistry.Counter refusalsNotSubscribed;
    private final MetricsRegistry.Counter poisonRetries;
    private final MetricsRegistry.Counter poisonPill;
    private final MetricsRegistry.Counter poisonTerminal;
    private final MetricsRegistry.Counter reconnects;
    private final MetricsRegistry.Counter stalenessViolations;
    private final MetricsRegistry.Counter rebootstrapTriggered;
    private final MetricsRegistry.Counter implausible;
    /** Edge read-serving latency histogram ({@code configd_edge_read_seconds}). */
    private final MetricsRegistry.Histogram readLatency;

    /** Gauge backing for cursor lag (written on the session thread, read by the exporter). */
    private final AtomicLong cursorLag = new AtomicLong(0);

    // --- delta-pump state (session thread only) ---
    private long lastApplied;
    private int lastGaps;
    private int lastSnapshots;
    private int lastSnapshotChunksRejected;
    private int lastVerifyRejections;
    private StalenessTracker.State lastState;

    EdgeNodeMetrics(MetricsRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.applied = registry.counter("edge.applied");
        this.gaps = registry.counter("edge.gaps");
        this.snapshotsApplied = registry.counter("edge.snapshots_applied");
        this.snapshotChunksRejected = registry.counter("edge.snapshot_chunks_rejected");
        this.verifyRejections = registry.counter("edge.verify_rejections");
        this.reads = registry.counter("edge.reads");
        this.refusalsCursorBehind = registry.counter("edge.read_refusals." + REASON_CURSOR_BEHIND);
        this.refusalsStrongRead = registry.counter("edge.read_refusals." + REASON_STRONG_READ);
        this.refusalsNotSubscribed =
                registry.counter("edge.read_refusals." + REASON_NOT_SUBSCRIBED);
        // Handed to the PoisonPillPolicy, which increments them directly on its single-writer
        // path (no pump needed); eager so the series exist at scrape 0.
        this.poisonRetries = registry.counter(PoisonPillPolicy.RETRIES_METRIC);
        this.poisonPill = registry.counter(PoisonPillPolicy.POISON_PILL_METRIC);
        this.poisonTerminal = registry.counter(PoisonPillPolicy.TERMINAL_METRIC);
        this.reconnects = registry.counter("edge.reconnects");
        this.stalenessViolations = registry.counter("configd.edge.staleness_violation");
        this.rebootstrapTriggered = registry.counter("edge.rebootstrap_triggered");
        this.implausible = registry.counter(StalenessTracker.IMPLAUSIBLE_METRIC);
        registry.gauge("edge.cursor_lag", cursorLag::get);
        this.readLatency = registry.histogram(io.configd.observability.ConfigdMetrics.NAME_EDGE_READ_SECONDS);
    }

    void recordReadLatency(long nanos) {
        readLatency.record(nanos);
    }

    MetricsRegistry.Counter implausibleCounter() {
        return implausible;
    }

    MetricsRegistry.Counter poisonRetriesCounter() {
        return poisonRetries;
    }

    MetricsRegistry.Counter poisonPillCounter() {
        return poisonPill;
    }

    MetricsRegistry.Counter poisonTerminalCounter() {
        return poisonTerminal;
    }

    void bind(EdgeClientCore core) {
        Objects.requireNonNull(core, "core must not be null");
        registry.gauge("edge.staleness_ms", core::stalenessMs);
        registry.gauge("edge.staleness_state", () -> core.stalenessState().ordinal());
        // Seed transition detection with the boot state (DISCONNECTED until the first
        // frontier) so process start does not count as a STALE transition or a
        // re-bootstrap trigger — the initial SUBSCRIBE is the bootstrap.
        this.lastState = core.stalenessState();
    }

    // Must be called from the core's single writer thread (stream client's session loop).
    void syncFromCore(EdgeClientCore core, Runnable rebootstrapHook) {
        pump(applied, core.appliedCount() - lastApplied);
        lastApplied = core.appliedCount();
        pump(gaps, core.gapsDetected() - lastGaps);
        lastGaps = core.gapsDetected();
        pump(snapshotsApplied, core.snapshotsApplied() - lastSnapshots);
        lastSnapshots = core.snapshotsApplied();
        pump(snapshotChunksRejected, core.snapshotChunksRejected() - lastSnapshotChunksRejected);
        lastSnapshotChunksRejected = core.snapshotChunksRejected();
        pump(verifyRejections, core.verifyRejections() - lastVerifyRejections);
        lastVerifyRejections = core.verifyRejections();
        cursorLag.set(core.cursorLag());

        StalenessTracker.State state = core.stalenessState();
        StalenessTracker.State previous = lastState;
        lastState = state;
        if (previous == null) {
            return; // bind() not called (test path); no transition baseline yet
        }
        // Count each transition from below-STALE into STALE+.
        if (previous.ordinal() < StalenessTracker.State.STALE.ordinal()
                && state.ordinal() >= StalenessTracker.State.STALE.ordinal()) {
            stalenessViolations.increment();
        }
        // Transition INTO DISCONNECTED fires the re-bootstrap seam.
        if (previous != StalenessTracker.State.DISCONNECTED
                && state == StalenessTracker.State.DISCONNECTED) {
            rebootstrapTriggered.increment();
            if (rebootstrapHook != null) {
                rebootstrapHook.run();
            }
        }
    }

    void onRead() {
        reads.increment();
    }

    void onReadRefused(String reason) {
        switch (reason) {
            case REASON_CURSOR_BEHIND -> refusalsCursorBehind.increment();
            case REASON_STRONG_READ -> refusalsStrongRead.increment();
            case REASON_NOT_SUBSCRIBED -> refusalsNotSubscribed.increment();
            default -> throw new IllegalArgumentException("unknown refusal reason: " + reason);
        }
    }

    void onReconnect() {
        reconnects.increment();
    }

    long reconnectsCount() {
        return reconnects.get();
    }

    long rebootstrapTriggeredCount() {
        return rebootstrapTriggered.get();
    }

    long stalenessViolationsCount() {
        return stalenessViolations.get();
    }

    private static void pump(MetricsRegistry.Counter counter, long delta) {
        if (delta > 0) {
            counter.increment(delta);
        }
    }
}
