package io.configd.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Eagerly registers the SLO-cited metrics in {@link MetricsRegistry} and
 * publishes the matching {@link PrometheusExporter.BucketSchedule}s.
 *
 * <p>F5 (Tier-1-METRIC-DRIFT, closes H-001 / DOC-014) — without this
 * class, every metric referenced by {@code ops/alerts/configd-slo-alerts.yaml}
 * and {@code ops/runbooks/*.md} would be created lazily on first
 * emission. That made the SLO pipeline decorative: the alerts query
 * series that never exist (no time series == no alert).
 *
 * <p>The constructor:
 * <ol>
 *   <li>Creates every counter / histogram / gauge name the alert YAML
 *       references, so the {@link PrometheusExporter} emits non-empty
 *       {@code # TYPE} lines on the very first scrape.</li>
 *   <li>Holds typed handles to those metrics for the production wire-up
 *       sites (state-machine apply, edge read, install-snapshot).</li>
 *   <li>Exposes the per-histogram bucket schedule that aligns the
 *       {@code _bucket{le=...}} output with the alert {@code le}
 *       thresholds in fractional seconds.</li>
 * </ol>
 *
 * <p>Thread-safety: holds final references to thread-safe counter and
 * histogram instances. Safe to share across server threads.
 */
public final class ConfigdMetrics {

    // ---- Registry-level metric names (canonical, dot-separated). -----
    // PrometheusExporter sanitizes these to underscores at scrape time.

    public static final String NAME_WRITE_COMMIT_TOTAL = "configd.write.commit";
    public static final String NAME_WRITE_COMMIT_FAILED = "configd.write.commit.failed";
    public static final String NAME_WRITE_COMMIT_SECONDS = "configd.write.commit.seconds";
    public static final String NAME_APPLY_SECONDS = "configd.apply.seconds";
    public static final String NAME_EDGE_READ_TOTAL = "configd.edge.read";
    public static final String NAME_EDGE_READ_SECONDS = "configd.edge.read.seconds";
    public static final String NAME_PROPAGATION_DELAY_SECONDS = "configd.propagation.delay.seconds";
    public static final String NAME_RAFT_PENDING_APPLY = "configd.raft.pending.apply.entries";
    public static final String NAME_SNAPSHOT_INSTALL_FAILED = "configd.snapshot.install.failed";
    public static final String NAME_SNAPSHOT_REBUILD = "configd.snapshot.rebuild";
    /**
     * H-009 (iter-2) — base name for the tick-loop unhandled-throwable
     * counter family. The actual Prometheus series carries a {@code class}
     * label pseudo-encoded into the registry name ({@code base.<bucket>})
     * because {@link MetricsRegistry} does not natively support labels;
     * the per-class bucketing is bounded by
     * {@link SafeLog#cardinalityGuard(String)} so the cardinality stays
     * inside Prometheus' safe envelope.
     */
    public static final String NAME_TICK_LOOP_THROWABLE_BASE = "configd.tick.loop.throwable";

    private final MetricsRegistry registry;

    private final MetricsRegistry.Counter writeCommitTotal;
    private final MetricsRegistry.Counter writeCommitFailed;
    private final MetricsRegistry.Histogram writeCommitSeconds;
    private final MetricsRegistry.Histogram applySeconds;
    private final MetricsRegistry.Counter edgeReadTotal;
    private final MetricsRegistry.Histogram edgeReadSeconds;
    private final MetricsRegistry.Histogram propagationDelaySeconds;
    private final MetricsRegistry.Counter snapshotInstallFailed;
    private final MetricsRegistry.Counter snapshotRebuild;

    /**
     * Eagerly registers all SLO metrics in {@code registry}. The optional
     * {@code raftPendingSupplier} backs the
     * {@code configd_raft_pending_apply_entries} gauge — pass
     * {@code null} in pre-wire-up tests to skip the gauge registration.
     */
    public ConfigdMetrics(MetricsRegistry registry, LongSupplier raftPendingSupplier) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");

        // Counters — eager creation so first scrape emits "_total 0".
        this.writeCommitTotal = registry.counter(NAME_WRITE_COMMIT_TOTAL);
        this.writeCommitFailed = registry.counter(NAME_WRITE_COMMIT_FAILED);
        this.edgeReadTotal = registry.counter(NAME_EDGE_READ_TOTAL);
        this.snapshotInstallFailed = registry.counter(NAME_SNAPSHOT_INSTALL_FAILED);
        this.snapshotRebuild = registry.counter(NAME_SNAPSHOT_REBUILD);

        // Histograms — eager creation so PrometheusExporter emits the
        // # TYPE histogram banner with le=+Inf even before any sample.
        this.writeCommitSeconds = registry.histogram(NAME_WRITE_COMMIT_SECONDS);
        this.applySeconds = registry.histogram(NAME_APPLY_SECONDS);
        this.edgeReadSeconds = registry.histogram(NAME_EDGE_READ_SECONDS);
        this.propagationDelaySeconds = registry.histogram(NAME_PROPAGATION_DELAY_SECONDS);

        // Gauge — only register when a supplier is provided. The supplier
        // must be allocation-free (called on every scrape).
        if (raftPendingSupplier != null) {
            registry.gauge(NAME_RAFT_PENDING_APPLY, raftPendingSupplier);
        }
    }

    /** Registers the gauge after construction. Useful when the supplier
     *  is not available at the time {@link ConfigdMetrics} is built (e.g.
     *  RaftNode is created later in the boot sequence). */
    public void bindRaftPendingApplyGauge(LongSupplier supplier) {
        Objects.requireNonNull(supplier, "supplier must not be null");
        registry.gauge(NAME_RAFT_PENDING_APPLY, supplier);
    }

    public MetricsRegistry registry() { return registry; }
    public MetricsRegistry.Counter writeCommitTotal() { return writeCommitTotal; }
    public MetricsRegistry.Counter writeCommitFailed() { return writeCommitFailed; }
    public MetricsRegistry.Histogram writeCommitSeconds() { return writeCommitSeconds; }
    public MetricsRegistry.Histogram applySeconds() { return applySeconds; }
    public MetricsRegistry.Counter edgeReadTotal() { return edgeReadTotal; }
    public MetricsRegistry.Histogram edgeReadSeconds() { return edgeReadSeconds; }
    public MetricsRegistry.Histogram propagationDelaySeconds() { return propagationDelaySeconds; }
    public MetricsRegistry.Counter snapshotInstallFailed() { return snapshotInstallFailed; }
    public MetricsRegistry.Counter snapshotRebuild() { return snapshotRebuild; }

    /**
     * H-009 (iter-2) — increments the tick-loop unhandled-throwable counter
     * for the given throwable's simple class name. The class label is
     * passed through {@link SafeLog#cardinalityGuard(String)} so that an
     * adversary who can pick the exception class cannot blow up the
     * Prometheus series count. Returns the bucketed label that was used,
     * so callers can include it in the structured log line they emit
     * alongside this metric increment.
     *
     * @param throwableClassName the simple class name of the unhandled
     *                           throwable (may be null → "unknown")
     * @return the bounded label value that was actually used
     */
    public String onTickLoopThrowable(String throwableClassName) {
        String label = SafeLog.cardinalityGuard(throwableClassName);
        registry.counter(NAME_TICK_LOOP_THROWABLE_BASE + "." + label).increment();
        return label;
    }

    /**
     * Returns the per-histogram bucket schedule map to pass to
     * {@link PrometheusExporter}. Each entry maps a registry-level
     * histogram name to a schedule whose {@code le} labels exactly match
     * the {@code le="X"} thresholds queried in
     * {@code ops/alerts/configd-slo-alerts.yaml}.
     *
     * <p>Cutoffs are nanoseconds because all latency samples are recorded
     * in nanoseconds (System.nanoTime() deltas).
     */
    public static Map<String, PrometheusExporter.BucketSchedule> histogramSchedules() {
        Map<String, PrometheusExporter.BucketSchedule> map = new LinkedHashMap<>();
        map.put(NAME_WRITE_COMMIT_SECONDS, latencySecondsSchedule());
        map.put(NAME_APPLY_SECONDS, latencySecondsSchedule());
        map.put(NAME_EDGE_READ_SECONDS, edgeReadSecondsSchedule());
        map.put(NAME_PROPAGATION_DELAY_SECONDS, propagationSecondsSchedule());
        return Collections.unmodifiableMap(map);
    }

    /**
     * Latency schedule for write-commit / apply paths (covers le="0.150"
     * referenced by the WriteCommitFastBurn / SlowBurn alerts).
     */
    private static PrometheusExporter.BucketSchedule latencySecondsSchedule() {
        LinkedHashMap<String, Long> m = new LinkedHashMap<>();
        m.put("0.005", 5_000_000L);
        m.put("0.010", 10_000_000L);
        m.put("0.025", 25_000_000L);
        m.put("0.050", 50_000_000L);
        m.put("0.100", 100_000_000L);
        m.put("0.150", 150_000_000L);
        m.put("0.250", 250_000_000L);
        m.put("0.500", 500_000_000L);
        m.put("1.000", 1_000_000_000L);
        m.put("2.500", 2_500_000_000L);
        m.put("5.000", 5_000_000_000L);
        m.put("10.000", 10_000_000_000L);
        return PrometheusExporter.BucketSchedule.of(m);
    }

    /**
     * Edge-read schedule (covers le="0.001" / le="0.005" referenced by the
     * EdgeReadFastBurn / P999 alerts).
     */
    private static PrometheusExporter.BucketSchedule edgeReadSecondsSchedule() {
        LinkedHashMap<String, Long> m = new LinkedHashMap<>();
        m.put("0.0001", 100_000L);
        m.put("0.0005", 500_000L);
        m.put("0.001", 1_000_000L);
        m.put("0.0025", 2_500_000L);
        m.put("0.005", 5_000_000L);
        m.put("0.010", 10_000_000L);
        m.put("0.025", 25_000_000L);
        m.put("0.050", 50_000_000L);
        m.put("0.100", 100_000_000L);
        return PrometheusExporter.BucketSchedule.of(m);
    }

    /**
     * Propagation-delay schedule (covers le="0.5" referenced by the
     * PropagationFastBurn alert).
     */
    private static PrometheusExporter.BucketSchedule propagationSecondsSchedule() {
        LinkedHashMap<String, Long> m = new LinkedHashMap<>();
        m.put("0.010", 10_000_000L);
        m.put("0.025", 25_000_000L);
        m.put("0.050", 50_000_000L);
        m.put("0.100", 100_000_000L);
        m.put("0.250", 250_000_000L);
        m.put("0.500", 500_000_000L);
        m.put("1.000", 1_000_000_000L);
        m.put("2.500", 2_500_000_000L);
        m.put("5.000", 5_000_000_000L);
        m.put("10.000", 10_000_000_000L);
        return PrometheusExporter.BucketSchedule.of(m);
    }
}
