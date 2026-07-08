package io.configd.server.balance;

import io.configd.common.NodeId;
import io.configd.server.balance.BalanceTestSupport.FakeCluster;
import io.configd.server.balance.BalanceTestSupport.MutableClock;
import io.configd.server.balance.BalanceTestSupport.RecordingMetrics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static io.configd.server.balance.BalanceTestSupport.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the {@link LeaderBalanceLoop}'s control behavior deterministically over the {@link FakeCluster}
 * model - convergence, the dampening (threshold / cooldown / one-at-a-time), the instability back-off,
 * dry-run, the primitive-refusal fold, and decentralized multi-shedder convergence. Each cadence is a
 * hand-driven {@code runOnce()} at a controlled clock, so there is no scheduler, no thread, and no
 * network to flake on.
 */
class LeaderBalanceLoopTest {

    private static final long CADENCE_MS = 30_000L;

    private static LeaderBalanceLoop loop(FakeCluster cluster, NodeId self, LeaderBalanceConfig cfg,
                                          MutableClock clock, RecordingMetrics metrics, long seed) {
        return new LeaderBalanceLoop(cluster.viewFor(self), cluster.transferFor(self), cfg, clock,
                new Random(seed), metrics);
    }

    @Test
    void convergesFromAllOnOneNode_thenStops() {
        // G=8, M=4, every leader on node 0. Node 0 is the only max, so running just its loop converges the
        // whole cluster to spread <= 1 and then STOPS (test-matrix item 1).
        FakeCluster cluster = new FakeCluster(4);
        cluster.placeAllOn(8, NodeId.of(0));
        MutableClock clock = new MutableClock();
        RecordingMetrics metrics = new RecordingMetrics();

        try (LeaderBalanceLoop loop = loop(cluster, NodeId.of(0), config(2, 60_000L, 5_000L, false),
                clock, metrics, 11L)) {
            int appliedAtConvergence = -1;
            int convergedCadence = -1;
            for (int cadence = 0; cadence < 40; cadence++) {
                clock.set(cadence * CADENCE_MS);
                loop.runOnce();
                if (convergedCadence < 0 && cluster.spread() <= 1) {
                    convergedCadence = cadence;
                    appliedAtConvergence = cluster.transfersApplied;
                }
            }
            assertTrue(convergedCadence >= 0, "cluster should converge to spread <= 1");
            assertTrue(cluster.spread() <= 1, "final spread must be <= 1, was " + cluster.spread());
            // Once balanced it must not keep transferring.
            assertEquals(appliedAtConvergence, cluster.transfersApplied,
                    "no further transfers may occur after convergence");
        }
    }

    @Test
    void noThrashOnOptimalUneven() {
        // G=5, M=3, optimal {2,2,1}: spread 1 < threshold 2. Running every node's loop must move NOTHING
        // (test-matrix item 2 - the >=2 threshold at work).
        FakeCluster cluster = new FakeCluster(3);
        cluster.place(0, NodeId.of(0));
        cluster.place(1, NodeId.of(0));
        cluster.place(2, NodeId.of(1));
        cluster.place(3, NodeId.of(1));
        cluster.place(4, NodeId.of(2));
        MutableClock clock = new MutableClock();

        List<LeaderBalanceLoop> loops = new ArrayList<>();
        for (int n = 0; n < 3; n++) {
            loops.add(loop(cluster, NodeId.of(n), config(2, 60_000L, 5_000L, false),
                    clock, new RecordingMetrics(), n));
        }
        try {
            for (int cadence = 0; cadence < 12; cadence++) {
                clock.set(cadence * CADENCE_MS);
                for (LeaderBalanceLoop loop : loops) {
                    loop.runOnce();
                }
            }
            assertEquals(0, cluster.transfersApplied, "an optimal-but-uneven cluster must not thrash");
        } finally {
            loops.forEach(LeaderBalanceLoop::close);
        }
    }

