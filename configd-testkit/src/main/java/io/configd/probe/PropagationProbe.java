package io.configd.probe;

import org.HdrHistogram.Histogram;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Transport-agnostic recorder of config-propagation staleness: the wall (or logical) time
 * between a committed write becoming visible at the boundary ({@link #recordPublished}) and
 * an observer applying it ({@link #recordVisible}). The staleness sample,
 * {@code visibleTs - publishTs}, is exactly the production staleness invariant
 * ({@code staleness = observer_now - commit_timestamp(last_applied)}).
 *
 * <p>The same recorder serves both probe modes: a single sim thread feeding exact logical
 * timestamps (checks the staleness <em>mechanism</em>), and concurrent live observers
 * feeding {@code System.currentTimeMillis()} (honest, hardware-caveated numbers).
 *
 * <h2>Thread-safety</h2>
 * Live mode has concurrent observers; sim mode is single-threaded. We use plain
 * (non-concurrent) {@link Histogram}s guarded by {@code this}. HdrHistogram's lock-free
 * {@code Recorder} was considered and rejected: its interval-reset (sample-and-clear) model
 * complicates producing a single cumulative {@link #report()}, and the probe is explicitly
 * <b>not</b> on any hot path (one record per committed write per observer), so a short
 * synchronized block costs nothing measurable.
 *
 * <p>Unmatched {@code recordVisible} calls (a seq never published) are not dropped silently:
 * they are counted per observer and globally and surfaced in the report.
 *
 * @see io.configd.distribution.CommitNotification
 */
public final class PropagationProbe {

    /** Generous staleness ceiling: 10 minutes in ms (well above any honest bound). */
    public static final long HIGHEST_TRACKABLE_MILLIS = 10L * 60L * 1_000L;

    /** Significant value digits retained by every histogram (HdrHistogram precision). */
    public static final int SIGNIFICANT_DIGITS = 3;

    private static final String UNIT = "ms";

    public static final String GLOBAL_SCOPE = "global";

    /**
     * Mutable per-observer recorder: the latency histogram plus the bookkeeping
     * counters that must never enter the distribution.
     */
    private static final class ObserverState {
        final int observerId;
        final Histogram histogram =
                new Histogram(1, HIGHEST_TRACKABLE_MILLIS, SIGNIFICANT_DIGITS);
        long unmatched;
        long overflow;

        ObserverState(int observerId) {
            this.observerId = observerId;
        }
    }

    /** Published commit timestamp per seq (the latest publish wins). Sorted for determinism. */
    private final Map<Long, Long> publishTsBySeq = new TreeMap<>();

    /** Per-observer state, keyed by observer id (sorted so the report is deterministic). */
    private final Map<Integer, ObserverState> observers = new TreeMap<>();

    private final Histogram globalHistogram =
            new Histogram(1, HIGHEST_TRACKABLE_MILLIS, SIGNIFICANT_DIGITS);

    private long globalUnmatched;
    private long globalOverflow;

    /**
         * Records that committed sequence {@code seq} became visible at the boundary at
         * {@code publishTsMillis}. Idempotent-friendly: re-publishing the same seq overwrites
         * the recorded publish time with the latest, so a re-delivered notification cannot
         * corrupt the clock.
         */
    public synchronized void recordPublished(long seq, long publishTsMillis) {
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be non-negative: " + seq);
        }
        if (publishTsMillis < 0) {
            throw new IllegalArgumentException(
                    "publishTsMillis must be non-negative: " + publishTsMillis);
        }
        publishTsBySeq.put(seq, publishTsMillis);
    }

    /**
         * Records that observer {@code observerId} saw committed sequence {@code seq} at
         * {@code visibleTsMillis}. A negative staleness (visible before published - clock skew
         * or a logically-impossible out-of-order sample) is clamped to 0 rather than rejected,
         * so the histogram stays well-formed.
         *
         * @param observerId the observer identity (an edge id in sim mode, a boundary id live)
         */
    public synchronized void recordVisible(int observerId, long seq, long visibleTsMillis) {
        if (visibleTsMillis < 0) {
            throw new IllegalArgumentException(
                    "visibleTsMillis must be non-negative: " + visibleTsMillis);
        }
        ObserverState state = observers.computeIfAbsent(observerId, ObserverState::new);
        Long publishTs = publishTsBySeq.get(seq);
        if (publishTs == null) {
            state.unmatched++;
            globalUnmatched++;
            return;
        }
        long staleness = visibleTsMillis - publishTs;
        if (staleness < 0) {
            staleness = 0; // observed "before" published (skew / out-of-order) - floor, do not drop
        }
        boolean overflow = staleness > HIGHEST_TRACKABLE_MILLIS;
        if (overflow) {
            staleness = HIGHEST_TRACKABLE_MILLIS;
            state.overflow++;
            globalOverflow++;
        }
        state.histogram.recordValue(staleness);
        globalHistogram.recordValue(staleness);
    }


    /** Sorted observer ids that have at least one recorded sample or unmatched count. */
    public synchronized List<Integer> observerIds() {
        return new ArrayList<>(observers.keySet());
    }

    /** Recorded (matched) sample count for {@code observerId}, or 0 if none. */
    public synchronized long count(int observerId) {
        ObserverState s = observers.get(observerId);
        return s == null ? 0L : s.histogram.getTotalCount();
    }

    /** Recorded (matched) sample count across all observers. */
    public synchronized long globalCount() {
        return globalHistogram.getTotalCount();
    }

    /**
     * Distinct sequence numbers seen by {@link #recordPublished} (the size of the
     * publish map). Exposed so a test can prove the publish seam is live without
     * needing a matching visibility sample.
     */
    public synchronized long publishedSeqCount() {
        return publishTsBySeq.size();
    }

    /** Unmatched {@code recordVisible} count for {@code observerId} (seq never published). */
    public synchronized long unmatched(int observerId) {
        ObserverState s = observers.get(observerId);
        return s == null ? 0L : s.unmatched;
    }

    public synchronized long globalUnmatched() {
        return globalUnmatched;
    }

    /** Value (ms) at {@code percentile} (0..100) for {@code observerId}; 0 if no samples. */
    public synchronized long percentile(int observerId, double percentile) {
        ObserverState s = observers.get(observerId);
        return s == null ? 0L : s.histogram.getValueAtPercentile(percentile);
    }

    /** Value (ms) at {@code percentile} (0..100) across all observers; 0 if no samples. */
    public synchronized long globalPercentile(double percentile) {
        return globalHistogram.getValueAtPercentile(percentile);
    }

    /** Minimum recorded staleness (ms) for {@code observerId}; 0 if no samples. */
    public synchronized long min(int observerId) {
        ObserverState s = observers.get(observerId);
        return s == null || s.histogram.getTotalCount() == 0 ? 0L : s.histogram.getMinValue();
    }

    /** Maximum recorded staleness (ms) for {@code observerId}; 0 if no samples. */
    public synchronized long max(int observerId) {
        ObserverState s = observers.get(observerId);
        return s == null || s.histogram.getTotalCount() == 0 ? 0L : s.histogram.getMaxValue();
    }

    /** Maximum recorded staleness (ms) across all observers; 0 if no samples. */
    public synchronized long globalMax() {
        return globalHistogram.getTotalCount() == 0 ? 0L : globalHistogram.getMaxValue();
    }

    /**
         * Exact count of recorded samples whose staleness is {@code >= valueMs}, across all
         * observers. A tail percentile (p999/p9999) backed by only a handful of samples at or
         * above its value is low-confidence; this lets a report state that count honestly
         * rather than guessing it.
         */
    public synchronized long globalCountAtOrAbove(long valueMs) {
        if (globalHistogram.getTotalCount() == 0) {
            return 0L;
        }
        long lo = Math.max(0L, Math.min(valueMs, HIGHEST_TRACKABLE_MILLIS));
        return globalHistogram.getCountBetweenValues(lo, HIGHEST_TRACKABLE_MILLIS);
    }


    /**
         * Produces the structured, deterministic text report: one block per observer plus a
         * global block, each followed by a machine-greppable summary line of the form
         * {@code PROBE-HISTOGRAM: scope=<global|observer-N> count=N p50=X p99=Y p999=Z max=M
         * unit=ms}.
         */
    public synchronized String report() {
        StringBuilder sb = new StringBuilder(512);
        for (ObserverState s : observers.values()) {
            appendHistogramBlock(sb, "observer-" + s.observerId, s.histogram, s.unmatched, s.overflow);
        }
        appendHistogramBlock(sb, GLOBAL_SCOPE, globalHistogram, globalUnmatched, globalOverflow);
        return sb.toString();
    }

    /**
         * Returns only the machine-greppable {@code PROBE-HISTOGRAM:} summary lines (one per
         * observer, then global), in deterministic order - the form CI greps for.
         * Newline-separated, ending with a newline.
         */
    public synchronized String summaryLines() {
        StringBuilder sb = new StringBuilder(256);
        for (ObserverState s : observers.values()) {
            sb.append(summaryLine("observer-" + s.observerId, s.histogram)).append('\n');
        }
        sb.append(summaryLine(GLOBAL_SCOPE, globalHistogram)).append('\n');
        return sb.toString();
    }

    private static void appendHistogramBlock(StringBuilder sb, String scope, Histogram h,
            long unmatched, long overflow) {
        long count = h.getTotalCount();
        sb.append("=== propagation staleness: scope=").append(scope).append(" ===\n");
        sb.append("  count   = ").append(count).append('\n');
        if (count > 0) {
            sb.append("  min     = ").append(h.getMinValue()).append(' ').append(UNIT).append('\n');
            sb.append("  p50     = ").append(h.getValueAtPercentile(50.0)).append(' ').append(UNIT).append('\n');
            sb.append("  p90     = ").append(h.getValueAtPercentile(90.0)).append(' ').append(UNIT).append('\n');
            sb.append("  p99     = ").append(h.getValueAtPercentile(99.0)).append(' ').append(UNIT).append('\n');
            sb.append("  p999    = ").append(h.getValueAtPercentile(99.9)).append(' ').append(UNIT).append('\n');
            sb.append("  p9999   = ").append(h.getValueAtPercentile(99.99)).append(' ').append(UNIT).append('\n');
            sb.append("  max     = ").append(h.getMaxValue()).append(' ').append(UNIT).append('\n');
        }
        sb.append("  unmatched = ").append(unmatched).append('\n');
        sb.append("  overflow  = ").append(overflow).append(" (clamped to ")
                .append(HIGHEST_TRACKABLE_MILLIS).append(' ').append(UNIT).append(")\n");
        sb.append(summaryLine(scope, h)).append('\n');
    }

    private static String summaryLine(String scope, Histogram h) {
        long count = h.getTotalCount();
        long p50 = count > 0 ? h.getValueAtPercentile(50.0) : 0;
        long p99 = count > 0 ? h.getValueAtPercentile(99.0) : 0;
        long p999 = count > 0 ? h.getValueAtPercentile(99.9) : 0;
        long max = count > 0 ? h.getMaxValue() : 0;
        return "PROBE-HISTOGRAM: scope=" + scope
                + " count=" + count
                + " p50=" + p50
                + " p99=" + p99
                + " p999=" + p999
                + " max=" + max
                + " unit=" + UNIT;
    }
}
