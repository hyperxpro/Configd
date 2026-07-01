package io.configd.distribution.fanout;

import io.configd.distribution.fanout.SlowConsumerGovernor.ConsumerState;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code demoteLimit} distress demotions within {@code demoteWindowMs} transition the
 * identity to QUARANTINED; the caller disconnects with {@code ErrorCode.QUARANTINED}
 * (wire code 8). The metric {@code edge_fanout_quarantines_total} fires and the structured
 * event carries the cursor evidence.
 *
 * <p>Also pins the reason weighting: GAP demotions (network/eviction artifacts) are
 * counted separately at the higher {@code gapDemoteLimit}, so a lossy-WAN edge that gaps
 * and heals repeatedly does not walk to QUARANTINED - while a genuine gap loop still trips
 * the backstop.
 */
class SlowConsumerQuarantineTransitionTest {

    private static final String EDGE = "CN=edge-2,O=configd";
    private static final long T0 = 1_700_000_000_000L;

    /** demoteLimit=3, gapDemoteLimit=5, demoteWindowMs=60_000 (test-scaled gap backstop). */
    private static SlowConsumerPolicyConfig config() {
        return new SlowConsumerPolicyConfig(
                10_000L, 3, 5, 60_000L, 60_000L, 3, 3_600_000L, 3_600_000L, 4_096);
    }

    private static DemotionEvent distress(long cursor, long acked) {
        return new DemotionEvent(cursor, acked, DemotionEvent.REASON_ACK_LAG);
    }

    private static DemotionEvent gap(long cursor, long acked) {
        return new DemotionEvent(cursor, acked, DemotionEvent.REASON_GAP);
    }

