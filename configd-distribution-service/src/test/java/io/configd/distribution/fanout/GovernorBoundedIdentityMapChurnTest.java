package io.configd.distribution.fanout;

import io.configd.distribution.fanout.SlowConsumerGovernor.ConsumerState;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session 4 / Workstream A3 leg 4 (S3 handoff §1): long-running governor churn under
 * adversarial identity counts. The C4 review reasoned the bounded identity map is safe but
 * never load-tested it. These pin the two safety properties of {@code maxTrackedIdentities}
 * eviction under churn ≫ the bound:
 * <ol>
 *   <li><b>Distressed records are never evicted</b> — forgetting a QUARANTINED/UNHEALTHY
 *       identity would be a policy escape (a flapping edge re-admitted as fresh, skipping its
 *       cooldown). {@link SlowConsumerGovernor#evictIfAtBound} must skip them and evict only
 *       the least-recently-touched HEALTHY record.</li>
 *   <li><b>Bounded memory</b> — HEALTHY identities are capped at the bound under unbounded
 *       churn; the map exceeds the bound ONLY by the count of simultaneously distressed
 *       identities (the documented honest overflow), never unbounded.</li>
 * </ol>
 * fault-matrix §A3-4. Mutation M-evict (evict regardless of state) is captured in EXP-005.
 */
class GovernorBoundedIdentityMapChurnTest {

    private static final long T = 1_000L;

    /** A tiny tracking bound so churn forces eviction; demoteLimit=3 (default) → quarantine. */
    private static SlowConsumerPolicyConfig tinyBound(int maxTracked) {
        return new SlowConsumerPolicyConfig(
                10_000L, // queueWarnWindowMs (never elapses at fixed T → churn stays HEALTHY)
                3,       // demoteLimit  → 3 ack-lag demotions quarantine
                10,      // gapDemoteLimit
                60_000L, // demoteWindowMs
                60_000L, // quarantineCooldownMs
                3,       // quarantineLimit
                3_600_000L, // unhealthyWindowMs
                3_600_000L, // unhealthyCooldownMs
                maxTracked);
    }

    private static void driveToQuarantine(SlowConsumerGovernor gov, String id) {
        for (int k = 0; k < 3; k++) { // demoteLimit
            gov.onDemotion(id, new DemotionEvent(100 + k, 0, DemotionEvent.REASON_ACK_LAG), T);
        }
        assertEquals(ConsumerState.QUARANTINED, gov.state(id), "fixture: " + id + " must be quarantined");
    }

    /** Track a fresh HEALTHY identity via a single queue-warn signal (warn window never elapses). */
    private static void trackHealthy(SlowConsumerGovernor gov, String id) {
        gov.onQueuePressure(id, true, -1, -1, T);
    }

    @Test
    void churnNeverEvictsDistressedAndBoundsHealthyGrowth() {
        int bound = 8;
        SlowConsumerGovernor gov = new SlowConsumerGovernor(tinyBound(bound), FanOutSessionMetrics.NOOP);

        // Quarantine K=3 identities (< bound). These are distressed and must be immune to eviction.
        int k = 3;
        for (int i = 0; i < k; i++) {
            driveToQuarantine(gov, "q" + i);
        }
        assertEquals(k, gov.trackedIdentities(), "only the 3 quarantined are tracked so far");

        // Adversarial churn: thousands of DISTINCT healthy identities signal once each. Each
        // records a fresh HEALTHY entry; once the bound is reached every new one must evict the
        // least-recently-touched HEALTHY — never one of the quarantined records (which sit at the
        // access-order head, untouched since quarantine).
        for (int i = 0; i < 5_000; i++) {
            trackHealthy(gov, "churn-" + i);
        }

        // (1) Every quarantined identity survived the churn — never evicted, still QUARANTINED.
        for (int i = 0; i < k; i++) {
            assertEquals(ConsumerState.QUARANTINED, gov.state("q" + i),
                    "q" + i + " must NOT be evicted by HEALTHY churn — evicting a distressed record "
                            + "is a policy escape (cooldown skipped on re-admit)");
        }
        // (2) Bounded: map = k quarantined + (bound-k) healthy working set = exactly the bound.
        assertEquals(bound, gov.trackedIdentities(),
                "tracked identities must stay bounded under unbounded healthy churn");

        // (3) Re-entry after eviction is fresh: an evicted early-churn identity returns HEALTHY
        // (no stale state) — harmless for HEALTHY, and the symmetric proof that we evicted
        // healthy (not distressed) records.
        assertEquals(ConsumerState.HEALTHY, gov.state("churn-0"),
                "an evicted HEALTHY identity is forgotten and returns fresh");
    }

    @Test
    void allDistressedOverflowsHonestlyWithoutEvictingPolicyState() {
        // The documented honest overflow: when EVERY tracked identity is distressed there is no
        // HEALTHY victim, so the map exceeds the bound rather than forget a quarantine. The
        // overflow is bounded by the count of genuinely distressed identities — never unbounded,
        // and never at the cost of a lost policy state.
        int bound = 4;
        SlowConsumerGovernor gov = new SlowConsumerGovernor(tinyBound(bound), FanOutSessionMetrics.NOOP);

        int distressed = bound + 3; // 7 > bound 4
        for (int i = 0; i < distressed; i++) {
            driveToQuarantine(gov, "q" + i);
        }

        // All distressed identities are retained (none evicted to honor the bound) ...
        for (int i = 0; i < distressed; i++) {
            assertEquals(ConsumerState.QUARANTINED, gov.state("q" + i),
                    "no quarantined identity may be dropped to satisfy maxTrackedIdentities");
        }
        // ... so the map honestly exceeds the bound by exactly the distressed overflow.
        assertEquals(distressed, gov.trackedIdentities(),
                "honest overflow: tracked == distressed count (bounded by real distinct distressed certs)");
        assertTrue(gov.trackedIdentities() > bound,
                "this cell exercises the > bound overflow path (safety beats the bound)");

        // A fresh HEALTHY identity arriving now still cannot evict any distressed record (no
        // HEALTHY victim exists) — it is simply added; the overflow does not corrupt accounting.
        trackHealthy(gov, "newcomer");
        assertEquals(ConsumerState.HEALTHY, gov.state("newcomer"));
        assertEquals(distressed + 1, gov.trackedIdentities());
        for (int i = 0; i < distressed; i++) {
            assertEquals(ConsumerState.QUARANTINED, gov.state("q" + i));
        }
    }
}
