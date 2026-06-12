package io.configd.testkit;

import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.FanOutSessionMetrics;
import io.configd.distribution.fanout.SlowConsumerGovernor;
import io.configd.distribution.fanout.SlowConsumerGovernor.ConsumerState;
import io.configd.distribution.fanout.SlowConsumerPolicyConfig;
import io.configd.store.CommandCodec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The charter §4 C4 / gate-3 walk ("slow-consumer state machine walk passes"): a
 * deliberately-lagging edge actor ({@code EdgeActor.lag()}) walks the FULL machine inside
 * {@link EdgeFanOutSim} — HEALTHY → SLOW (sustained queue warn) → CATCHUP (C1 overflow
 * demotion) → QUARANTINED (demoteLimit; on-wire ERROR_CLOSE code 8 through the REAL
 * {@code EdgeClientCore.onErrorClose} reaction) → refused reconnects through the cooldown
 * → readmitted with the snapshot-first re-bootstrap forced → CATCHUP → HEALTHY, with the
 * edge invariants checked every tick and the edge converging at the end.
 *
 * <p>Also the screen C4-3 scenario: a HEALTHY edge that flaps purely from injected network
 * loss (partition/heal cycles crossing the replay horizon — GAP recoveries, not slowness)
 * recovers every time and never escalates: zero refusals, zero quarantines, zero
 * governor transitions.
 *
 * <p>The governor wiring is OPT-IN on the {@link C1StreamDriver} — the 507-seed gate path
 * runs without it and is byte-identical ({@code EdgeSeedCompatTest}). Determinism of the
 * governor itself is proven by replaying the whole walk: the recorded structured
 * transition events (timestamps, cursors, window counts) must be identical run-to-run.
 */
class SlowConsumerStateMachineWalkTest {

    private static final int CP_NODES = 3;
    /** One edge: the walking actor. (Identity independence is the process test's leg —
     *  {@code FanOutServerQuarantineTest}; a bystander here would only add it own
     *  walk-threshold noise to the recorded evidence.) */
    private static final int EDGES = 1;
    private static final long SEED = 31L;

    /**
     * Walk-tuned session config: 1-notification frames against an 8-frame bounded queue
     * (warn at 6) make queue pressure and overflow demotion exactly countable in commits;
     * ack-lag is disabled so the distress reason under test is queue_overflow alone.
     */
    private static FanOutConfig walkSessionConfig() {
        return new FanOutConfig(8, 80, 1, 262_144, 1_000_000L, 250L, 5L, 1_048_576);
    }

    /**
     * Sim-scaled policy thresholds (1 sim tick = 1 ms): warn window 20 ms, 3 distress
     * demotions within 60 s quarantine, 300 ms quarantine cooldown. GAP limit stays high
     * (C4-2 weighting — the flap scenario relies on it).
     */
    private static SlowConsumerPolicyConfig walkPolicyConfig() {
        return new SlowConsumerPolicyConfig(
                20L, 3, 10, 60_000L, 300L, 3, 3_600_000L, 3_600_000L, 64);
    }

    /** One full deterministic walk; returns the recorded evidence for the replay compare. */
    private record WalkEvidence(List<SlowConsumerGovernor.TransitionEvent> transitions,
                                int refusals, int quarantines, int readmissions) { }

