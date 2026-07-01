package io.configd.probe;

import org.HdrHistogram.Histogram;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reduces a stream of staleness samples (one integer-millisecond value per line on stdin)
 * into an HdrHistogram and prints the {@code p50 / p99 / p999 / p9999 / max} distribution plus
 * a machine-greppable {@code STALENESS-SAMPLE-HISTOGRAM:} line - the methodology section 4 reporting
 * form (HdrHistogram only; p50/p99/p999/p9999).
 *
 * <p>This is the reducer for the LIVE Compose multi-edge run: a fixed-cadence
 * edge sampler ({@code docker exec ... curl /metrics | awk '$1=="edge_staleness_ms"'},
 * methodology section 3c) emits one {@code edge_staleness_ms} gauge read per edge per tick; this main
 * folds those samples into the cumulative distribution. The gauge is {@code wall_now - frontier}
 * (per the staleness-measure spec), i.e. exactly the staleness invariant, read at the edge's own wall clock
 * on a cadence independent of the data-plane - so a stalled propagation surfaces as a growing
 * sample, never a dropped one (the section 3c coordinated-omission discipline).
 *
 * <p>An optional first argument is a {@code scope} label echoed on the summary line (e.g.
 * {@code edge1}, {@code all-edges}); a second optional argument is the sampling cadence in ms
 * (echoed only, for the record). Non-numeric lines are skipped (so a transient empty scrape is
 * ignored, not counted as 0). The histogram tracks 1 ms ... 10 min at 3 significant digits - the
 * same bounds as {@link PropagationProbe}.
 */
public final class StalenessSampleHistogram {

    private StalenessSampleHistogram() {
    }

    public static void main(String[] args) throws Exception {
        String scope = args.length >= 1 ? args[0] : "all-edges";
        String cadence = args.length >= 2 ? args[1] : "?";

        Histogram h = new Histogram(1, PropagationProbe.HIGHEST_TRACKABLE_MILLIS,
                PropagationProbe.SIGNIFICANT_DIGITS);
        long skipped = 0;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                long v;
                try {
                    // Tolerate a trailing ".0" from a gauge printed as a double.
                    int dot = line.indexOf('.');
                    v = Long.parseLong(dot >= 0 ? line.substring(0, dot) : line);
                } catch (NumberFormatException e) {
                    skipped++;
                    continue;
                }
                if (v < 0) {
                    v = 0;
                }
                if (v > PropagationProbe.HIGHEST_TRACKABLE_MILLIS) {
                    v = PropagationProbe.HIGHEST_TRACKABLE_MILLIS;
                }
                h.recordValue(v);
            }
        }

        long count = h.getTotalCount();
        if (count == 0) {
            System.out.println("STALENESS-SAMPLE-HISTOGRAM: scope=" + scope
                    + " cadence_ms=" + cadence + " count=0 (no samples)");
            return;
        }
        long p50 = h.getValueAtPercentile(50.0);
        long p90 = h.getValueAtPercentile(90.0);
        long p99 = h.getValueAtPercentile(99.0);
        long p999 = h.getValueAtPercentile(99.9);
        long p9999 = h.getValueAtPercentile(99.99);
        long max = h.getMaxValue();
        long cntAtP99 = h.getCountBetweenValues(p99, PropagationProbe.HIGHEST_TRACKABLE_MILLIS);
        long cntAtP999 = h.getCountBetweenValues(p999, PropagationProbe.HIGHEST_TRACKABLE_MILLIS);
        long cntAtP9999 = h.getCountBetweenValues(p9999, PropagationProbe.HIGHEST_TRACKABLE_MILLIS);

        System.out.println("=== live edge staleness (edge_staleness_ms gauge, ADR-0039) "
                + "scope=" + scope + " ===");
        System.out.println("  cadence = " + cadence + " ms (fixed wall-clock, methodology §3c)");
        System.out.println("  count   = " + count);
        System.out.println("  min     = " + h.getMinValue() + " ms");
        System.out.println("  p50     = " + p50 + " ms");
        System.out.println("  p90     = " + p90 + " ms");
        System.out.println("  p99     = " + p99 + " ms  (tail samples >= p99: " + cntAtP99 + ")");
        System.out.println("  p999    = " + p999 + " ms  (tail samples >= p999: " + cntAtP999 + ")");
        System.out.println("  p9999   = " + p9999 + " ms  (tail samples >= p9999: " + cntAtP9999 + ")");
        System.out.println("  max     = " + max + " ms");
        System.out.println("STALENESS-SAMPLE-HISTOGRAM: scope=" + scope
                + " cadence_ms=" + cadence + " count=" + count
                + " p50=" + p50 + " p99=" + p99 + " p999=" + p999 + " p9999=" + p9999
                + " max=" + max + " skipped=" + skipped + " unit=ms");
    }
}
