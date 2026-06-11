package io.configd.testkit;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase V1 executable backlog (RR-001). With {@link StreamDriver#NONE} — the
 * honest current state, because no fan-out service exists in {@code src/main} — a
 * committed write at a CP node never reaches an edge, so:
 * <ul>
 *   <li>every published commit notification breaches the eventual-delivery bound
 *       (contract §2 INV-S2), recorded into {@link EdgeActivity}; and</li>
 *   <li>the live edges never converge to the CP leader's authoritative store
 *       (contract §1 INV-L1 / §4), so {@link EdgeFanOutSim#finalCheck()} throws.</li>
 * </ul>
 *
 * <p><b>This test FAILS today.</b> Its assertions are REAL and will be re-enabled
 * <em>verbatim</em> when component C1 (the fan-out/streaming service implementing
 * the {@link StreamDriver} contract = ADR-0034's consumer loop) lands — at which
 * point a no-fault schedule must deliver every notification within the bound and
 * every live edge must converge. The captured pre-implementation failure is in
 * {@code docs/session-3/captures/phase-v-backlog-failures.txt}.
 *
 * @see StreamDriver
 * @see EdgeInvariants
 */
@Disabled("S3-BACKLOG(C1): no fan-out service exists; see contract-test-map row CT-39"
        + " (eventual delivery + convergence, the V1 invariant set) — enable when C1 lands")
class EdgePropagationBacklogTest {

    private static final int CP_NODES = 5;
    private static final int EDGES = 3;
    private static final int TICKS = 1_200;
    private static final long SEED = 4242L;

    /**
     * Over a no-edge-fault schedule, a healthy edge data plane must (1) deliver every
     * published commit notification within the bound and (2) converge every live
     * edge to the CP leader's authoritative store. With {@link StreamDriver#NONE}
     * both fail — this is the backlog.
     */
    @Test
    void noFaultScheduleDeliversAndConverges() {
        EdgeFanOutSim sim = new EdgeFanOutSim(SEED, CP_NODES, EDGES, TICKS,
                /* edgeFaults */ false, StreamDriver.NONE,
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
