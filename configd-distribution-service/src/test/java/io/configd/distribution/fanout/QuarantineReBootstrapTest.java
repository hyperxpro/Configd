package io.configd.distribution.fanout;

import io.configd.distribution.fanout.SlowConsumerGovernor.Admission;
import io.configd.distribution.fanout.SlowConsumerGovernor.AdmissionDecision;
import io.configd.distribution.fanout.SlowConsumerGovernor.ConsumerState;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SUBSCRIBEs from a QUARANTINED identity are REFUSED for {@code quarantineCooldownMs}
 * (each refusal counted on {@code edge_fanout_reconnects_refused_total}); after the cooldown
 * the identity is readmitted with {@code ALLOW_FORCE_SNAPSHOT} - the caller rebinds the
 * resume cursor to 0 so the cursor-0 rule in decideMode forces the snapshot re-bootstrap.
 * Operator reset is an ADDITIONAL exit, never the only one. Clock-driven, no sleeps.
 */
class QuarantineReBootstrapTest {

    private static final String EDGE = "CN=edge-3,O=configd";
    private static final long T0 = 1_700_000_000_000L;

    private static SlowConsumerPolicyConfig config() {
        return SlowConsumerPolicyConfig.defaults(); // quarantineCooldownMs = 60_000
    }

    private static SlowConsumerGovernor quarantined(RecordingPolicyProbe probe) {
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);
        for (int i = 0; i < 3; i++) {
            governor.onDemotion(EDGE,
                    new DemotionEvent(100 + i, 90, DemotionEvent.REASON_ACK_LAG),
                    T0 + i * 1_000L);
        }
        assertEquals(ConsumerState.QUARANTINED, governor.state(EDGE), "fixture");
        return governor;
    }

    @Test
    void subscribeDuringTheCooldownIsRefusedAndCounted() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor = quarantined(probe);
        long quarantinedAt = T0 + 2_000; // the 3rd demotion's timestamp

        Admission first = governor.admit(EDGE, quarantinedAt + 1_000);
        assertEquals(AdmissionDecision.REFUSE, first.decision());
        assertEquals(ConsumerState.QUARANTINED, first.state());
        assertEquals(59_000, first.cooldownRemainingMs(),
                "the refusal must report the remaining cooldown");

        // One millisecond before expiry: still refused (boundary exactness).
        Admission last = governor.admit(EDGE, quarantinedAt + 59_999);
        assertEquals(AdmissionDecision.REFUSE, last.decision());

        assertEquals(2, probe.reconnectsRefused,
                "edge_fanout_reconnects_refused_total must count EVERY refusal "
                        + "(a flapping edge in cooldown is observable, C4-3)");
        assertEquals(ConsumerState.QUARANTINED, governor.state(EDGE),
                "a refusal must not mutate the state");
    }

    @Test
    void afterTheCooldownTheIdentityIsReadmittedWithSnapshotFirstForced() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor = quarantined(probe);
        long quarantinedAt = T0 + 2_000;

        Admission readmitted = governor.admit(EDGE, quarantinedAt + 60_000);
        assertEquals(AdmissionDecision.ALLOW_FORCE_SNAPSHOT, readmitted.decision(),
                "post-cooldown the identity MUST re-bootstrap (§7) — cursor rebound to 0 "
                        + "rides the C3 decideMode snapshot-first rule");
        assertEquals(1, probe.readmissions,
                "edge_fanout_readmissions_total must move on the cooldown exit");
        assertEquals(ConsumerState.CATCHUP, governor.state(EDGE),
                "readmission enters CATCHUP: the forced re-bootstrap is in flight");
        SlowConsumerGovernor.TransitionEvent event = probe.lastTransition();
        assertEquals(ConsumerState.QUARANTINED, event.from());
        assertEquals(ConsumerState.CATCHUP, event.to());
        assertEquals(SlowConsumerGovernor.REASON_READMITTED_QUARANTINE, event.reason());

        // The snapshot lands and the edge acks past it: CATCHUP resolves to HEALTHY.
        governor.onAckProgress(EDGE, 200, 200, quarantinedAt + 61_000);
        assertEquals(ConsumerState.HEALTHY, governor.state(EDGE));
        assertEquals(SlowConsumerGovernor.REASON_CATCHUP_RESOLVED,
                probe.lastTransition().reason());
    }

    @Test
    void theDemotionLadderStartsFreshAfterReadmission() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor = quarantined(probe);
        long readmitAt = T0 + 2_000 + 60_000;
        governor.admit(EDGE, readmitAt);

        // Two post-readmission demotions: under the limit - the pre-quarantine demotions
        // were cleared at quarantine time and must not double-trip.
        governor.onDemotion(EDGE,
                new DemotionEvent(210, 200, DemotionEvent.REASON_ACK_LAG), readmitAt + 1_000);
        governor.onDemotion(EDGE,
                new DemotionEvent(220, 200, DemotionEvent.REASON_ACK_LAG), readmitAt + 2_000);
        assertEquals(ConsumerState.CATCHUP, governor.state(EDGE));
        assertEquals(1, probe.quarantines, "still only the original quarantine");
    }

    @Test
    void operatorResetIsAnAdditionalExitNotTheOnlyOne() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor = quarantined(probe);

        // Operator reset mid-cooldown: full amnesty, immediately admissible as-is.
        governor.operatorReset(EDGE, T0 + 10_000);
        assertEquals(ConsumerState.HEALTHY, governor.state(EDGE));
        Admission admission = governor.admit(EDGE, T0 + 10_001);
        assertEquals(AdmissionDecision.ALLOW, admission.decision(),
                "after operator reset the identity subscribes normally");
        assertEquals(SlowConsumerGovernor.REASON_OPERATOR_RESET,
                probe.transitions.stream()
                        .filter(t -> t.reason().equals(SlowConsumerGovernor.REASON_OPERATOR_RESET))
                        .findFirst().orElseThrow().reason());
    }

    @Test
    void aHealthyIdentityIsAdmittedAsRequested() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);
        Admission unknown = governor.admit("never-seen", T0);
        assertEquals(AdmissionDecision.ALLOW, unknown.decision());
        assertEquals(ConsumerState.HEALTHY, unknown.state());
        assertEquals(0, probe.reconnectsRefused);
        assertTrue(probe.transitions.isEmpty(), "plain admission is not a transition");
    }

    @Test
    void aTrackedNonQuarantinedIdentityIsAdmittedAsRequested() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);
        governor.onDemotion(EDGE,
                new DemotionEvent(10, 5, DemotionEvent.REASON_ACK_LAG), T0); // -> CATCHUP
        Admission admission = governor.admit(EDGE, T0 + 1_000);
        assertEquals(AdmissionDecision.ALLOW, admission.decision(),
                "CATCHUP is not a refusal state — the reconnect resumes normally");
        assertEquals(ConsumerState.CATCHUP, admission.state());
        assertEquals(0L, admission.cooldownRemainingMs());
    }

    @Test
    void operatorResetUpdatesTheStateGauges() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor = quarantined(probe);
        assertEquals(1, probe.lastQuarantined, "fixture: the gauge counts the quarantine");
        governor.operatorReset(EDGE, T0 + 10_000);
        assertEquals(0, probe.lastQuarantined,
                "the reset must release the identity from the quarantined gauge");
        assertEquals(0, governor.trackedIdentities(), "the record is dropped entirely");
    }

    @Test
    void aQuarantineClearsBothDemotionLaddersEvenInsideTheWindow() {
        // The quarantine's clean slate must come from the CLEAR, not merely from the
        // window pruning: with a cooldown SHORTER than the demote window, pre-quarantine
        // demotions are still age-eligible after readmission - and must not re-trip.
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerPolicyConfig shortCooldown = new SlowConsumerPolicyConfig(
                10_000L, 3, 3, 60_000L, 10_000L, 3, 3_600_000L, 3_600_000L, 4_096);
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(shortCooldown, probe, probe::onTransition);

        // 2 gap demotions (gapDemoteLimit 3 here) + 3 distress -> quarantine via distress.
        governor.onDemotion(EDGE, new DemotionEvent(1, 0, DemotionEvent.REASON_GAP), T0);
        governor.onDemotion(EDGE, new DemotionEvent(2, 0, DemotionEvent.REASON_GAP), T0 + 100);
        for (int i = 0; i < 3; i++) {
            governor.onDemotion(EDGE,
                    new DemotionEvent(3 + i, 0, DemotionEvent.REASON_ACK_LAG), T0 + 200 + i);
        }
        assertEquals(ConsumerState.QUARANTINED, governor.state(EDGE), "fixture");

        // Readmit after the 10 s cooldown - well inside the 60 s demote window.
        governor.admit(EDGE, T0 + 10_300);
        assertEquals(ConsumerState.CATCHUP, governor.state(EDGE));

        // One gap and one distress demotion: had the quarantine not CLEARED the ladders,
        // the still-in-window pre-quarantine entries would re-trip immediately.
        assertEquals(ConsumerState.CATCHUP, governor.onDemotion(EDGE,
                new DemotionEvent(10, 5, DemotionEvent.REASON_GAP), T0 + 11_000));
        assertEquals(ConsumerState.CATCHUP, governor.onDemotion(EDGE,
                new DemotionEvent(11, 5, DemotionEvent.REASON_ACK_LAG), T0 + 11_100));
        assertEquals(1, probe.quarantines,
                "the cleared ladders must not re-trip from pre-quarantine demotions");
    }

    @Test
    void configAccessorExposesTheEnforcedThresholds() {
        SlowConsumerPolicyConfig config = config();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config, new RecordingPolicyProbe());
        assertEquals(config, governor.config(),
                "the server derives its evaluation cadence from the enforced config");
    }
}
