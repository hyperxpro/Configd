package io.configd.distribution.fanout;

import io.configd.distribution.fanout.SlowConsumerGovernor.ConsumerState;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernorBoundedIdentityMapChurnTest {

    private static final long T = 1_000L;

    private static SlowConsumerPolicyConfig tinyBound(int maxTracked) {
        return new SlowConsumerPolicyConfig(
                10_000L,
                3,
                10,
                60_000L,
                60_000L,
                3,
                3_600_000L,
                3_600_000L,
                maxTracked);
    }

    private static void driveToQuarantine(SlowConsumerGovernor gov, String id) {
        for (int k = 0; k < 3; k++) {
            gov.onDemotion(id, new DemotionEvent(100 + k, 0, DemotionEvent.REASON_ACK_LAG), T);
        }
        assertEquals(ConsumerState.QUARANTINED, gov.state(id), "fixture: " + id + " must be quarantined");
    }

    private static void trackHealthy(SlowConsumerGovernor gov, String id) {
        gov.onQueuePressure(id, true, -1, -1, T);
    }

    @Test
    void churnNeverEvictsDistressedAndBoundsHealthyGrowth() {
        int bound = 8;
        SlowConsumerGovernor gov = new SlowConsumerGovernor(tinyBound(bound), FanOutSessionMetrics.NOOP);

        int k = 3;
        for (int i = 0; i < k; i++) {
            driveToQuarantine(gov, "q" + i);
        }
        assertEquals(k, gov.trackedIdentities(), "only the 3 quarantined are tracked so far");

        for (int i = 0; i < 5_000; i++) {
            trackHealthy(gov, "churn-" + i);
        }

        for (int i = 0; i < k; i++) {
            assertEquals(ConsumerState.QUARANTINED, gov.state("q" + i),
                    "q" + i + " must NOT be evicted by HEALTHY churn — evicting a distressed record "
                            + "is a policy escape (cooldown skipped on re-admit)");
        }
        assertEquals(bound, gov.trackedIdentities(),
                "tracked identities must stay bounded under unbounded healthy churn");

        assertEquals(ConsumerState.HEALTHY, gov.state("churn-0"),
                "an evicted HEALTHY identity is forgotten and returns fresh");
    }

    @Test
    void allDistressedOverflowsHonestlyWithoutEvictingPolicyState() {
        // The documented honest overflow: when EVERY tracked identity is distressed there is no
        // HEALTHY victim, so the map exceeds the bound rather than forget a quarantine. The
        // overflow is bounded by the count of genuinely distressed identities - never unbounded,
        // and never at the cost of a lost policy state.
        int bound = 4;
        SlowConsumerGovernor gov = new SlowConsumerGovernor(tinyBound(bound), FanOutSessionMetrics.NOOP);

        int distressed = bound + 3;
        for (int i = 0; i < distressed; i++) {
            driveToQuarantine(gov, "q" + i);
        }

        for (int i = 0; i < distressed; i++) {
            assertEquals(ConsumerState.QUARANTINED, gov.state("q" + i),
                    "no quarantined identity may be dropped to satisfy maxTrackedIdentities");
        }
        assertEquals(distressed, gov.trackedIdentities(),
                "honest overflow: tracked == distressed count (bounded by real distinct distressed certs)");
        assertTrue(gov.trackedIdentities() > bound,
                "this cell exercises the > bound overflow path (safety beats the bound)");

        trackHealthy(gov, "newcomer");
        assertEquals(ConsumerState.HEALTHY, gov.state("newcomer"));
        assertEquals(distressed + 1, gov.trackedIdentities());
        for (int i = 0; i < distressed; i++) {
            assertEquals(ConsumerState.QUARANTINED, gov.state("q" + i));
        }
    }
}
