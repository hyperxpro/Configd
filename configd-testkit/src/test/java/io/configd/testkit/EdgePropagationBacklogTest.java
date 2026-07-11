package io.configd.testkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The propagation backlog test, driven by the real {@link C1StreamDriver} (the handoff
 * spec's consumer loop driving the production
 * {@link io.configd.distribution.fanout.FanOutSessionCore}): over a no-edge-fault schedule a
 * healthy edge data plane (1) delivers every published commit notification within the bound
 * (the delivery invariant) and (2) converges every live edge to the CP leader's authoritative
 * store. With {@link StreamDriver#NONE} nothing is ever delivered, since no fan-out service
 * exists to drive it; the captured before/after runs are
 * {@code docs/session-3/captures/phase-v-backlog-failures.txt} and
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
     * edge to the CP leader's authoritative store. With the real stream driver, both hold.
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
