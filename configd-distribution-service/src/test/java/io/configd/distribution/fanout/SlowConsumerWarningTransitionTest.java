package io.configd.distribution.fanout;

import io.configd.distribution.fanout.SlowConsumerGovernor.ConsumerState;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CT-27 — architecture §7 ":288 0 credits for &gt; 10 s → Warning log + metric", re-based
 * on the C1 signals (C4 design §1/§2): the queue at/above the C1 warn threshold sustained
 * for {@code edge.fanout.policy.queueWarnWindowMs} promotes HEALTHY→SLOW with the
 * {@code edge_fanout_slow_transitions_total} metric and the structured transition event;
 * ack progress (the queue draining below warn) returns the consumer to HEALTHY.
 * Clock-driven (explicit nowMillis — no sleeps; evidence discipline, handoff §6).
 */
class SlowConsumerWarningTransitionTest {

    private static final String EDGE = "CN=edge-1,O=configd";
    private static final long T0 = 1_700_000_000_000L;

    private static SlowConsumerPolicyConfig config() {
        // queueWarnWindowMs = 10_000 (the §7 ">10 s" analogue), everything else defaults.
        return SlowConsumerPolicyConfig.defaults();
    }

    @Test
    void sustainedQueueWarnPromotesToSlowWithMetricAndStructuredEvent() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);

        governor.onQueuePressure(EDGE, true, 42L, 30L, T0);
        assertEquals(ConsumerState.HEALTHY, governor.state(EDGE),
                "crossing the warn threshold alone is the C1 warning, not yet SLOW");

        // One millisecond short of the window: still HEALTHY (boundary exactness).
        assertEquals(ConsumerState.HEALTHY, governor.evaluate(EDGE, T0 + 9_999));
        assertEquals(0, probe.slowTransitions);

        // At exactly the window: SLOW, with the metric and the structured evidence.
        assertEquals(ConsumerState.SLOW, governor.evaluate(EDGE, T0 + 10_000));
        assertEquals(1, probe.slowTransitions,
                "edge_fanout_slow_transitions_total must move exactly once");
        SlowConsumerGovernor.TransitionEvent event = probe.lastTransition();
        assertEquals(EDGE, event.identity());
        assertEquals(ConsumerState.HEALTHY, event.from());
        assertEquals(ConsumerState.SLOW, event.to());
        assertEquals(SlowConsumerGovernor.REASON_QUEUE_WARN_SUSTAINED, event.reason());
        assertEquals(T0 + 10_000, event.atMillis());
        assertEquals(1, probe.lastSlow, "consumer_state gauge must count the SLOW identity");

        // Re-evaluating while SLOW must not re-fire the transition (edge, not level).
        assertEquals(ConsumerState.SLOW, governor.evaluate(EDGE, T0 + 20_000));
        assertEquals(1, probe.slowTransitions);
    }

    @Test
    void slowReturnsToHealthyWhenAckProgressDrainsTheQueue() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);
        governor.onQueuePressure(EDGE, true, 42L, 30L, T0);
        governor.evaluate(EDGE, T0 + 10_000);
        assertEquals(ConsumerState.SLOW, governor.state(EDGE));

        // The queue drains below warn — the §2 "ack progress resumes" exit.
        governor.onQueuePressure(EDGE, false, 50L, 50L, T0 + 12_000);
        assertEquals(ConsumerState.HEALTHY, governor.state(EDGE));
        SlowConsumerGovernor.TransitionEvent event = probe.lastTransition();
        assertEquals(ConsumerState.SLOW, event.from());
        assertEquals(ConsumerState.HEALTHY, event.to());
        assertEquals(SlowConsumerGovernor.REASON_ACK_PROGRESS, event.reason());
        assertEquals(0, probe.lastSlow);
        assertEquals(1, probe.lastHealthy);
    }

    @Test
    void aWarnExcursionShorterThanTheWindowNeverPromotes() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);

        // Above for 9 s, then below — the window re-arms from scratch on the next excursion.
        governor.onQueuePressure(EDGE, true, 10L, 5L, T0);
        governor.onQueuePressure(EDGE, false, 12L, 12L, T0 + 9_000);
        governor.onQueuePressure(EDGE, true, 20L, 15L, T0 + 9_500);
        assertEquals(ConsumerState.HEALTHY, governor.evaluate(EDGE, T0 + 19_000),
                "9.5 s into the SECOND excursion: the first excursion must not carry over");
        assertEquals(ConsumerState.SLOW, governor.evaluate(EDGE, T0 + 19_500));
        assertEquals(1, probe.slowTransitions);
        assertTrue(probe.transitions.stream()
                        .noneMatch(t -> t.atMillis() < T0 + 19_500),
                "no transition may predate the sustained-window expiry");
    }

    @Test
    void repeatedAboveSignalsDoNotResetTheWarnWindow() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);
        governor.onQueuePressure(EDGE, true, 10L, 5L, T0);
        // A repeated (level, not edge) above-signal must keep the ORIGINAL anchor.
        governor.onQueuePressure(EDGE, true, 11L, 5L, T0 + 8_000);
        assertEquals(ConsumerState.SLOW, governor.evaluate(EDGE, T0 + 10_000),
                "the window anchors at the first above-warn signal");
    }

    @Test
    void aRepeatedAboveSignalItselfPromotesOnceTheWindowElapsed() {
        // The promotion must not depend on the evaluate() cadence: a level (repeat)
        // above-warn signal arriving after the window is itself a promotion point.
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);
        governor.onQueuePressure(EDGE, true, 10L, 5L, T0);
        governor.onQueuePressure(EDGE, true, 12L, 5L, T0 + 20_000);
        assertEquals(ConsumerState.SLOW, governor.state(EDGE),
                "the pressure signal alone must promote — no evaluate() needed");
    }

    @Test
    void epochZeroAnchorsTheWarnWindowCorrectly() {
        // Time-0 anchor exactness: warnSinceMillis == 0 is a VALID anchor (the sentinel
        // is -1, not 0) and a repeat signal must not re-anchor it.
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);
        governor.onQueuePressure(EDGE, true, 1L, 0L, 0L);
        governor.onQueuePressure(EDGE, true, 2L, 0L, 5_000L); // repeat: keeps anchor 0
        assertEquals(ConsumerState.SLOW, governor.evaluate(EDGE, 10_000L),
                "anchor at t=0 plus the full window must promote");
    }

    @Test
    void trackingANewIdentityPublishesTheStateGauges() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);
        governor.onQueuePressure(EDGE, true, 1L, 0L, T0);
        assertEquals(1, probe.lastHealthy,
                "the consumer_state gauge must count a newly tracked identity");
        // Under the bound, identities coexist — eviction must not run below it.
        governor.onQueuePressure("CN=edge-other,O=configd", true, 1L, 0L, T0 + 1);
        assertEquals(2, governor.trackedIdentities(),
                "no eviction below maxTrackedIdentities");
        assertEquals(2, probe.lastHealthy);
    }

    @Test
    void untrackedIdentityIsHealthyAndEvaluateIsANoOp() {
        RecordingPolicyProbe probe = new RecordingPolicyProbe();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(config(), probe, probe::onTransition);
        assertEquals(ConsumerState.HEALTHY, governor.state("never-seen"));
        assertEquals(ConsumerState.HEALTHY, governor.evaluate("never-seen", T0));
        assertEquals(0, governor.trackedIdentities(),
                "evaluate must not allocate a record for an unknown identity");
    }
}
