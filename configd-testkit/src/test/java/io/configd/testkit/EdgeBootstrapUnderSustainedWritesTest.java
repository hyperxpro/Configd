package io.configd.testkit;

import io.configd.distribution.fanout.FanOutConfig;
import io.configd.store.CommandCodec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The adversarial proof: a ZERO-STATE edge joins a
 * running system under SUSTAINED CONCURRENT WRITES (writes flowing BEFORE, DURING and
 * AFTER its snapshot transfer) and must converge with no gap and no
 * duplicate-application divergence. The mechanism under proof is the production chain
 * already running on every fresh subscribe ({@code decideMode} cursor-0 =>
 * SNAPSHOT_FIRST; {@code performSnapshotTransfer} exact cutover at S; the edge core's
 * atomic cutover + tail from S+1) - this test builds no machinery, it joins a fresh
 * {@link EdgeActor} mid-run via {@link EdgeFanOutSim#joinEdge} and lets the REAL code run,
 * with the reconnect recovery loop live ({@link EdgeFanOutSim#enableEdgeRecovery}).
 *
 * <h2>The judge</h2>
 * The snapshot-delta equivalence machinery, not a parallel judge:
 * <ul>
 *   <li>per-tick THROWING safety invariants ({@link EdgeInvariants}: per-edge version
 *       monotonicity, no stale overwrite) run inside every {@code sim.tick()};</li>
 *   <li>{@link EdgeFanOutSim#finalCheck()} - every edge's store byte-equals the CP
 *       leader's authoritative store (effect equality, handoff spec exactly-once-over-effect);
 *       a skipped seq (missing effect) or a duplicate application with different effect
 *       (a unique-valued older write resurrecting) both fail it;</li>
 *   <li>scenario 1 states the equivalence claim directly: the snapshot-bootstrapped
 *       joiner is judged against a PURE-STREAM control edge (subscribed before genesis;
 *       hard-asserted zero snapshots - possible there because the ack-lag heal is
 *       disabled and the only recovery is the resubscribe path) by the SAME
 *       {@link EdgeInvariants#finalCheck} code.</li>
 * </ul>
 *
 * <h2>Non-vacuity (HARD asserts)</h2>
 * Every scenario hard-asserts its adversity actually happened: at least 1 write committed during
 * the transfer window (the cutover straddle - the window is widened deterministically by
 * lagging the joiner, the slow-consumer-during-its-own-bootstrap reality; the literal
 * big-store/small-chunks widening is the process test's lever), the joiner genuinely
 * bootstrapped via a snapshot, a faulted-channel transfer was genuinely lost mid-flight,
 * and the dup-channel scenario counts &gt;0 duplicated frames across the bootstrap
 * window. The writes carry per-write-unique values, so any duplicate application with
 * different effect (an old write resurrecting over a newer one) diverges the final
 * byte-equality.
 *
 * <h2>End-of-run discipline</h2>
 * Each scenario judges only after (1) the seed-scheduled workload is exhausted (settling
 * mid-schedule lets ops fire during the settle and the judge race a mid-replication
 * leader), (2) fence writes convert any in-flight final-delta strand into an arriving
 * gap the live recovery loop heals, (3) the CP is settled level, and (4) a bounded
 * tick loop has driven every edge to its source's version. Tick-driven throughout - no
 * sleeps.
 */
class EdgeBootstrapUnderSustainedWritesTest {

    private static final int CP_NODES = 3;
    private static final int TICKS = 1_500;

    /** Clean-CP intensity: the only adversity is what each scenario injects (plus the
     *  edge network's inherent 1 - 10 ms latency/reorder and seeded 2 - 5% dup rate). */
    private static final AdversarialSchedule.Intensity CLEAN_CP =
            new AdversarialSchedule.Intensity(0, 40, 0.0);

    /** Ticks the joiner stays lagged after its subscribe - the widened transfer window. */
    private static final int TRANSFER_WINDOW_TICKS = 12;

    /** Monotonic write counter so every pumped value is unique (double-apply tripwire). */
    private int writeCounter;

    /**
     * Ack-lag heal disabled (the EdgeGapRecoveryTest control): the only recovery
     * path is resubscribe, so a TAIL-replaying control edge provably never
     * receives a snapshot - the pure-stream side of the equivalence claim.
     */
    private static FanOutConfig noAckLagHealConfig() {
        return new FanOutConfig(64, 80, 64, 262_144, 1_000_000L, 250L, 5L, 1_048_576);
    }

    // Scenario 1: the primary equivalence proof, multiple seeds.

    @ParameterizedTest
    @ValueSource(longs = {41L, 42L, 43L, 44L})
    void zeroStateEdgeJoinsMidRunUnderSustainedWritesAndConvergesByteEqual(long seed) {
        writeCounter = 0;
        C1StreamDriver driver = new C1StreamDriver(noAckLagHealConfig());
        EdgeFanOutSim sim = new EdgeFanOutSim(seed, CP_NODES, 1, TICKS,
                false, driver, CLEAN_CP, EdgeInvariants.BOUND_MS);
        EdgeActor veteran = sim.edges().get(0);
        int cp = veteran.subscribedCpNode();
        sim.enableEdgeRecovery(0); // the production directive loop, live from the start

        // BEFORE: half the seed-scheduled workload + a pumped warm-up so the apply
        // pipeline is saturated (commits landing on nearly every tick around the join)
        // and the store the snapshot will carry is genuinely populated.
        for (int t = 0; t < TICKS / 2; t++) {
            sim.tick();
        }
        for (int t = 0; t < 40; t++) {
            pumpAndTick(sim, seed);
        }
        assertTrue(sim.cpSim().store(cp).currentVersion() > 0,
                "fixture: a populated store must exist at join");

        // The zero-state join. The next tick's drive subscribes it at cursor 0 and emits
        // the snapshot transfer at S = the source store's version at that drive.
        int joinIdx = sim.joinEdge(cp);
        EdgeActor joiner = sim.edges().get(joinIdx);
        sim.enableEdgeRecovery(joinIdx);
        assertEquals(0, joiner.currentVersion(), "the joiner is genuinely zero-state");
        joiner.lag(); // widen the transfer window deterministically (slow joiner)
        pumpAndTick(sim, seed); // T0: SUBSCRIBE -> SNAPSHOT_FIRST -> transfer emitted
        long s = sim.cpSim().store(cp).currentVersion(); // == the transfer's S

        // DURING: writes committing while the transfer is in flight / unapplied.
        for (int t = 0; t < TRANSFER_WINDOW_TICKS; t++) {
            pumpAndTick(sim, seed);
        }
        joiner.unlag();
        int guard = 0;
        while (joiner.snapshotsApplied() == 0) {
            assertTrue(++guard < 1_000, "the bootstrap transfer must land (seed " + seed + ")");
            pumpAndTick(sim, seed);
        }
        long straddleWrites = sim.cpSim().store(cp).currentVersion() - s;
        assertTrue(straddleWrites >= 1,
                "HARD non-vacuity (C5-2): at least one write must commit DURING the "
                        + "snapshot transfer; observed " + straddleWrites
                        + " (seed " + seed + ", S=" + s + ")");

        // AFTER: more sustained writes past the cutover.
        for (int t = 0; t < 30; t++) {
            pumpAndTick(sim, seed);
        }

        settleAndJudge(sim, driver);

        // The snapshot-delta EQUIVALENCE, stated directly and judged by the SAME
        // machinery: snapshot+tail-bootstrapped joiner == streamed-everything control.
        // With the ack-lag heal disabled, a TAIL-only control is structural: every
        // veteran (re)subscribe is within the 10k ring horizon => TAIL, never a snapshot.
        assertEquals(0, veteran.snapshotsApplied(),
                "control: the veteran is a PURE stream consumer (streamed from genesis)");
        assertTrue(joiner.snapshotsApplied() >= 1,
                "the joiner genuinely bootstrapped via snapshot transfer");
        assertTrue(joiner.snapshotsApplied() <= 2,
                "at most the one emitted transfer (+ at most one channel-duplicated "
                        + "idempotent re-apply); re-sends are impossible with the "
                        + "ack-lag heal disabled — observed " + joiner.snapshotsApplied());
        sim.invariants().finalCheck(List.of(joiner), veteran.snapshot());
    }

    // Scenario 2: faults on the other edges; the joiner's channel stays clean.

    @Test
    void joinUnderFaultsOnOtherEdgesBootstrapsExactlyAndEveryoneReconverges() {
        writeCounter = 0;
        long seed = 77L;
        C1StreamDriver driver = new C1StreamDriver();
        EdgeFanOutSim sim = new EdgeFanOutSim(seed, CP_NODES, 3, TICKS,
                false, driver, CLEAN_CP, EdgeInvariants.BOUND_MS);
        for (int e = 0; e < 3; e++) {
            sim.enableEdgeRecovery(e);
        }
        for (int t = 0; t < TICKS / 2; t++) {
            sim.tick();
        }
        for (int t = 0; t < 40; t++) {
            pumpAndTick(sim, seed);
        }

        // Fault the OTHER edges' channels (deterministically), writes still flowing.
        sim.partitionEdge(0);
        sim.partitionEdge(1);
        for (int t = 0; t < 20; t++) {
            pumpAndTick(sim, seed);
        }

        int joinIdx = sim.joinEdge(0);
        EdgeActor joiner = sim.edges().get(joinIdx);
        sim.enableEdgeRecovery(joinIdx);
        joiner.lag();
        pumpAndTick(sim, seed); // T0
        long s = sim.cpSim().store(0).currentVersion();
        for (int t = 0; t < TRANSFER_WINDOW_TICKS; t++) {
            pumpAndTick(sim, seed);
        }
        joiner.unlag();
        int guard = 0;
        while (joiner.snapshotsApplied() == 0) {
            assertTrue(++guard < 1_000, "the joiner must bootstrap despite faulted peers");
            pumpAndTick(sim, seed);
        }
        assertTrue(sim.cpSim().store(0).currentVersion() - s >= 1,
                "HARD non-vacuity: writes committed during the transfer");

        for (int t = 0; t < 20; t++) {
            pumpAndTick(sim, seed);
        }
        sim.healEdge(0);
        sim.healEdge(1);

        // Everyone - the clean joiner AND the healed victims - must converge byte-equal.
        settleAndJudge(sim, driver);
        assertTrue(joiner.snapshotsApplied() >= 1);
    }

    // Scenario 3: the joiner's own channel faulted - the transfer is lost mid-flight.

    @Test
    void transferLostMidFlightOnTheJoinersChannelSelfHealsToExactConvergence() {
        writeCounter = 0;
        long seed = 91L;
        C1StreamDriver driver = new C1StreamDriver();
        EdgeFanOutSim sim = new EdgeFanOutSim(seed, CP_NODES, 1, TICKS,
                false, driver, CLEAN_CP, EdgeInvariants.BOUND_MS);
        sim.enableEdgeRecovery(0);
        for (int t = 0; t < TICKS / 2; t++) {
            sim.tick();
        }
        for (int t = 0; t < 40; t++) {
            pumpAndTick(sim, seed);
        }
        assertTrue(sim.cpSim().store(0).currentVersion() > 0);

        int joinIdx = sim.joinEdge(0);
        EdgeActor joiner = sim.edges().get(joinIdx);
        sim.enableEdgeRecovery(joinIdx);
        pumpAndTick(sim, seed); // T0: the transfer is emitted, in flight (latency >= 1ms)

        // Cut the joiner's channel BEFORE the transfer can deliver (delivery re-checks
        // the partition, so the in-flight snapshot is genuinely lost - the stream driver
        // self-healing case AT BOOTSTRAP, under continuing writes).
        sim.partitionEdge(joinIdx);
        for (int t = 0; t < 30; t++) {
            pumpAndTick(sim, seed);
        }
        assertEquals(0, joiner.snapshotsApplied(),
                "HARD non-vacuity: the first transfer was genuinely lost mid-flight");
        assertEquals(0, joiner.currentVersion(), "the joiner is still empty while cut off");

        // Heal: the unacked transfer has been rebuilding ack-lag server-side; the
        // re-demote -> re-send loop must complete the bootstrap, exactly.
        sim.healEdge(joinIdx);
        long target = sim.cpSim().store(0).currentVersion();
        int guard = 0;
        while (joiner.currentVersion() < target) {
            assertTrue(++guard < 3_000, "the self-healing re-send must bootstrap the joiner");
            sim.tick();
        }
        assertTrue(joiner.snapshotsApplied() >= 1,
                "the heal was a (re-sent) snapshot bootstrap");

        settleAndJudge(sim, driver);
    }

    // Scenario 4: dup channel - every frame across the cutover is duplicated.

    @Test
    void duplicatedFramesAcrossTheCutoverNeverCauseDoubleApplicationDivergence() {
        writeCounter = 0;
        long seed = 101L;
        C1StreamDriver driver = new C1StreamDriver();
        EdgeFanOutSim sim = new EdgeFanOutSim(seed, CP_NODES, 2, TICKS,
                false, driver, CLEAN_CP, EdgeInvariants.BOUND_MS);
        sim.enableEdgeRecovery(0);
        sim.enableEdgeRecovery(1);
        for (int t = 0; t < TICKS / 2; t++) {
            sim.tick();
        }
        for (int t = 0; t < 40; t++) {
            pumpAndTick(sim, seed);
        }

        // Every CP->edge frame from here on is duplicated - the snapshot transfer,
        // NOTIFY batches, heartbeats - straddling the cutover in both directions.
        sim.setEdgeDupRateForTest(1.0);
        long dupsBefore = sim.edgeDupCount();

        int joinIdx = sim.joinEdge(0);
        EdgeActor joiner = sim.edges().get(joinIdx);
        sim.enableEdgeRecovery(joinIdx);
        joiner.lag();
        pumpAndTick(sim, seed);
        long s = sim.cpSim().store(0).currentVersion();
        for (int t = 0; t < TRANSFER_WINDOW_TICKS; t++) {
            pumpAndTick(sim, seed);
        }
        joiner.unlag();
        int guard = 0;
        while (joiner.snapshotsApplied() == 0) {
            assertTrue(++guard < 1_000, "the bootstrap must land on the dup channel");
            pumpAndTick(sim, seed);
        }
        assertTrue(sim.cpSim().store(0).currentVersion() - s >= 1,
                "HARD non-vacuity: writes committed during the transfer");
        for (int t = 0; t < 30; t++) {
            pumpAndTick(sim, seed);
        }
        long dupsAcrossBootstrap = sim.edgeDupCount() - dupsBefore;
        assertTrue(dupsAcrossBootstrap > 0,
                "HARD non-vacuity (C5-2): duplicated frames must actually cross the "
                        + "bootstrap window; observed " + dupsAcrossBootstrap);

        // Duplicates must be invisible in effect: stale-discard (idempotent-apply
        // defense-in-depth) + the exact cutover keep the final state byte-equal. Any
        // duplicate that re-applied a unique-valued older write diverges the judge;
        // any per-key version regression already threw inside tick().
        settleAndJudge(sim, driver);
        assertTrue(joiner.snapshotsApplied() >= 1);
    }

    // Helpers: deterministic, tick-driven, no sleeps.

    /**
     * One tick of sustained writes: propose one PUT (unique value per write - the
     * double-apply tripwire) at the current leader if there is one, then tick. Commits
     * land on later ticks as the Raft pipeline drains, so a continuous pump keeps
     * commits flowing on (nearly) every tick - the "sustained concurrent writes" shape.
     */
    private void pumpAndTick(EdgeFanOutSim sim, long seed) {
        int leader = sim.cpSim().findLeader();
        if (leader >= 0) {
            int i = writeCounter++;
            sim.cpSim().node(leader).propose(CommandCodec.encodePut(
                    "c5/k" + (i % 8),
                    ("w-" + seed + "-" + i).getBytes(StandardCharsets.UTF_8)));
        }
        sim.tick();
    }

    /**
     * The end-of-run judging discipline (see the class javadoc): exhaust the seed
     * schedule, fence, settle the CP level, drive every edge to its source's version
     * (bounded), then run the byte-equality judge. The fence writes matter: a
     * final-delta reorder can strand an edge one seq short with ack-lag 1 (below the
     * demote threshold) and nothing left in flight to trigger the gap heal -
     * production heals that via the staleness ladder on wall-clock timescales the sim
     * does not simulate, so the fence supplies the arriving frame that lets the
     * resubscribe recovery fire instead.
     */
    private void settleAndJudge(EdgeFanOutSim sim, C1StreamDriver driver) {
        for (int t = 0; t < TICKS; t++) {
            sim.tick(); // exhaust any still-scheduled seed workload ops
        }
        commitBlocking(sim, "c5/fence-a", "fence-a");
        commitBlocking(sim, "c5/fence-b", "fence-b");
        sim.settleCp();
        int leaderNode = sim.cpSim().findLeader();
        assertTrue(leaderNode >= 0, "a settled clean CP must have a leader");
        long target = sim.cpSim().store(leaderNode).currentVersion();
        int guard = 0;
        while (!allEdgesAt(sim, target)) {
            if (++guard >= 3_000) {
                fail("edges did not converge to " + target + " within the tick bound: "
                        + describeEdges(sim) + " resubscribes=" + driver.resubscribes()
                        + " fatal=" + driver.fatalCloses());
            }
            sim.tick();
        }
        sim.finalCheck(); // the byte-equality judge (throws with a precise diff)
    }

    private static boolean allEdgesAt(EdgeFanOutSim sim, long target) {
        for (EdgeActor e : sim.edges()) {
            if (e.alive() && e.currentVersion() < target) {
                return false;
            }
        }
        return true;
    }

    private static String describeEdges(EdgeFanOutSim sim) {
        StringBuilder sb = new StringBuilder();
        for (EdgeActor e : sim.edges()) {
            sb.append(" edge").append(e.edgeId()).append("=v").append(e.currentVersion())
                    .append("/snap").append(e.snapshotsApplied())
                    .append("/gap").append(e.gapsDetected());
        }
        return sb.toString();
    }

    /**
     * Commits one write through the REAL CP (leader propose) and ticks until EVERY CP
     * node has applied the VALUE (the EdgeGapRecoveryTest pattern, strengthened to all
     * nodes so the post-fence settle starts level).
     */
    private void commitBlocking(EdgeFanOutSim sim, String key, String value) {
        byte[] expected = value.getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 50; attempt++) {
            int leader = sim.cpSim().findLeader();
            if (leader >= 0) {
                sim.cpSim().node(leader).propose(CommandCodec.encodePut(key, expected));
            }
            for (int t = 0; t < 20; t++) {
                sim.tick();
                if (allCpNodesHold(sim, key, expected)) {
                    return;
                }
            }
        }
        fail("fence write '" + key + "' did not commit/apply on every CP node");
    }

    private static boolean allCpNodesHold(EdgeFanOutSim sim, String key, byte[] expected) {
        for (int n = 0; n < CP_NODES; n++) {
            var r = sim.cpSim().store(n).get(key);
            if (!r.found() || !java.util.Arrays.equals(expected, r.value())) {
                return false;
            }
        }
        return true;
    }
}
