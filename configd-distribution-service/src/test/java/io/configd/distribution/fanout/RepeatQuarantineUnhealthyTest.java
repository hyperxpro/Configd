package io.configd.distribution.fanout;

import io.configd.distribution.fanout.SlowConsumerGovernor.Admission;
import io.configd.distribution.fanout.SlowConsumerGovernor.AdmissionDecision;
import io.configd.distribution.fanout.SlowConsumerGovernor.ConsumerState;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CT-30 — architecture §7 ":291 3 quarantines in 1 hour → Marked as unhealthy, removed
 * from distribution tree" (C4 design §2): the {@code quarantineLimit}-th quarantine
 * within {@code unhealthyWindowMs} escalates to UNHEALTHY (alert-grade
 * {@code edge_fanout_unhealthy_total}); SUBSCRIBEs are refused for
 * {@code unhealthyCooldownMs}, after which the identity is AUTOMATICALLY readmitted with
 * the snapshot-first re-bootstrap forced — the cooldown alone is a sufficient exit
 * (screen condition C4-3: UNHEALTHY is never a permanent lockout; operator reset is
 * additional). Clock-driven (explicit nowMillis), no sleeps — the evidence discipline
 * the CT-30 row demands.
 */
class RepeatQuarantineUnhealthyTest {

    private static final String EDGE = "CN=edge-4,O=configd";
    private static final long T0 = 1_700_000_000_000L;
    private static final long HOUR = 3_600_000L;

    private static SlowConsumerPolicyConfig config() {
        // Defaults: demoteLimit 3 / 60 s window; quarantineLimit 3 / 1 h window;
        // quarantine cooldown 60 s; unhealthy cooldown 1 h.
        return SlowConsumerPolicyConfig.defaults();
    }

    /** Drives one full quarantine cycle starting at {@code at}: 3 demotions + readmission. */
    private static long quarantineCycle(SlowConsumerGovernor governor, long at) {
        for (int i = 0; i < 3; i++) {
            governor.onDemotion(EDGE,
                    new DemotionEvent(100 + i, 90, DemotionEvent.REASON_ACK_LAG),
                    at + i * 1_000L);
        }
        return at + 2_000; // the quarantine timestamp (the 3rd demotion)
    }

