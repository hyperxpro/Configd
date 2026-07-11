package io.configd.testkit;

import io.configd.edge.StalenessTracker;
import io.configd.probe.PropagationProbe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The p99 staleness distribution at SIM level.
 *
 * <p>This is the deterministic <b>mechanism number</b>: a {@link PropagationProbe} fed
 * publish->visible samples through the production fan-out path ({@link C1StreamDriver} /
 * {@link io.configd.edge.EdgeClientCore} staleness frontier) under a <b>sustained write
 * load</b> over a no-fault schedule, producing the HdrHistogram local fan-out staleness
 * distribution against leader-assigned commit timestamps.
 *
 * <p>Where the sibling {@link EdgeStalenessDistributionSimTest} deliberately sets NO p99
 * target (it asserts only that the mechanism produces a monotone distribution), this test
 * measures the actual p50/p99/p999/p9999 of the <em>local fan-out propagation component</em>
 * and asserts it against the delivery contract targets (p99 &lt; 500 ms, p9999 &lt; 2 s)
 * <b>for the local component only</b>.
 *
 * <h2>What this number is - and is not</h2>
 * This is the LOCAL fan-out component: commit-timestamp -> edge-apply propagation across the
 * production fan-out tree, in the deterministic sim's logical-millisecond time base (the sim
 * advances 1 ms/tick; the CP->edge network adds 1 - 10 ms/hop; the leader commit timestamp
 * carries the +/-50 ms NTP-skew error term the contract names). It is NOT the global target:
 * the global p99 &lt; 500 ms = this local fan-out component + the modeled WAN leg (1 - 3
 * Plumtree hops from the RTT matrix), which is environment-blocked and never marked verified
 * on local hardware.
 *
 * <h2>Coordinated omission</h2>
 * The write side is driven open-loop by the deterministic schedule (a fixed op count spread
 * uniformly across the tick window - an intended send time per op, not "issue-next-after-
 * previous"). The staleness sample for each delivered commit is {@code visibleTs - publishTs};
 * a stalled propagation shows up as a larger {@code visibleTs} (the edge apply tick), i.e. a
 * growing staleness sample, never a dropped one - the sim's logical clock does not pause when
 * delivery lags. So coordinated omission is structurally absent here for the same reason it is
 * absent for the read microbenchmarks: there is no fixed-cadence sampler whose skipped slots
 * vanish.
 *
 * <h2>Tail sample counts</h2>
 * The test prints the global p50/p99/p999/p9999 with the count of samples in/above each tail
 * bin, so a thin tail is visible as low-confidence rather than a silent headline.
 */
class EdgeStalenessDistributionLoadSimTest {

    private static final int CP_NODES = 3;
    private static final int EDGES = 3;

    /**
     * The run window in ticks (= ms of sim logical time). Sized so a leader elects, the
     * sustained write stream lands, and the edges apply every commit with margin.
     */
    private static final int TICKS = 12_000;

    /**
     * Sustained write count over the window. The schedule spreads ops uniformly across
     * {@code [TICKS/10, TICKS*0.95]} (~10.2 s of sim time); 60% are PUTs (the rest READ/
     * DELETE). 3000 ops -> ~1800 commits over ~10.2 s ~ 175 commits/s of sim logical time -
     * a sustained write load comparable to the box-bound ceiling (~125 - 172 commits/s), so
     * this is an honest "under load" propagation number, not a single-write microbench.
     */
    private static final int SUSTAINED_OPS = 3_000;

    /** Delivery targets - for the LOCAL component only. */
    private static final long P99_TARGET_MS = 500;
    private static final long P9999_TARGET_MS = 2_000;

    @Test
    void sustainedLoadProducesLocalFanOutStalenessDistribution() {
        EdgeFanOutSim sim = new EdgeFanOutSim(20250614L, CP_NODES, EDGES, TICKS,
                /*edgeFaults*/ false, new C1StreamDriver(),
                new AdversarialSchedule.Intensity(0, SUSTAINED_OPS, 0.0), EdgeInvariants.BOUND_MS);

        PropagationProbe probe = new PropagationProbe();
        sim.attachProbe(probe);
        sim.run();

        long count = probe.globalCount();
        // Non-vacuity: a sustained load must have produced a populated distribution. The tail
        // assertions below are only meaningful with enough samples behind p9999.
        assertTrue(count >= 1_000,
                "sustained load must produce a populated distribution (got " + count
                        + " samples; need >= 1000 for a credible p9999)");

        long p50 = probe.globalPercentile(50.0);
        long p99 = probe.globalPercentile(99.0);
        long p999 = probe.globalPercentile(99.9);
        long p9999 = probe.globalPercentile(99.99);
        long max = probe.globalMax();

        // Distribution well-formedness.
        assertTrue(p50 <= p99 && p99 <= p999 && p999 <= p9999 && p9999 <= max,
                "distribution must be monotone: p50=" + p50 + " p99=" + p99
                        + " p999=" + p999 + " p9999=" + p9999 + " max=" + max);

        // Tail sample counts: exact count of samples at/above each tail percentile
        // boundary - a p9999 backed by < ~100 tail samples is low-confidence.
        long cntAtP99 = probe.globalCountAtOrAbove(p99);
        long cntAtP999 = probe.globalCountAtOrAbove(p999);
        long cntAtP9999 = probe.globalCountAtOrAbove(p9999);

        // The delivery bound assertion - for the LOCAL fan-out component: p99 < 500 ms,
        // p9999 < 2 s. This is the local component; the global target adds the modeled WAN
        // leg and is environment-blocked. On this deterministic sim the local component is
        // tiny (network 1 - 10 ms/hop + skew), so it passes with vast margin - which is
        // exactly the finding: the LOCAL budget is not the constraint; the WAN leg is.
        assertTrue(p99 < P99_TARGET_MS,
                "LOCAL fan-out staleness p99 must be < " + P99_TARGET_MS + " ms (INV-S2 local"
                        + " component); measured p99=" + p99 + " ms over " + count + " samples");
        assertTrue(p9999 < P9999_TARGET_MS,
                "LOCAL fan-out staleness p9999 must be < " + P9999_TARGET_MS + " ms (INV-S2 local"
                        + " component); measured p9999=" + p9999 + " ms (tail samples="
                        + cntAtP9999 + ")");

        // A live, caught-up edge under a no-fault schedule must not end DISCONNECTED.
        for (EdgeActor edge : sim.edges()) {
            if (edge.alive() && edge.cursor() > 0) {
                assertTrue(edge.staleness() != StalenessTracker.State.DISCONNECTED,
                        "a live, caught-up edge under a no-fault schedule must not be"
                                + " DISCONNECTED (edge " + edge.edgeId()
                                + " staleness=" + edge.staleness() + ")");
            }
        }

        // Per-edge tail (the multi-edge fan-out: each edge is one fan-out leaf).
        StringBuilder perEdge = new StringBuilder();
        for (int id : probe.observerIds()) {
            perEdge.append("\n  edge-").append(id)
                    .append(": count=").append(probe.count(id))
                    .append(" p50=").append(probe.percentile(id, 50.0))
                    .append(" p99=").append(probe.percentile(id, 99.0))
                    .append(" p999=").append(probe.percentile(id, 99.9))
                    .append(" p9999=").append(probe.percentile(id, 99.99))
                    .append(" max=").append(probe.max(id))
                    .append(" ms");
        }

        // The greppable summary line for the report. NOT a perf gate - the report's
        // tables are filled from these numbers.
        System.out.println("STALENESS-LOAD-DIST: scope=local-fanout edges=" + EDGES
                + " cp=" + CP_NODES + " ops=" + SUSTAINED_OPS + " ticks=" + TICKS
                + " samples=" + count
                + " p50=" + p50 + "ms p99=" + p99 + "ms p999=" + p999 + "ms p9999=" + p9999
                + "ms max=" + max + "ms"
                + " tailCnt[>=p99]=" + cntAtP99 + " tailCnt[>=p999]=" + cntAtP999
                + " tailCnt[>=p9999]=" + cntAtP9999);
        System.out.println("STALENESS-LOAD-DIST per-edge:" + perEdge);
        System.out.println(probe.report());
    }
}
