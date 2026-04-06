package io.configd.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exports {@link MetricsRegistry} snapshots in Prometheus text exposition format.
 * <p>
 * Output follows the <a href="https://prometheus.io/docs/instrumenting/exposition_formats/">
 * Prometheus exposition format</a> specification. Counter names are suffixed with
 * {@code _total}. Histogram names emit {@code _count} and percentile lines.
 * Histograms with a registered {@link BucketSchedule} additionally emit
 * cumulative {@code _bucket{le="X"}} lines so SLO alert expressions of the
 * form {@code histogram_quantile} or
 * {@code rate(<name>_bucket{le="0.150"}[5m]) / rate(<name>_count[5m])} have
 * concrete time series to query.
 * <p>
 * Thread safety: safe for concurrent use — reads an immutable snapshot.
 */
public final class PrometheusExporter {

    /**
     * Per-histogram cumulative-bucket schedule. Each entry maps a Prometheus
     * {@code le="X"} label (rendered verbatim into the output) to its cutoff
     * in the same units as the histogram samples — for the SLO histograms
     * (write-commit, edge-read, propagation) that is nanoseconds.
     *
     * <p>F5 (Tier-1-METRIC-DRIFT, H-009 iter-2 sibling) — without an explicit
     * schedule, the exporter only emits quantile lines, but
     * {@code ops/alerts/configd-slo-alerts.yaml} queries {@code _bucket{le="X"}}
     * series. Registering a schedule keeps the alert vocabulary and the
     * exposition vocabulary in lock-step.
     */
    public static final class BucketSchedule {
        private final List<String> labels;
        private final long[] cutoffs;

        private BucketSchedule(List<String> labels, long[] cutoffs) {
            if (labels.size() != cutoffs.length) {
                throw new IllegalArgumentException("labels/cutoffs length mismatch");
            }
            for (int i = 1; i < cutoffs.length; i++) {
                if (cutoffs[i] <= cutoffs[i - 1]) {
                    throw new IllegalArgumentException(
                            "cutoffs must be strictly increasing at index " + i);
                }
            }
            this.labels = List.copyOf(labels);
            this.cutoffs = cutoffs.clone();
        }

        /**
         * Builds a schedule from an ordered map of {@code le} label → cutoff.
         * Iteration order of the supplied map is preserved.
         */
        public static BucketSchedule of(Map<String, Long> labelsToCutoffs) {
            Objects.requireNonNull(labelsToCutoffs, "labelsToCutoffs must not be null");
            String[] ls = labelsToCutoffs.keySet().toArray(new String[0]);
            long[] cs = new long[ls.length];
            int i = 0;
            for (Long v : labelsToCutoffs.values()) {
                cs[i++] = v;
            }
            return new BucketSchedule(List.of(ls), cs);
        }

        /** Number of finite buckets in this schedule (exclusive of {@code +Inf}). */
        public int size() { return cutoffs.length; }

        /** Cutoff (in sample units) of the bucket at the given index. */
        public long cutoffAt(int i) { return cutoffs[i]; }

        /** Label string ({@code le} value) of the bucket at the given index. */
        public String labelAt(int i) { return labels.get(i); }
    }

    private final MetricsRegistry registry;
    private final Map<String, BucketSchedule> schedules;

    public PrometheusExporter(MetricsRegistry registry) {
        this(registry, Collections.emptyMap());
    }

    /**
     * Creates an exporter that emits {@code _bucket{le="X"}} lines for the
     * histograms named in {@code schedules}. Histograms without a schedule
     * fall back to quantile lines (existing behavior).
     */
    public PrometheusExporter(MetricsRegistry registry, Map<String, BucketSchedule> schedules) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.schedules = Map.copyOf(Objects.requireNonNull(schedules, "schedules must not be null"));
    }

    /**
     * Exports all metrics in Prometheus text format.
     *
     * @return the metrics text (UTF-8, newline-terminated)
     */
    public String export() {
        MetricsRegistry.MetricsSnapshot snapshot = registry.snapshot();
        StringBuilder sb = new StringBuilder(4096);

        snapshot.metrics().forEach((name, metric) -> {
            String promName = sanitizeName(name);
            switch (metric.type()) {
                case "counter" -> {
                    sb.append("# TYPE ").append(promName).append("_total counter\n");
                    sb.append(promName).append("_total ").append(metric.value()).append('\n');
                }
                case "gauge" -> {
                    sb.append("# TYPE ").append(promName).append(" gauge\n");
                    sb.append(promName).append(' ').append(metric.value()).append('\n');
                }
                case "histogram" -> {
                    BucketSchedule schedule = schedules.get(name);
                    MetricsRegistry.Histogram hist = registry.histogram(name);
                    if (schedule != null) {
                        // F5 (Tier-1-METRIC-DRIFT) — emit cumulative bucket
                        // lines so SLO alert expressions querying
                        // {le="X"} have actual time series to read.
                        sb.append("# TYPE ").append(promName).append(" histogram\n");
                        long[] cutoffs = new long[schedule.size()];
                        for (int i = 0; i < cutoffs.length; i++) cutoffs[i] = schedule.cutoffAt(i);
                        long[] counts = hist.bucketCounts(cutoffs);
                        long total = hist.count();
                        for (int i = 0; i < schedule.size(); i++) {
                            sb.append(promName).append("_bucket{le=\"")
                                    .append(schedule.labelAt(i)).append("\"} ")
                                    .append(counts[i]).append('\n');
                        }
                        sb.append(promName).append("_bucket{le=\"+Inf\"} ").append(total).append('\n');
                        sb.append(promName).append("_count ").append(total).append('\n');
                    } else {
                        sb.append("# TYPE ").append(promName).append(" summary\n");
                        sb.append(promName).append("_count ").append(metric.value()).append('\n');
                        if (hist.count() > 0) {
                            sb.append(promName).append("{quantile=\"0.5\"} ").append(hist.percentile(0.5)).append('\n');
                            sb.append(promName).append("{quantile=\"0.9\"} ").append(hist.percentile(0.9)).append('\n');
                            sb.append(promName).append("{quantile=\"0.99\"} ").append(hist.percentile(0.99)).append('\n');
                            sb.append(promName).append("{quantile=\"0.999\"} ").append(hist.percentile(0.999)).append('\n');
                        }
                    }
                }
                default -> {
                    sb.append("# TYPE ").append(promName).append(" untyped\n");
                    sb.append(promName).append(' ').append(metric.value()).append('\n');
                }
            }
        });

        return sb.toString();
    }

    /**
     * Sanitizes a metric name for Prometheus compatibility.
     * Prometheus metric names must match {@code [a-zA-Z_:][a-zA-Z0-9_:]*}.
     */
    private static String sanitizeName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == ':' ||
                (i > 0 && c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (c == '.' || c == '-') {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
