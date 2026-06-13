package io.configd.testkit;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RR-095 per-seed re-run and diagnosis (Session 4 / Workstream A2, EXP-002).
 * <p>
 * The S2 10k adversarial sweep recorded 7 liveness stalls (no leader ever elected):
 * seeds 452, 869, 4740, 5100, 5159, 5500, 8319 — characterized as expected
 * never-healed-schedule artifacts (a sustained drop window and/or partitions never healed
 * before end-of-run, so the cluster correctly makes no progress; 0 safety violations).
 * RR-103 (per-peer inflight-window leak) was a candidate root-cause component for this
 * family. This test re-runs all 7 seeds against the RR-103-FIXED kernel and gives each its
 * own diagnosis (charter A2: "no remaining-stalls blob").
 * <p>
 * The discriminating question for each seed: is the stall a never-healed-schedule artifact
 * (the network is still faulted at end-of-run — benign, no recovery was ever possible) or a
 * recoverable-but-stuck bug (the network healed but no leader emerged — a real liveness
 * defect)? This test reads the authoritative end-of-run network state to answer it per seed.
 */
class Rr095StallSeedDiagnosisTest {

    private static final int NODES = 5;
    private static final int TICKS = 1_500;
    private static final long[] STALL_SEEDS = {452, 869, 4740, 5100, 5159, 5500, 8319};

    @Test
    void everyRr095StallSeedIsANeverHealedArtifactNotARecoverableStuckBug() {
        for (long seed : STALL_SEEDS) {
            AdversarialSim sim = new AdversarialSim(seed, NODES, TICKS);
            sim.run(); // throws on any safety violation (none expected)

            boolean leaderElected = sim.activity().leaderElected();
            double endDropRate = sim.network().dropRateForTest();
            int endPartitions = sim.network().activePartitionsForTest();

            // Characterize the schedule: unpaired drop windows + net unhealed partitions.
            Map<AdversarialSchedule.FaultKind, Integer> counts =
                    new EnumMap<>(AdversarialSchedule.FaultKind.class);
            for (AdversarialSchedule.Event e : sim.schedule().events()) {
                counts.merge(e.kind(), 1, Integer::sum);
            }
            int dropBegins = counts.getOrDefault(AdversarialSchedule.FaultKind.DROP_WINDOW_BEGIN, 0);
            int dropEnds = counts.getOrDefault(AdversarialSchedule.FaultKind.DROP_WINDOW_END, 0);
            int unpairedDropWindows = dropBegins - dropEnds;

            boolean networkUnhealedAtEnd = endDropRate > 0.0 || endPartitions > 0;

            System.out.printf(
                    "[RR-095 seed=%d] leaderElected=%b | endDropRate=%.3f endPartitions=%d "
                            + "| dropWindows=%d(unpaired=%d) partAdds=%d partRemoves=%d heals=%d "
                            + "=> %s%n",
                    seed, leaderElected, endDropRate, endPartitions, dropBegins,
                    unpairedDropWindows,
                    counts.getOrDefault(AdversarialSchedule.FaultKind.PARTITION_ADD, 0),
                    counts.getOrDefault(AdversarialSchedule.FaultKind.PARTITION_REMOVE, 0),
                    counts.getOrDefault(AdversarialSchedule.FaultKind.HEAL_ALL, 0),
                    networkUnhealedAtEnd ? "NEVER-HEALED ARTIFACT (benign)"
                            : "HEALED-BUT-STUCK (would be a real liveness bug!)");

            // (1) Still stalls post-RR-103-fix — confirms RR-103 was not its root cause.
            assertFalse(leaderElected,
                    "seed " + seed + " was a registered RR-095 stall; post-RR-103-fix it must"
                            + " still stall (RR-103 is a leader-side window leak, a different"
                            + " mechanism from no-leader-elected) — if it now elects, the causal"
                            + " link must be recorded, not silently absorbed");

            // (2) The stall is explained by a never-healed network — NOT a recoverable stuck
            //     state. This is the load-bearing diagnosis: a healed network with no leader
            //     would be a genuine liveness defect owed its own row.
            assertTrue(networkUnhealedAtEnd,
                    "seed " + seed + " stalls with a HEALED network (dropRate=" + endDropRate
                            + ", partitions=" + endPartitions + ") — that would be a real"
                            + " recoverable-but-stuck liveness bug, not a benign artifact; it must"
                            + " get its own register row, not be filed under RR-095");
        }
    }
}
