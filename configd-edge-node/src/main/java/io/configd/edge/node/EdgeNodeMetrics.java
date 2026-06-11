package io.configd.edge.node;

import io.configd.edge.EdgeClientCore;
import io.configd.edge.StalenessTracker;
import io.configd.observability.MetricsRegistry;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The edge node's metric series (C2 design §4 — names are contractual; charter §6 rule 8).
 * Bridges the transport-agnostic {@link EdgeClientCore} diagnostics into the process
 * {@link MetricsRegistry}, mirroring {@code RegistryFanOutSessionMetrics} on the server side.
 *
 * <h2>Eager registration (RR-013)</h2>
 * Every series is registered in the constructor so the very first {@code /metrics} scrape
 * returns a zero-valued time series, never a metric that blinks into existence after its
 * first event.
 *
 * <h2>Series (design §4, verbatim names after Prometheus mapping)</h2>
 * <ul>
 *   <li>{@code edge_staleness_ms} (gauge) — live {@code wall_now − frontier} (ADR-0039),
 *       read straight off the core's tracker at scrape time so an idle/disconnected edge
 *       cannot freeze the gauge (lying-dashboard guard);</li>
 *   <li>{@code edge_staleness_state} (gauge) — the contract §2 state ordinal
 *       (0=CURRENT 1=STALE 2=DEGRADED 3=DISCONNECTED);</li>
 *   <li>{@code configd_edge_staleness_violation_total} (CT-04) — incremented on each
 *       transition from below-STALE into STALE+;</li>
 *   <li>{@code edge_staleness_implausible_total} (CT-08) — the part-(a)
 *       {@code edge.staleness.implausible} counter wired into THIS registry (the counter
 *       instance is created here and handed to the core's constructor);</li>
 *   <li>{@code edge_cursor_lag} (gauge), {@code edge_applied_total},
 *       {@code edge_gaps_total}, {@code edge_snapshots_applied_total} — pumped from the
 *       core's single-writer diagnostics by {@link #syncFromCore};</li>
 *   <li>{@code edge_reads_total}, {@code edge_read_refusals_total{reason}} — the HTTP
 *       serving surface. {@link MetricsRegistry} has no label support, so the
 *       {@code {reason}} label is encoded per the established convention as a per-reason
 *       counter ({@code edge.read_refusals.cursor_behind} →
 *       {@code edge_read_refusals_cursor_behind_total}, likewise {@code strong_read});</li>
 *   <li>{@code edge_reconnects_total} — reconnect cycles initiated by the stream shell;</li>
 *   <li>{@code edge_verify_rejections_total} — F-0052/CT-23 signed-chain rejections
 *       (additional honest series beyond the §4 list; a rejecting edge must be visible);</li>
 *   <li>{@code edge_rebootstrap_triggered_total} — the DISCONNECTED re-bootstrap trigger
 *       seam (C2 names it + counts it; C3 orchestrates the actual re-bootstrap).</li>
 * </ul>
 *
 * <p>Thread-safety: counters are {@code LongAdder}-backed; {@link #syncFromCore} is called
 * only from the stream client's session thread (the core's single writer); the gauge
 * suppliers read volatile/atomic state and are safe from the exporter thread.
 */
final class EdgeNodeMetrics {

    /** Refusal reason: cursor-behind monotonic-read refusal (contract §3). */
    static final String REASON_CURSOR_BEHIND = "cursor_behind";
    /** Refusal reason: strong-read fail-close (CT-37). */
    static final String REASON_STRONG_READ = "strong_read";

    private final MetricsRegistry registry;

    private final MetricsRegistry.Counter applied;
    private final MetricsRegistry.Counter gaps;
    private final MetricsRegistry.Counter snapshotsApplied;
    private final MetricsRegistry.Counter verifyRejections;
    private final MetricsRegistry.Counter reads;
    private final MetricsRegistry.Counter refusalsCursorBehind;
    private final MetricsRegistry.Counter refusalsStrongRead;
    private final MetricsRegistry.Counter reconnects;
    private final MetricsRegistry.Counter stalenessViolations;
    private final MetricsRegistry.Counter rebootstrapTriggered;
    private final MetricsRegistry.Counter implausible;

    /** Gauge backing for cursor lag (written on the session thread, read by the exporter). */
    private final AtomicLong cursorLag = new AtomicLong(0);

    // --- delta-pump state (session thread only) ---
    private long lastApplied;
    private int lastGaps;
    private int lastSnapshots;
    private int lastVerifyRejections;
    private StalenessTracker.State lastState;

    EdgeNodeMetrics(MetricsRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.applied = registry.counter("edge.applied");
        this.gaps = registry.counter("edge.gaps");
        this.snapshotsApplied = registry.counter("edge.snapshots_applied");
        this.verifyRejections = registry.counter("edge.verify_rejections");
        this.reads = registry.counter("edge.reads");
        this.refusalsCursorBehind = registry.counter("edge.read_refusals." + REASON_CURSOR_BEHIND);
        this.refusalsStrongRead = registry.counter("edge.read_refusals." + REASON_STRONG_READ);
        this.reconnects = registry.counter("edge.reconnects");
        // CT-04: the contract names this series with the configd. prefix verbatim.
        this.stalenessViolations = registry.counter("configd.edge.staleness_violation");
        this.rebootstrapTriggered = registry.counter("edge.rebootstrap_triggered");
        // CT-08: the part-(a) StalenessTracker counter, wired into the process registry.
        this.implausible = registry.counter(StalenessTracker.IMPLAUSIBLE_METRIC);
        registry.gauge("edge.cursor_lag", cursorLag::get);
    }

    /** The CT-08 implausible-frontier counter to hand to {@link EdgeClientCore}'s constructor. */
    MetricsRegistry.Counter implausibleCounter() {
        return implausible;
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
        // re-bootstrap trigger — the initial SUBSCRIBE is the bootstrap (C5's territory).
        this.lastState = core.stalenessState();
    }

    /**
     * Pumps the core's single-writer diagnostic counters into the registry and detects
     * staleness-state transitions. MUST be called from the thread that owns the core
     * (the stream client's session loop) — both while connected and during backoff, so
     * the DISCONNECTED transition (which happens precisely while disconnected) is seen.
     *
     * @param core           the core to pump from (non-null)
     * @param rebootstrapHook invoked on each transition INTO DISCONNECTED (the C3
     *                        re-bootstrap orchestration seam; may be a no-op stub)
     */
    void syncFromCore(EdgeClientCore core, Runnable rebootstrapHook) {
        pump(applied, core.appliedCount() - lastApplied);
        lastApplied = core.appliedCount();
        pump(gaps, core.gapsDetected() - lastGaps);
        lastGaps = core.gapsDetected();
        pump(snapshotsApplied, core.snapshotsApplied() - lastSnapshots);
        lastSnapshots = core.snapshotsApplied();
        pump(verifyRejections, core.verifyRejections() - lastVerifyRejections);
        lastVerifyRejections = core.verifyRejections();
        cursorLag.set(core.cursorLag());

        StalenessTracker.State state = core.stalenessState();
        StalenessTracker.State previous = lastState;
        lastState = state;
        if (previous == null) {
            return; // bind() not called (test path); no transition baseline yet
        }
        // CT-04: count each transition from below-STALE into STALE+.
        if (previous.ordinal() < StalenessTracker.State.STALE.ordinal()
                && state.ordinal() >= StalenessTracker.State.STALE.ordinal()) {
            stalenessViolations.increment();
        }
        // CT-06 trigger half: transition INTO DISCONNECTED fires the re-bootstrap seam.
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
