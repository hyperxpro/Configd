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
 * exists to drive it.
 *
 * @see C1StreamDriver
 * @see EdgeInvariants
 */
class EdgePropagationBacklogTest {

    private static final int CP_NODES = 5;
    private static final int EDGES = 3;
    private static final int TICKS = 1_200;
    private static final long SEED = 4242L;

    @Test
    void noFaultScheduleDeliversAndConverges() {
        EdgeFanOutSim sim = new EdgeFanOutSim(SEED, CP_NODES, EDGES, TICKS,
                /* edgeFaults */ false, new C1StreamDriver(),
                AdversarialSchedule.defaultIntensity(), EdgeInvariants.BOUND_MS);
        sim.run();

        EdgeActivity activity = sim.activity();
        assertEquals(0, activity.deliveryViolationCount(),
                "every published commit notification must be delivered to a"
                        + " live+connected+non-lagging edge within "
                        + EdgeInvariants.BOUND_MS + "ms (contract §2 INV-S2); recorded "
                        + activity.deliveryViolationCount()
                        + " violation(s)" + (activity.deliveryViolationsTruncated()
                        ? " (+truncated)" : "")
                        + ", maxLateness=" + activity.perEdgeMaxLatenessMs());

        assertTrue(activity.deliveredCount() > 0,
                "edges must have observed at least one notification (delivered="
                        + activity.deliveredCount() + ")");

        // (2) Convergence: every live edge byte-equals the CP leader after heal+drain.
        // finalCheck throws SimInvariants.SafetyViolation on divergence.
        sim.finalCheck();
    }
}