    private WalkEvidence runFullWalk() {
        List<SlowConsumerGovernor.TransitionEvent> transitions = new ArrayList<>();
        CountingPolicyMetrics metrics = new CountingPolicyMetrics();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(walkPolicyConfig(), metrics, transitions::add);
        C1StreamDriver driver = new C1StreamDriver(walkSessionConfig(), governor);
        EdgeFanOutSim sim = new EdgeFanOutSim(SEED, CP_NODES, EDGES, 200,
                false, driver, new AdversarialSchedule.Intensity(0, 0, 0.0),
                EdgeInvariants.BOUND_MS);
        sim.run(); // settle: election + initial subscribes (empty ring → TAIL), no faults
        EdgeActor victim = sim.edges().get(0);
        String victimIdentity = "edge-" + victim.edgeId();
        sim.enableEdgeRecovery(0);
        assertEquals(ConsumerState.HEALTHY, governor.state(victimIdentity), "walk start");

        // --- HEALTHY → SLOW → CATCHUP → QUARANTINED: the victim lags and commits keep
        // flowing. Each commit lands ~1-2 seqs as unacked 1-seq frames: the queue crosses
        // warn (6) and stays there past the 20 ms window (→ SLOW, fires mid-flow), then
        // overflows at 8 (→ the C1 queue_overflow demotion, → CATCHUP); the 3rd distress
        // demotion inside the window trips the quarantine, the driver kicks the
        // connection and ERROR_CLOSE code 8 rides the wire to the edge. The ORDER of the
        // legs is asserted from the recorded transition events below — polling for SLOW
        // here would race past it (the walk is driven by the commits themselves).
        victim.lag();
        for (int i = 1; i <= 60 && governor.state(victimIdentity) != ConsumerState.QUARANTINED; i++) {
            commit(sim, victim.subscribedCpNode(), "walk/q" + i, "q" + i);
        }
        assertEquals(ConsumerState.QUARANTINED, governor.state(victimIdentity),
                "repeated distress demotions must quarantine");
        assertEquals(1, metrics.slowTransitions, "the sustained-warn SLOW leg fired");
        assertEquals(1, metrics.quarantines);

        // --- Refused reconnects through the cooldown: the (now unlagged) edge processes
        // the ERROR_CLOSE through the REAL core reaction → reconnect directive → the
        // driver's resubscribe is REFUSED at admission and retried — observably.
        victim.unlag();
        tickUntil(sim, () -> metrics.reconnectsRefused > 0,
                "the edge's reconnect attempts are refused during the cooldown");
        assertEquals(ConsumerState.QUARANTINED, governor.state(victimIdentity),
                "refusals must not mutate the state");

        // --- Cooldown exit → forced snapshot-first re-bootstrap → CATCHUP → HEALTHY.
        tickUntil(sim, () -> governor.state(victimIdentity) == ConsumerState.HEALTHY,
                "post-cooldown readmission re-bootstraps and resolves to HEALTHY");
        assertEquals(1, metrics.readmissions);

        // --- Converged: the readmitted edge serves the latest committed value.
        commit(sim, victim.subscribedCpNode(), "walk/final", "converged");
        tickUntil(sim, () -> hasValue(victim, "walk/final", "converged"),
                "the readmitted edge converges to post-quarantine commits");

        return new WalkEvidence(List.copyOf(transitions), metrics.reconnectsRefused,
                metrics.quarantines, metrics.readmissions);
    }

    @Test
    void laggingEdgeWalksTheFullStateMachineEndToEnd() {
        WalkEvidence evidence = runFullWalk();

        // The exact walk, in order, with the design §2 reasons (charter §4 C4: every
        // transition observed; the structured events ARE the evidence).
        List<String> legs = evidence.transitions().stream()
                .map(t -> t.from() + "->" + t.to() + ":" + t.reason())
                .toList();
        assertEquals(List.of(
                "HEALTHY->SLOW:" + SlowConsumerGovernor.REASON_QUEUE_WARN_SUSTAINED,
                "SLOW->CATCHUP:" + io.configd.distribution.fanout.DemotionEvent.REASON_QUEUE_OVERFLOW,
                "CATCHUP->QUARANTINED:" + SlowConsumerGovernor.REASON_DEMOTE_LIMIT,
                "QUARANTINED->CATCHUP:" + SlowConsumerGovernor.REASON_READMITTED_QUARANTINE,
                "CATCHUP->HEALTHY:" + SlowConsumerGovernor.REASON_CATCHUP_RESOLVED),
                legs, "the full machine must be walked in order: " + legs);
        assertTrue(evidence.refusals() > 0, "cooldown refusals must be observed (C4-3)");

        // Cursor evidence rides the quarantine transition (CT-28).
        SlowConsumerGovernor.TransitionEvent quarantine = evidence.transitions().get(2);
        assertTrue(quarantine.cursor() > 0 && quarantine.lastAckedSeq() >= 0,
                "the quarantine event must carry the cursor evidence: " + quarantine);
        assertEquals(3, quarantine.distressDemotionsInWindow());
    }

    @Test
    void theWalkIsDeterministic_sameSeedReplaysIdentically() {
        // The governor folds into the sim's determinism story: the same seed must replay
        // the SAME structured transition events — timestamps, cursors, window counts —
        // and the same refusal/quarantine/readmission counts. (The gate digest itself is
        // governor-free because the governor is opt-in and absent on the gate path.)
        WalkEvidence first = runFullWalk();
        WalkEvidence second = runFullWalk();
        assertEquals(first.transitions(), second.transitions(),
                "the recorded transition events must be byte-equal run-to-run");
        assertEquals(first.refusals(), second.refusals());
        assertEquals(first.quarantines(), second.quarantines());
        assertEquals(first.readmissions(), second.readmissions());
    }