    @Test
    void atMostOneTransferPerCadence() {
        // From a heavily skewed start a single cadence initiates at most one transfer (test-matrix item 6).
        FakeCluster cluster = new FakeCluster(4);
        cluster.placeAllOn(8, NodeId.of(0));
        MutableClock clock = new MutableClock();
        RecordingMetrics metrics = new RecordingMetrics();
        try (LeaderBalanceLoop loop = loop(cluster, NodeId.of(0), config(2, 60_000L, 0L, false),
                clock, metrics, 1L)) {
            loop.runOnce();
            assertEquals(1, cluster.transfersApplied);
            assertEquals(1, metrics.transfersInitiated);
        }
    }

    @Test
    void cooldownSuppressesTheNextCadence() {
        // instabilityWindow=0 isolates cooldown from term-churn. A transfer at t=0 must block the next
        // cadence (inside the 60s cooldown) and unblock once it elapses (test-matrix item 7).
        FakeCluster cluster = new FakeCluster(4);
        cluster.placeAllOn(8, NodeId.of(0));
        MutableClock clock = new MutableClock();
        RecordingMetrics metrics = new RecordingMetrics();
        try (LeaderBalanceLoop loop = loop(cluster, NodeId.of(0), config(2, 60_000L, 0L, false),
                clock, metrics, 1L)) {
            clock.set(0);
            loop.runOnce();
            assertEquals(1, cluster.transfersApplied);

            clock.set(30_000L); // still inside cooldown
            loop.runOnce();
            assertEquals(1, cluster.transfersApplied, "cooldown must suppress the mid-cooldown cadence");
            assertTrue(metrics.skipped(LeaderBalancePlanner.REASON_COOLDOWN) >= 1);

            clock.set(60_000L); // cooldown elapsed
            loop.runOnce();
            assertEquals(2, cluster.transfersApplied, "a transfer resumes once cooldown elapses");
        }
    }

    @Test
    void electionStormBacksOff_thenResumes() {
        // A balanced seed, then a crash-takeover with continual term churn: while any group's term bumped
        // within the window, the whole cycle backs off; once terms settle, transfers resume
        // (test-matrix item 4). cooldown=0 isolates the churn gate.
        FakeCluster cluster = new FakeCluster(4);
        cluster.place(0, NodeId.of(0));
        cluster.place(4, NodeId.of(0));
        cluster.place(1, NodeId.of(1));
        cluster.place(5, NodeId.of(1));
        cluster.place(2, NodeId.of(2));
        cluster.place(6, NodeId.of(2));
        cluster.place(3, NodeId.of(3));
        cluster.place(7, NodeId.of(3));
        MutableClock clock = new MutableClock();
        RecordingMetrics metrics = new RecordingMetrics();

        try (LeaderBalanceLoop loop = loop(cluster, NodeId.of(0), config(2, 0L, 5_000L, false),
                clock, metrics, 1L)) {
            clock.set(0);
            loop.runOnce(); // seed terms on a balanced cluster - no action
            assertEquals(0, cluster.transfersApplied);

            // Node 0 wins every group in an election storm (bumps terms), then terms keep churning.
            for (int g = 0; g < 8; g++) {
                cluster.leaderOf.put(g, NodeId.of(0));
                cluster.bumpTerm(g);
            }
            for (long t = 1_000L; t <= 5_000L; t += 2_000L) {
                clock.set(t);
                cluster.bumpTerm(0); // ongoing churn within the window
                loop.runOnce();
            }
            assertEquals(0, cluster.transfersApplied, "no transfer may happen during an election storm");
            assertTrue(metrics.skipped(LeaderBalancePlanner.REASON_TERM_CHURN) >= 2);

            clock.set(15_000L); // terms have settled (last bump at t=5000, window 5000)
            loop.runOnce();
            assertTrue(cluster.transfersApplied >= 1, "a transfer resumes once the storm settles");
        }
    }

