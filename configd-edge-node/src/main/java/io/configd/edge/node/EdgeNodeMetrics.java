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
        // The contract names this series with the configd. prefix verbatim.
        this.stalenessViolations = registry.counter("configd.edge.staleness_violation");
        this.rebootstrapTriggered = registry.counter("edge.rebootstrap_triggered");
        // The StalenessTracker counter, wired into the process registry.
        this.implausible = registry.counter(StalenessTracker.IMPLAUSIBLE_METRIC);
        registry.gauge("edge.cursor_lag", cursorLag::get);
        // Eager so configd_edge_read_seconds exists on the first scrape. The matching bucket
        // schedule is published to the exporter in EdgeNodeMain via
        // ConfigdMetrics.edgeProcessHistogramSchedules() so the le buckets match the edge-read alert.
        this.readLatency = registry.histogram(io.configd.observability.ConfigdMetrics.NAME_EDGE_READ_SECONDS);
    }

    /** Records one edge read-serving latency sample ({@code configd_edge_read_seconds}). */
    void recordReadLatency(long nanos) {
        readLatency.record(nanos);
    }

    /** The implausible-frontier counter to hand to {@link EdgeClientCore}'s constructor. */
    MetricsRegistry.Counter implausibleCounter() {
        return implausible;
    }

    /** The retry counter ({@code edge_poison_retries_total}) for the poison-pill policy. */
    MetricsRegistry.Counter poisonRetriesCounter() {
        return poisonRetries;
    }

    /** The quarantine counter ({@code configd_edge_poison_pill_total}). */
    MetricsRegistry.Counter poisonPillCounter() {
        return poisonPill;
    }

    /** The terminal counter ({@code configd_edge_poison_pill_terminal_total}). */
    MetricsRegistry.Counter poisonTerminalCounter() {
        return poisonTerminal;
    }

    /**
     * Registers the live staleness gauges against the core. Called once at wiring time;
     * the suppliers read the core's volatile frontier so the gauges stay current at
     * scrape time even when no events flow.
     */
    void bind(EdgeClientCore core) {
        Objects.requireNonNull(core, "core must not be null");
        registry.gauge("edge.staleness_ms", core::stalenessMs);
        registry.gauge("edge.staleness_state", () -> core.stalenessState().ordinal());
        // Seed transition detection with the boot state (DISCONNECTED until the first
        // frontier) so process start does not count as a STALE transition or a
        // re-bootstrap trigger — the initial SUBSCRIBE is the bootstrap.
        this.lastState = core.stalenessState();
    }

    /**
     * Pumps the core's single-writer diagnostic counters into the registry and detects
     * staleness-state transitions. MUST be called from the thread that owns the core
     * (the stream client's session loop) — both while connected and during backoff, so
     * the DISCONNECTED transition (which happens precisely while disconnected) is seen.
     *
     * @param core           the core to pump from (non-null)
     * @param rebootstrapHook invoked on each transition INTO DISCONNECTED (the re-bootstrap
     *                        orchestration seam; may be a no-op stub)
     */
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

    /** A read was served or attempted on the HTTP surface ({@code edge_reads_total}). */
    void onRead() {
        reads.increment();
    }

    /** A read was refused ({@code edge_read_refusals_total{reason}}). */
    void onReadRefused(String reason) {
        switch (reason) {
            case REASON_CURSOR_BEHIND -> refusalsCursorBehind.increment();
            case REASON_STRONG_READ -> refusalsStrongRead.increment();
            case REASON_NOT_SUBSCRIBED -> refusalsNotSubscribed.increment();
            default -> throw new IllegalArgumentException("unknown refusal reason: " + reason);
        }
    }

    /** A reconnect cycle was initiated ({@code edge_reconnects_total}). */
    void onReconnect() {
        reconnects.increment();
    }

    // --- test accessors ---

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