    /**
     * The CT-30 closing condition (C4 contract-qa audit): {@code quarantineLimit}
     * lag/readmit cycles walk the machine to its LAST state — the 3rd quarantine within
     * {@code unhealthyWindowMs} escalates to UNHEALTHY (alert-grade metric, the
     * {@code repeat_quarantine} structured event with {@code quarantinesInWindow=3}),
     * reconnects are refused through the unhealthy cooldown, and the cooldown ALONE
     * auto-readmits with the forced snapshot-first re-bootstrap (C4-3) — ending converged
     * and HEALTHY. This also exercises the driver-side UNHEALTHY kick arm (the verdict
     * branch shared with the wire test's {@code FanOutServer.onDemotionEvent}).
     */
    @Test
    void quarantineLimitCyclesEscalateToUnhealthyThenAutoReadmit() {
        List<SlowConsumerGovernor.TransitionEvent> transitions = new ArrayList<>();
        CountingPolicyMetrics metrics = new CountingPolicyMetrics();
        // Walk thresholds with a sim-scaled UNHEALTHY ladder: all 3 quarantines land
        // inside the 60 s window; the unhealthy cooldown (2 s) is tick-crossable.
        SlowConsumerPolicyConfig policy = new SlowConsumerPolicyConfig(
                20L, 3, 10, 60_000L, 300L, 3, 60_000L, 2_000L, 64);
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(policy, metrics, transitions::add);
        C1StreamDriver driver = new C1StreamDriver(walkSessionConfig(), governor);
        EdgeFanOutSim sim = new EdgeFanOutSim(SEED, CP_NODES, EDGES, 200,
                false, driver, new AdversarialSchedule.Intensity(0, 0, 0.0),
                EdgeInvariants.BOUND_MS);
        sim.run();
        EdgeActor victim = sim.edges().get(0);
        String victimIdentity = "edge-" + victim.edgeId();
        sim.enableEdgeRecovery(0);

        // quarantineLimit (3) lag→quarantine→readmit cycles; the 3rd trip escalates.
        for (int cycle = 1; cycle <= 3; cycle++) {
            victim.lag();
            for (int i = 1; i <= 60
                    && governor.state(victimIdentity) != ConsumerState.QUARANTINED
                    && governor.state(victimIdentity) != ConsumerState.UNHEALTHY; i++) {
                commit(sim, victim.subscribedCpNode(), "uw/c" + cycle + "/k" + i, "v" + i);
            }
            victim.unlag();
            if (cycle < 3) {
                assertEquals(ConsumerState.QUARANTINED, governor.state(victimIdentity),
                        "cycle " + cycle + " must end in QUARANTINED");
                tickUntil(sim, () -> governor.state(victimIdentity) == ConsumerState.HEALTHY,
                        "cycle " + cycle + " readmits after the quarantine cooldown");
            }
        }
        assertEquals(ConsumerState.UNHEALTHY, governor.state(victimIdentity),
                "the 3rd quarantine within the window must escalate");
        assertEquals(3, metrics.quarantines, "the escalating trip still counts as a quarantine");
        assertEquals(1, metrics.unhealthy,
                "edge_fanout_unhealthy_total must move exactly once (alert-grade)");
        SlowConsumerGovernor.TransitionEvent escalation = transitions.stream()
                .filter(t -> t.to() == ConsumerState.UNHEALTHY)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals(SlowConsumerGovernor.REASON_REPEAT_QUARANTINE, escalation.reason());
        assertEquals(3, escalation.quarantinesInWindow());

        // Refused — observably — through the unhealthy cooldown...
        int refusalsAtEscalation = metrics.reconnectsRefused;
        tickUntil(sim, () -> metrics.reconnectsRefused > refusalsAtEscalation,
                "reconnects are refused during the unhealthy cooldown");
        assertEquals(ConsumerState.UNHEALTHY, governor.state(victimIdentity));

        // ...then the cooldown ALONE readmits (C4-3) and the edge resolves to HEALTHY.
        tickUntil(sim, () -> governor.state(victimIdentity) == ConsumerState.HEALTHY,
                "the unhealthy cooldown auto-readmits and the re-bootstrap resolves");
        assertEquals(3, metrics.readmissions,
                "two quarantine-cooldown readmissions plus the unhealthy-cooldown one");
        assertTrue(transitions.stream().anyMatch(t ->
                        t.from() == ConsumerState.UNHEALTHY && t.to() == ConsumerState.CATCHUP
                        && SlowConsumerGovernor.REASON_READMITTED_UNHEALTHY.equals(t.reason())),
                "the auto-readmission must ride the readmitted_after_unhealthy_cooldown leg");

        commit(sim, victim.subscribedCpNode(), "uw/final", "converged");
        tickUntil(sim, () -> hasValue(victim, "uw/final", "converged"),
                "the readmitted edge converges after the UNHEALTHY episode");
    }

