package io.configd.testkit;

import io.configd.edge.StalenessTracker;
import io.configd.probe.PropagationProbe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The staleness DISTRIBUTION machinery: a
 * {@link PropagationProbe} fed publish->visible samples over a no-fault schedule, plus the
 * staleness frontier on the edges, both driven through the real
 * {@link C1StreamDriver} / {@link io.configd.edge.EdgeClientCore}.
 *
 * <p><b>Split ownership:</b> this asserts the MECHANISM produces a
 * distribution (a non-vacuous histogram with sane percentiles); it deliberately sets NO p99
 * performance target - the real {@code p99 < 500ms} bound is for real
 * propagation latency. The point here is that the clock + probe + frontier wiring works
 * end-to-end through the production edge-client path, so there is a mechanism to measure.
 */
class EdgeStalenessDistributionSimTest {

    private static final int CP_NODES = 3;
    private static final int EDGES = 3;
    private static final int TICKS = 4_000;

    @Test
    void noFaultScheduleProducesAStalenessDistribution() {
        EdgeFanOutSim sim = new EdgeFanOutSim(2024L, CP_NODES, EDGES, TICKS,
                /*edgeFaults*/ false, new C1StreamDriver(),
                new AdversarialSchedule.Intensity(0, 120, 0.0), EdgeInvariants.BOUND_MS);

        PropagationProbe probe = new PropagationProbe();
        sim.attachProbe(probe);
        sim.run();

        // The probe MECHANISM must have produced a non-vacuous global distribution: every
        // delivered commit yields one publish->visible sample.
        assertTrue(probe.globalCount() > 0,
                "the probe must have recorded propagation samples (mechanism non-vacuity)");

        // Sane percentiles: the distribution is well-formed (p50 <= p99 <= max). The probe
        // floors negative staleness to 0, so samples are non-negative by construction.
        long p50 = probe.globalPercentile(50.0);
        long p99 = probe.globalPercentile(99.0);
        long max = probe.globalMax();
        assertTrue(p50 <= p99 && p99 <= max,
                "distribution must be monotone: p50=" + p50 + " p99=" + p99 + " max=" + max);

        // The staleness frontier on the edges is sane: with heartbeats flowing under
        // a no-fault schedule, the edges are not stuck DISCONNECTED at end of run.
        for (EdgeActor edge : sim.edges()) {
            if (edge.alive() && edge.cursor() > 0) {
                assertTrue(edge.staleness() != StalenessTracker.State.DISCONNECTED,
                        "a live, caught-up edge under a no-fault schedule must not be"
                                + " DISCONNECTED (edge " + edge.edgeId()
                                + " staleness=" + edge.staleness() + ")");
            }
        }

        // Greppable mechanism summary for the report (no assertion on the values themselves).
        System.out.println("STALENESS-DIST-SUMMARY: samples=" + probe.globalCount()
                + " p50=" + p50 + "ms p99=" + p99 + "ms max=" + max + "ms");
    }
}
