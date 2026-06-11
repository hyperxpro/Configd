package io.configd.testkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase V1 backlog (RR-001) — NOW GREEN under component C1. With
 * {@link StreamDriver#NONE} this test was the executable backlog (no fan-out service
 * existed, so nothing was ever delivered and it FAILED). It is re-enabled <em>verbatim</em>
 * with the real {@link C1StreamDriver} (= ADR-0034's consumer loop driving the production
 * {@link io.configd.distribution.fanout.FanOutSessionCore}): over a no-edge-fault schedule a
 * healthy edge data plane now (1) delivers every published commit notification within the
 * bound (contract §2 INV-S2) and (2) converges every live edge to the CP leader's
 * authoritative store (contract §1 INV-L1 / §4). The captured pre-implementation failure is
 * in {@code docs/session-3/captures/phase-v-backlog-failures.txt}; the now-green capture is
 * {@code docs/session-3/captures/c1-backlog-green.txt}.
 *
 * @see C1StreamDriver
 * @see EdgeInvariants
 */
class EdgePropagationBacklogTest {

    private static final int CP_NODES = 5;
    private static final int EDGES = 3;
    private static final int TICKS = 1_200;
    private static final long SEED = 4242L;

    /**
     * Over a no-edge-fault schedule, a healthy edge data plane must (1) deliver every
     * published commit notification within the bound and (2) converge every live
     * edge to the CP leader's authoritative store. With the C1 driver both now hold.
     */
    @Test
    void noFaultScheduleDeliversAndConverges() {
        EdgeFanOutSim sim = new EdgeFanOutSim(SEED, CP_NODES, EDGES, TICKS,
                /* edgeFaults */ false, new C1StreamDriver(),
                AdversarialSchedule.defaultIntensity(), EdgeInvariants.BOUND_MS);
        sim.run();

        // (1) Eventual delivery: zero recorded delivery-bound violations.
        EdgeActivity activity = sim.activity();
        assertEquals(0, activity.deliveryViolationCount(),
                "every published commit notification must be delivered to a"
                        + " live+connected+non-lagging edge within "
                        + EdgeInvariants.BOUND_MS + "ms (contract §2 INV-S2); recorded "
                        + activity.deliveryViolationCount()
                        + " violation(s)" + (activity.deliveryViolationsTruncated()
                        ? " (+truncated)" : "")
                        + ", maxLateness=" + activity.perEdgeMaxLatenessMs());

        // The edges must actually have received deltas (non-vacuity: a delivering
        // plane delivers something).
        assertTrue(activity.deliveredCount() > 0,
                "edges must have observed at least one notification (delivered="
                        + activity.deliveredCount() + ")");

        // (2) Convergence: every live edge byte-equals the CP leader after heal+drain.
        // finalCheck throws SimInvariants.SafetyViolation on divergence.
        sim.finalCheck();
    }
}