    /**
     * Screen condition C4-3 (couples to C4-2's reason weighting): a HEALTHY edge that
     * flaps purely from injected network loss — partition/heal cycles whose misses cross
     * the replay horizon (ring cap 8), i.e. GAP recoveries, not slowness — recovers every
     * cycle through the C3 resubscribe path and NEVER escalates: the governor records no
     * transitions, no refusals, no quarantines.
     */
    @Test
    void networkLossFlappingNeverEscalatesAHealthyEdge() {
        List<SlowConsumerGovernor.TransitionEvent> transitions = new ArrayList<>();
        CountingPolicyMetrics metrics = new CountingPolicyMetrics();
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(walkPolicyConfig(), metrics, transitions::add);
        // Roomy queue + disabled ack-lag: the only distress an honest network flap could
        // produce is GAP — which is exactly what must not escalate.
        FanOutConfig config = new FanOutConfig(64, 80, 64, 262_144, 1_000_000L, 250L, 5L, 1_048_576);
        C1StreamDriver driver = new C1StreamDriver(config, governor);
        EdgeFanOutSim sim = new EdgeFanOutSim(SEED + 1, CP_NODES, EDGES, 200,
                false, driver, new AdversarialSchedule.Intensity(0, 0, 0.0),
                EdgeInvariants.BOUND_MS, 8 /* tiny ring: flaps cross the horizon */);
        sim.run();
        EdgeActor victim = sim.edges().get(0);
        String victimIdentity = "edge-" + victim.edgeId();
        sim.enableEdgeRecovery(0);

        for (int cycle = 1; cycle <= 3; cycle++) {
            sim.partitionEdge(0);
            for (int i = 1; i <= 12; i++) { // > ring cap: the horizon laps the victim
                commit(sim, victim.subscribedCpNode(), "flap/" + cycle + "/k" + (i % 3), "c" + cycle + "v" + i);
            }
            sim.healEdge(0);
            commit(sim, victim.subscribedCpNode(), "flap/" + cycle + "/after", "healed-" + cycle);
            final int c = cycle;
            tickUntil(sim, () -> hasValue(victim, "flap/" + c + "/after", "healed-" + c),
                    "the flapping edge recovers in cycle " + cycle);
        }

        assertTrue(driver.resubscribes() >= 3,
                "non-vacuity: each flap must actually recover through the resubscribe path");
        assertEquals(ConsumerState.HEALTHY, governor.state(victimIdentity));
        assertEquals(0, metrics.quarantines, "network loss must never quarantine (C4-2/C4-3)");
        assertEquals(0, metrics.reconnectsRefused, "every flap reconnect must be admitted");
        assertEquals(List.of(), transitions,
                "a healthy flapping edge must record NO policy transitions: " + transitions);
    }

    // -----------------------------------------------------------------------
    // helpers (the EdgeGapRecoveryTest commit/tick discipline — no sleeps)
    // -----------------------------------------------------------------------

    private static void commit(EdgeFanOutSim sim, int observedCpNode, String key, String value) {
        byte[] expected = value.getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 50; attempt++) {
            int leader = sim.cpSim().findLeader();
            if (leader >= 0) {
                sim.cpSim().node(leader).propose(CommandCodec.encodePut(key, expected));
            }
            for (int t = 0; t < 20; t++) {
                sim.tick();
                var r = sim.cpSim().store(observedCpNode).get(key);
                if (r.found() && java.util.Arrays.equals(expected, r.value())) {
                    return;
                }
            }
        }
        fail("write '" + key + "' did not commit/apply on cp node " + observedCpNode);
    }

    private static void tickUntil(EdgeFanOutSim sim, java.util.function.BooleanSupplier cond,
                                  String what) {
        for (int t = 0; t < 3_000; t++) {
            if (cond.getAsBoolean()) {
                return;
            }
            sim.tick();
        }
        fail("not reached within the tick bound: " + what);
    }

    private static boolean hasValue(EdgeActor edge, String key, String expected) {
        var r = edge.get(key);
        return r.found() && expected.equals(new String(r.value(), StandardCharsets.UTF_8));
    }

    /** Counts the policy series (single sim thread — plain ints). */
    private static final class CountingPolicyMetrics implements FanOutSessionMetrics {
        int slowTransitions;
        int quarantines;
        int unhealthy;
        int reconnectsRefused;
        int readmissions;

        @Override public void onNotifyBatch(int n, int bytes) { }
        @Override public void onQueueDepth(int depth) { }
        @Override public void onSlowConsumerWarning() { }
        @Override public void onDemotion(String reason) { }
        @Override public void onSnapshotTransfer() { }
        @Override public void onHeartbeat() { }
        @Override public void onSessionClosed(String reason) { }
        @Override public void onSlowTransition() {
            slowTransitions++;
        }
        @Override public void onQuarantine() {
            quarantines++;
        }
        @Override public void onUnhealthy() {
            unhealthy++;
        }
        @Override public void onReconnectRefused() {
            reconnectsRefused++;
        }
        @Override public void onReadmission() {
            readmissions++;
        }
    }
}