    @Test
    void refusedTransferFoldsIntoCooldown() {
        // The primitive declines every transfer (e.g. a config change pending - the hard floor). The loop
        // treats it as one attempt: the group is NOT moved, and cooldown suppresses an immediate retry
        // (test-matrix item 5).
        FakeCluster cluster = new FakeCluster(4);
        cluster.placeAllOn(8, NodeId.of(0));
        cluster.refuse = true;
        MutableClock clock = new MutableClock();
        RecordingMetrics metrics = new RecordingMetrics();
        try (LeaderBalanceLoop loop = loop(cluster, NodeId.of(0), config(2, 60_000L, 0L, false),
                clock, metrics, 1L)) {
            clock.set(0);
            loop.runOnce();
            assertEquals(0, cluster.transfersApplied, "a refused transfer must not move leadership");
            assertEquals(0, metrics.transfersInitiated,
                    "a refused transfer is NOT an initiation: transfers_initiated counts only drives the "
                            + "primitive accepted, so the success series is not inflated (attempts = "
                            + "initiated + refused)");
            assertEquals(1, metrics.transfersRefused);

            clock.set(30_000L); // inside cooldown - no retry storm against a change-pending group
            loop.runOnce();
            assertEquals(1, cluster.transferAttempts, "the refused group is not retried every cadence");
            assertTrue(metrics.skipped(LeaderBalancePlanner.REASON_COOLDOWN) >= 1);
        }
    }

    @Test
    void dryRun_emitsWouldTransfer_butMovesNothing() {
        // Observe-only: the would-be move is recorded, but no transfer is attempted (test-matrix item 11).
        FakeCluster cluster = new FakeCluster(4);
        cluster.placeAllOn(8, NodeId.of(0));
        MutableClock clock = new MutableClock();
        RecordingMetrics metrics = new RecordingMetrics();
        try (LeaderBalanceLoop loop = loop(cluster, NodeId.of(0), config(2, 60_000L, 0L, true),
                clock, metrics, 1L)) {
            clock.set(0);
            loop.runOnce();
            assertEquals(0, cluster.transferAttempts, "dry-run must not call the transfer path");
            assertEquals(0, cluster.transfersApplied);
            assertEquals(1, metrics.wouldTransfers);
            assertEquals(0, metrics.transfersInitiated);
        }
    }

    @Test
    void decentralized_twoShedders_convergeWithoutOscillation() {
        // {4,4,0,0}: nodes 0 and 1 are both overloaded. Running every node's own loop (each with its own
        // state) must converge to spread <= 1 with a bounded number of transfers - no ping-pong, no double
        // move (only a group's leader can move it, so two nodes never contend for the same group).
        FakeCluster cluster = new FakeCluster(4);
        for (int g = 0; g < 4; g++) {
            cluster.place(g, NodeId.of(0));
        }
        for (int g = 4; g < 8; g++) {
            cluster.place(g, NodeId.of(1));
        }
        MutableClock clock = new MutableClock();

        List<LeaderBalanceLoop> loops = new ArrayList<>();
        for (int n = 0; n < 4; n++) {
            loops.add(loop(cluster, NodeId.of(n), config(2, 60_000L, 5_000L, false),
                    clock, new RecordingMetrics(), 100L + n));
        }
        try {
            for (int cadence = 0; cadence < 60; cadence++) {
                clock.set(cadence * CADENCE_MS);
                for (LeaderBalanceLoop loop : loops) {
                    loop.runOnce();
                }
                if (cluster.spread() <= 1) {
                    break;
                }
            }
            assertTrue(cluster.spread() <= 1, "decentralized shedding must converge, spread was " + cluster.spread());
            assertTrue(cluster.transfersApplied <= 12,
                    "convergence must be bounded (no oscillation), transfers=" + cluster.transfersApplied);
        } finally {
            loops.forEach(LeaderBalanceLoop::close);
        }
    }
}