    @Test
    void thirdQuarantineWithinTheHourEscalatesToUnhealthyWithAlertMetric() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);

        // Quarantine #1 at ~T0; readmit after its 60 s cooldown.
        long q1 = quarantineCycle(governor, T0);
        assertEquals(ConsumerState.QUARANTINED, governor.state(EDGE));
        governor.admit(EDGE, q1 + 60_000);
        assertEquals(ConsumerState.CATCHUP, governor.state(EDGE));

        // Quarantine #2; readmit again.
        long q2 = quarantineCycle(governor, q1 + 120_000);
        assertEquals(ConsumerState.QUARANTINED, governor.state(EDGE));
        assertEquals(2, probe.quarantines);
        assertEquals(0, probe.unhealthy, "two quarantines in the hour are not yet UNHEALTHY");
        governor.admit(EDGE, q2 + 60_000);

        // Quarantine #3 inside the same hour: UNHEALTHY, alert-grade metric.
        quarantineCycle(governor, q2 + 120_000);
        assertEquals(ConsumerState.UNHEALTHY, governor.state(EDGE));
        assertEquals(3, probe.quarantines, "the escalating event still counts as a quarantine");
        assertEquals(1, probe.unhealthy, "edge_fanout_unhealthy_total must move exactly once");
        SlowConsumerGovernor.TransitionEvent event = probe.lastTransition();
        assertEquals(ConsumerState.UNHEALTHY, event.to());
        assertEquals(SlowConsumerGovernor.REASON_REPEAT_QUARANTINE, event.reason());
        assertEquals(3, event.quarantinesInWindow());
        assertEquals(1, probe.lastUnhealthy, "the consumer_state gauge counts the identity");
    }

    @Test
    void quarantinesSpreadBeyondTheHourDoNotEscalate() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);

        long q1 = quarantineCycle(governor, T0);
        governor.admit(EDGE, q1 + 60_000);
        long q2 = quarantineCycle(governor, q1 + 30 * 60_000L); // +30 min
        governor.admit(EDGE, q2 + 60_000);

        // The 3rd quarantine lands > 1 h after the 1st: the sliding window holds only
        // two — QUARANTINED again, not UNHEALTHY.
        quarantineCycle(governor, q1 + HOUR + 60_000L);
        assertEquals(ConsumerState.QUARANTINED, governor.state(EDGE),
                "the unhealthy window is sliding — a stale quarantine must not count");
        assertEquals(0, probe.unhealthy);
        assertEquals(3, probe.quarantines);
    }

    @Test
    void unhealthyIsRefusedDuringItsCooldownAndAutoReadmittedAfter() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);
        long q1 = quarantineCycle(governor, T0);
        governor.admit(EDGE, q1 + 60_000);
        long q2 = quarantineCycle(governor, q1 + 120_000);
        governor.admit(EDGE, q2 + 60_000);
        long q3 = quarantineCycle(governor, q2 + 120_000);
        assertEquals(ConsumerState.UNHEALTHY, governor.state(EDGE), "fixture");

        // Refused throughout the 1 h unhealthy cooldown — and OBSERVABLY so (C4-3).
        Admission during = governor.admit(EDGE, q3 + 30 * 60_000L);
        assertEquals(AdmissionDecision.REFUSE, during.decision());
        assertEquals(ConsumerState.UNHEALTHY, during.state());
        assertEquals(30 * 60_000L, during.cooldownRemainingMs());
        assertEquals(AdmissionDecision.REFUSE, governor.admit(EDGE, q3 + HOUR - 1).decision());
        assertEquals(2, probe.reconnectsRefused);

        // The cooldown ALONE readmits — no operator action required (the C4-3
        // anti-permanent-lockout condition): forced snapshot-first re-bootstrap.
        Admission readmitted = governor.admit(EDGE, q3 + HOUR);
        assertEquals(AdmissionDecision.ALLOW_FORCE_SNAPSHOT, readmitted.decision());
        assertEquals(3, probe.readmissions,
                "the two quarantine-cooldown readmissions plus this unhealthy-cooldown one");
        assertEquals(ConsumerState.CATCHUP, governor.state(EDGE));
        assertEquals(SlowConsumerGovernor.REASON_READMITTED_UNHEALTHY,
                probe.lastTransition().reason());

        // And because the cooldown (1 h) equals the unhealthy window (1 h), the old
        // quarantine timestamps age out: the readmitted identity starts the ladder clean.
        governor.onAckProgress(EDGE, 300, 300, q3 + HOUR + 1_000);
        assertEquals(ConsumerState.HEALTHY, governor.state(EDGE));
        long q4 = quarantineCycle(governor, q3 + HOUR + 10_000);
        assertEquals(ConsumerState.QUARANTINED, governor.state(EDGE),
                "the first post-cooldown quarantine must NOT re-escalate to UNHEALTHY");
        assertEquals(1, probe.unhealthy);
        assertEquals(q4, probe.lastTransition().atMillis());
    }

    @Test
    void operatorResetClearsUnhealthyImmediately() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);
        long q1 = quarantineCycle(governor, T0);
        governor.admit(EDGE, q1 + 60_000);
        long q2 = quarantineCycle(governor, q1 + 120_000);
        governor.admit(EDGE, q2 + 60_000);
        quarantineCycle(governor, q2 + 120_000);
        assertEquals(ConsumerState.UNHEALTHY, governor.state(EDGE), "fixture");

        governor.operatorReset(EDGE, q2 + 130_000);
        assertEquals(ConsumerState.HEALTHY, governor.state(EDGE));
        assertEquals(AdmissionDecision.ALLOW, governor.admit(EDGE, q2 + 131_000).decision(),
                "operator reset (the ADDITIONAL exit) admits without the cooldown");
    }

    @Test
    void trackedIdentityMapIsBoundedAndNeverEvictsADistressedIdentity() {
        // Hard rule 4: the per-identity map is bounded; only HEALTHY identities are
        // evicted (forgetting a quarantine would be a policy escape) — and a distressed
        // record at the access-order head must not dam up eviction of healthy ones.
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerPolicyConfig tiny = new SlowConsumerPolicyConfig(
                10_000L, 3, 10, 60_000L, 60_000L, 3, HOUR, HOUR, 2);
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(tiny, probe, probe::onTransition);

        // Quarantine EDGE (3 distress demotions), then flood with healthy identities.
        quarantineCycle(governor, T0);
        assertEquals(ConsumerState.QUARANTINED, governor.state(EDGE));
        for (int i = 0; i < 10; i++) {
            governor.onQueuePressure("healthy-" + i, true, 1, 1, T0 + 10_000 + i);
        }
        assertEquals(ConsumerState.QUARANTINED, governor.state(EDGE),
                "the quarantined identity must survive eviction pressure");
        assertEquals(AdmissionDecision.REFUSE, governor.admit(EDGE, T0 + 11_000).decision(),
                "...and its refusal must still be enforced");
        // The bound is ENFORCED, skipping past the unevictable quarantined head: the
        // quarantined identity + exactly one (the newest) healthy survivor.
        assertEquals(2, governor.trackedIdentities(),
                "eviction must hold the map at the bound despite the distressed head");
        assertEquals(1, probe.lastHealthy, "the gauge tracks the surviving healthy identity");
        assertEquals(1, probe.lastQuarantined);
    }

    @Test
    void aStragglerDemotionAgainstAnUnhealthyIdentityIsInert() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);
        long q1 = quarantineCycle(governor, T0);
        governor.admit(EDGE, q1 + 60_000);
        long q2 = quarantineCycle(governor, q1 + 120_000);
        governor.admit(EDGE, q2 + 60_000);
        quarantineCycle(governor, q2 + 120_000);
        assertEquals(ConsumerState.UNHEALTHY, governor.state(EDGE), "fixture");
        int transitionsAtUnhealthy = probe.transitions.size();

        governor.onDemotion(EDGE,
                new DemotionEvent(500, 400, DemotionEvent.REASON_ACK_LAG), q2 + 125_000);
        assertEquals(ConsumerState.UNHEALTHY, governor.state(EDGE),
                "a dying session's straggler demotion must not perturb UNHEALTHY");
        assertEquals(transitionsAtUnhealthy, probe.transitions.size());
        assertEquals(3, probe.quarantines, "no extra quarantine may be counted");
    }
}