    @Test
    void demoteLimitDistressDemotionsWithinTheWindowQuarantineWithCursorEvidence() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);

        assertEquals(ConsumerState.CATCHUP,
                governor.onDemotion(EDGE, distress(100, 90), T0));
        assertEquals(ConsumerState.CATCHUP,
                governor.onDemotion(EDGE, distress(110, 90), T0 + 10_000));
        assertEquals(0, probe.quarantines, "two demotions must not quarantine");

        // The third distress demotion inside the 60 s window trips the limit.
        assertEquals(ConsumerState.QUARANTINED,
                governor.onDemotion(EDGE, distress(120, 90), T0 + 20_000));
        assertEquals(1, probe.quarantines,
                "edge_fanout_quarantines_total must move exactly once");
        SlowConsumerGovernor.TransitionEvent event = probe.lastTransition();
        assertEquals(ConsumerState.CATCHUP, event.from());
        assertEquals(ConsumerState.QUARANTINED, event.to());
        assertEquals(SlowConsumerGovernor.REASON_DEMOTE_LIMIT, event.reason());
        assertEquals(120, event.cursor(), "the cursor evidence rides the transition event");
        assertEquals(90, event.lastAckedSeq());
        assertEquals(1, probe.lastQuarantined, "consumer_state gauge counts the quarantine");
        assertEquals(0, probe.unhealthy, "a first quarantine is not UNHEALTHY");
    }

    @Test
    void demotionsSpreadBeyondTheWindowDoNotQuarantine() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);

        governor.onDemotion(EDGE, distress(10, 5), T0);
        governor.onDemotion(EDGE, distress(20, 5), T0 + 30_000);
        // The first demotion (T0) has aged out of the 60 s window by T0 + 61_000: the
        // sliding window holds only two distress demotions - under the limit.
        assertEquals(ConsumerState.CATCHUP,
                governor.onDemotion(EDGE, distress(30, 5), T0 + 61_000));
        assertEquals(0, probe.quarantines,
                "the demotion window is sliding — stale demotions must not count");

        // But a third inside the window does trip it.
        assertEquals(ConsumerState.QUARANTINED,
                governor.onDemotion(EDGE, distress(40, 5), T0 + 70_000));
        assertEquals(1, probe.quarantines);
    }

    @Test
    void gapDemotionsAreWeightedSeparatelyAndDoNotTripTheDistressLimit() {
        // Screen C4-2/C4-3: a healthy edge flapping on a lossy network (GAP demotions)
        // must not be quarantined at the distress limit.
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);

        for (int i = 0; i < 4; i++) { // 4 GAPs > demoteLimit (3), < gapDemoteLimit (5)
            assertEquals(ConsumerState.CATCHUP,
                    governor.onDemotion(EDGE, gap(50 + i, 40), T0 + i * 1_000L));
        }
        assertEquals(0, probe.quarantines,
                "GAP demotions beyond demoteLimit must NOT quarantine (reason weighting)");

        // ... and the recovered edge goes back to HEALTHY on ack progress.
        governor.onAckProgress(EDGE, 60, 60, T0 + 10_000);
        assertEquals(ConsumerState.HEALTHY, governor.state(EDGE));
    }

    @Test
    void aGenuineGapLoopStillTripsTheGapBackstop() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);

        for (int i = 0; i < 4; i++) {
            governor.onDemotion(EDGE, gap(50 + i, 40), T0 + i * 1_000L);
        }
        assertEquals(ConsumerState.QUARANTINED,
                governor.onDemotion(EDGE, gap(60, 40), T0 + 5_000),
                "the 5th GAP within the window is a genuine gap loop — backstop trips");
        assertEquals(SlowConsumerGovernor.REASON_GAP_DEMOTE_LIMIT,
                probe.lastTransition().reason());
        assertEquals(1, probe.quarantines);
    }

    @Test
    void mixedReasonsCountOnTheirOwnLadders() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);

        // 2 distress + 2 gap: neither ladder at its limit - no quarantine.
        governor.onDemotion(EDGE, distress(10, 5), T0);
        governor.onDemotion(EDGE, gap(11, 5), T0 + 1_000);
        governor.onDemotion(EDGE, distress(12, 5), T0 + 2_000);
        governor.onDemotion(EDGE, gap(13, 5), T0 + 3_000);
        assertEquals(0, probe.quarantines,
                "2 distress + 2 gap must not trip either separately-counted limit");
        assertEquals(ConsumerState.CATCHUP, governor.state(EDGE));

        // The 3rd DISTRESS trips its ladder regardless of the gap count.
        assertEquals(ConsumerState.QUARANTINED,
                governor.onDemotion(EDGE, distress(14, 5), T0 + 4_000));
        SlowConsumerGovernor.TransitionEvent event = probe.lastTransition();
        assertEquals(SlowConsumerGovernor.REASON_DEMOTE_LIMIT, event.reason());
        assertEquals(3, event.distressDemotionsInWindow());
        assertEquals(2, event.gapDemotionsInWindow());
    }

    @Test
    void demotionsAfterQuarantineDoNotDoubleCount() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);
        for (int i = 0; i < 3; i++) {
            governor.onDemotion(EDGE, distress(10 + i, 5), T0 + i * 1_000L);
        }
        assertEquals(ConsumerState.QUARANTINED, governor.state(EDGE));
        int transitionsAtQuarantine = probe.transitions.size();

        // A straggler demotion from the dying session must be inert.
        assertEquals(ConsumerState.QUARANTINED,
                governor.onDemotion(EDGE, distress(14, 5), T0 + 3_500));
        assertEquals(1, probe.quarantines);
        assertEquals(transitionsAtQuarantine, probe.transitions.size(),
                "no transition may fire for a post-quarantine straggler demotion");
    }

    @Test
    void theDemoteWindowIsInclusiveAtItsExactEdge() {
        // "within demoteWindowMs" is INCLUSIVE: a demotion exactly demoteWindowMs old
        // still counts (the window prunes strictly-older entries only).
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);
        governor.onDemotion(EDGE, distress(10, 5), T0);
        governor.onDemotion(EDGE, distress(20, 5), T0 + 60_000); // exactly the window edge
        assertEquals(ConsumerState.QUARANTINED,
                governor.onDemotion(EDGE, distress(30, 5), T0 + 60_000),
                "a demotion exactly demoteWindowMs old is still inside the window");
        assertEquals(1, probe.quarantines);
    }

    @Test
    void firstDemotionTransitionsToCatchupWithTheDemotionReason() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);
        governor.onDemotion(EDGE, new DemotionEvent(7, 3, DemotionEvent.REASON_QUEUE_OVERFLOW), T0);
        SlowConsumerGovernor.TransitionEvent event = probe.lastTransition();
        assertEquals(ConsumerState.HEALTHY, event.from());
        assertEquals(ConsumerState.CATCHUP, event.to());
        assertEquals(DemotionEvent.REASON_QUEUE_OVERFLOW, event.reason());
        assertEquals(7, event.cursor());
        assertEquals(3, event.lastAckedSeq());
        assertTrue(probe.lastCatchup == 1 && probe.lastHealthy == 0,
                "the gauge tallies must follow the transition");
    }
}
