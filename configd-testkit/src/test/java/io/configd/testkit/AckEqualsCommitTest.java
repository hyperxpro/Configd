package io.configd.testkit;

import io.configd.common.NodeId;
import io.configd.raft.*;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigStateMachine;
import io.configd.store.ReadResult;
import io.configd.store.VersionedConfigStore;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Discriminating safety test: <b>ack must equal commit</b>.
 * <p>
 * The defect: an HTTP-200 "acknowledged" write was produced the instant the
 * leader appended the entry to its <em>local</em> log, pre-quorum
 * ({@code RaftNode.propose} returning ACCEPTED -> {@code WriteResult.Accepted} ->
 * 200). If the leader was then killed / isolated / starved in the window
 * <em>between local append and quorum commit</em>, a new leader could win with a
 * log that never contained the un-replicated entry, overwrite the slot, and the
 * acknowledged write vanished.
 * <p>
 * This test drives the deterministic simulator (see
 * {@link RaftSimulation#electionRandom} and {@link SimulationDeterminismTest})
 * with randomized leader-kill points placed precisely in the append->commit
 * window, across many seeds and all three fault shapes (leader crash, leader
 * network isolation, slow-follower quorum delay).
 * <p>
 * <b>The "ack" boundary is the same one the HTTP write path uses.</b> The fix
 * moves acknowledgement from local-append to commit-confirmation, so this test
 * observes acknowledgement at the boundary that is live <em>in this tree</em>:
 * <ul>
 *   <li><b>Post-fix:</b> the {@code RaftNode.whenCommitOutcome} seam - the exact
 *       seam {@code ConfigWriteService} blocks on. A write is "acknowledged"
 *       (i.e. the client would receive a 200) <em>only</em> when the seam
 *       reports {@code COMMITTED}. {@code LOST} / {@code INDETERMINATE_LOCALLY}
 *       are 503/504, not acks.</li>
 *   <li><b>Pre-fix:</b> the seam does not exist; the HTTP ack was the bare
 *       {@code propose()} acceptance at local append, so that is the ack
 *       boundary.</li>
 * </ul>
 * The invariant asserted is exactly the contract promise: <b>every write
 * acknowledged as successful is present in the post-failover committed log</b>.
 * <p>
 * Pre-fix (ack-at-local-append) this MUST fail: a kill in the append->commit
 * window acknowledges writes the surviving quorum never saw. Post-fix
 * (ack-after-quorum-commit) it MUST pass: a write killed in that window resolves
 * as LOST/INDETERMINATE (correctly NOT acknowledged), and a write reported
 * COMMITTED is - by construction - already durable, so it always survives. A
 * non-vacuity guard additionally requires that the sweep genuinely commits and
 * survives writes (so "no acked write lost" is not passing because nothing was
 * ever acked).
 */
class AckEqualsCommitTest {

    /** Cluster size - 5 nodes tolerates a single leader failure with a 3-node quorum. */
    private static final int NODES = 5;

    /** Seeds swept per fault shape. Each seed is a fully reproducible scenario. */
    private static final int SEEDS = 200;

    @Test
    void ackedWriteSurvivesLeaderCrashInAppendCommitWindow() {
        assertNoAckedWriteLost(sweep(FaultShape.LEADER_CRASH), "leader CRASH");
    }

    @Test
    void ackedWriteSurvivesLeaderIsolationInAppendCommitWindow() {
        assertNoAckedWriteLost(sweep(FaultShape.LEADER_ISOLATION), "leader ISOLATION");
    }

    @Test
    void ackedWriteSurvivesSlowFollowerQuorumDelay() {
        assertNoAckedWriteLost(sweep(FaultShape.SLOW_FOLLOWER_QUORUM_DELAY), "SLOW-FOLLOWER quorum delay");
    }

    private static void assertNoAckedWriteLost(SweepResult r, String shape) {
        assertTrue(r.violations == 0,
                "RR-004: " + r.violations + " write(s) ACKNOWLEDGED as committed but ABSENT after "
                        + shape + " in the append→commit window " + r);
        // Non-vacuity: the sweep must genuinely acknowledge-and-survive writes, so
        // "no acked write lost" cannot pass because nothing was ever acked. Every
        // non-inconclusive seed produces at least one acknowledged-committed write
        // (the baseline) that has to survive failover.
        assertTrue(r.ackedAndSurvived > 0,
                "RR-004: non-vacuity — the sweep acknowledged ZERO committed writes (" + r
                        + "); the survival invariant would be vacuously true. " + shape);
    }

    private enum FaultShape { LEADER_CRASH, LEADER_ISOLATION, SLOW_FOLLOWER_QUORUM_DELAY }

    /** Aggregate sweep outcome over all seeds for one fault shape. */
    private record SweepResult(int violations, int acked, int notAcked, int inconclusive,
                               int ackedAndSurvived) {
        @Override public String toString() {
            return "[violations=" + violations + ", targetAcked(committed)=" + acked
                    + ", targetNotAcked(lost/indeterminate)=" + notAcked
                    + ", inconclusive=" + inconclusive
                    + ", ackedCommittedWritesThatSurvived=" + ackedAndSurvived + "]";
        }
    }

    private SweepResult sweep(FaultShape shape) {
        int violations = 0, acked = 0, notAcked = 0, inconclusive = 0, ackedAndSurvived = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            ScenarioResult sr = runOneScenario(seed, shape);
            switch (sr.outcome()) {
                case VIOLATION -> { violations++; acked++; }
                case ACKED_AND_PRESENT -> acked++;
                case NOT_ACKED -> notAcked++;
                case INCONCLUSIVE -> inconclusive++;
            }
            // Every non-inconclusive seed produced at least one acknowledged-
            // committed write (the baseline) that had to survive failover; count
            // them so non-vacuity holds for all shapes (incl. slow-follower, where
            // the TARGET write is intentionally never committed).
            ackedAndSurvived += sr.ackedCommittedSurvivors();
        }
        return new SweepResult(violations, acked, notAcked, inconclusive, ackedAndSurvived);
    }

    private enum Outcome { VIOLATION, ACKED_AND_PRESENT, NOT_ACKED, INCONCLUSIVE }

    /** Per-seed result: the target-write outcome plus how many acked writes survived. */
    private record ScenarioResult(Outcome outcome, int ackedCommittedSurvivors) {}

    private ScenarioResult runOneScenario(long seed, FaultShape shape) {
        Harness h = new Harness(seed, NODES);

        int leader = h.electLeader(1500);
        if (leader < 0) {
            return new ScenarioResult(Outcome.INCONCLUSIVE, 0); // no stable leader under this seed
        }

        // Warm up: a committed baseline so the leader's term has a committed entry
        // (real failover, not a degenerate empty-log case). This baseline write is
        // also our non-vacuity witness: it commits cleanly and must survive.
        AckObserver baseline = h.ackObserver();
        long baseSeq = h.proposeAndAwaitAck(leader, "rr004.base", "base", 400, baseline);
        if (baseSeq <= 0 || !baseline.committed()) {
            return new ScenarioResult(Outcome.INCONCLUSIVE, 0); // could not warm up under this seed
        }

        // Randomized kill point inside the append->commit window (1..4 ticks after
        // local append), derived from the seed so it is randomized but replayable.
        int killAfterTicks = 1 + (int) Math.floorMod(mix(seed), 4);

        if (shape == FaultShape.SLOW_FOLLOWER_QUORUM_DELAY) {
            // Pre-starve a quorum of followers so the leader cannot commit the
            // target write in the window: leader keeps only 1 follower reachable.
            List<Integer> followers = new ArrayList<>();
            for (int i = 0; i < NODES; i++) if (i != leader) followers.add(i);
            for (int i = 0; i < 3; i++) {
                h.isolatePair(leader, followers.get(i));
            }
        }

        // Propose the target write and observe its ACK at the live boundary.
        // Post-fix: register whenCommitOutcome (the seam the HTTP path blocks on).
        // Pre-fix: that seam is absent; the ack is the propose acceptance.
        AckObserver obs = h.ackObserver();
        boolean appended = h.propose(leader, "rr004.key", "acked-value-" + seed, obs);
        if (!appended) {
            return new ScenarioResult(Outcome.INCONCLUSIVE, 0); // leader stepped down at propose
        }

        // Advance the append->commit window, then remove the leader.
        for (int t = 0; t < killAfterTicks; t++) {
            h.tick();
        }
        switch (shape) {
            case LEADER_CRASH -> h.crashNode(leader);
            case LEADER_ISOLATION -> h.isolateNode(leader);
            case SLOW_FOLLOWER_QUORUM_DELAY -> h.crashNode(leader);
        }

        int newLeader = h.awaitStableLeader(Set.of(leader), 3000);
        if (newLeader < 0) {
            return new ScenarioResult(Outcome.INCONCLUSIVE, 0); // surviving quorum could not elect
        }
        // Commit a fresh current-term entry on the new leader (Raft section 5.4.2) so it
        // applies everything it will ever apply at the old indices, and settle.
        h.proposeAndAwaitAck(newLeader, "rr004.postfailover", "pf", 600, h.ackObserver());
        h.runTicks(400);

        // The target write's ack outcome is now resolved (or still pending - in
        // which case it was never acknowledged, by definition).
        boolean acknowledged = obs.committed();

        ReadResult result = h.store(newLeader).get("rr004.key");
        boolean present = result.found()
                && ("acked-value-" + seed).equals(new String(result.value(), StandardCharsets.UTF_8));

        // Non-vacuity: the baseline write was acknowledged-committed; it must
        // survive on the new leader too (a real surviving committed write).
        ReadResult baseResult = h.store(newLeader).get("rr004.base");
        boolean baselineSurvived = baseResult.found()
                && "base".equals(new String(baseResult.value(), StandardCharsets.UTF_8));
        if (!baselineSurvived) {
            System.out.println("RR-004 VIOLATION [" + shape + ", seed=" + seed
                    + "]: a cleanly COMMITTED (acknowledged) baseline write was lost after failover.");
            return new ScenarioResult(Outcome.VIOLATION, 0);
        }
        // The baseline is one acknowledged-committed write that survived failover.
        int survivors = 1;

        if (acknowledged) {
            if (!present) {
                System.out.println("RR-004 VIOLATION [" + shape + ", seed=" + seed
                        + ", killAfterTicks=" + killAfterTicks
                        + "]: write ACKNOWLEDGED as committed at the HTTP ack boundary is ABSENT "
                        + "from the post-failover committed log (new leader=" + newLeader
                        + "). ack != commit.");
                return new ScenarioResult(Outcome.VIOLATION, survivors);
            }
            return new ScenarioResult(Outcome.ACKED_AND_PRESENT, survivors + 1);
        }
        // Not acknowledged (LOST / INDETERMINATE / still-pending) - no durability
        // promise was made; the client correctly received a non-200.
        return new ScenarioResult(Outcome.NOT_ACKED, survivors);
    }

    /** SplitMix64 finalizer - derives a replayable, well-mixed value from the seed. */
    private static long mix(long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // Ack observer: abstracts the ack boundary so the SAME test runs against
    // both states of the tree (pre-fix: propose-accept; post-fix: commit seam).

    /**
     * Records whether a proposed write was ACKNOWLEDGED as committed at the HTTP
     * ack boundary. Post-fix this is fed by {@code whenCommitOutcome} (COMMITTED
     * only); pre-fix, where no commit seam exists, acceptance at local append IS
     * the ack, so the harness marks it committed on propose-accept.
     */
    private static final class AckObserver {
        private volatile boolean committed;
        boolean committed() { return committed; }
        void markCommitted() { committed = true; }
    }

    // Self-contained deterministic harness with CRASH + commit-seam support.
    // Modeled on ConsistencyPropertyTests.ClusterHarness; kept separate so the
    // shared harness used by dozens of tests is untouched.
    private static final class Harness {
        private final RaftSimulation sim;
        private final List<RaftNode> nodes = new ArrayList<>();
        private final List<VersionedConfigStore> stores = new ArrayList<>();
        private final boolean[] crashed;
        private final int n;
        /** Reflective handle to whenCommitOutcome(long,long,Consumer) when present (post-fix). */
        private final Method whenCommitOutcome;
        private final Object committedKind; // CommitOutcome.Kind.COMMITTED, or null pre-fix

        Harness(long seed, int nodeCount) {
            this.sim = new RaftSimulation(seed, nodeCount);
            this.n = nodeCount;
            this.crashed = new boolean[nodeCount];
            for (int i = 0; i < nodeCount; i++) {
                NodeId id = NodeId.of(i);
                Set<NodeId> peers = new HashSet<>();
                for (int j = 0; j < nodeCount; j++) if (j != i) peers.add(NodeId.of(j));

                RaftConfig config = RaftConfig.of(id, peers);
                RaftLog log = new RaftLog();
                VersionedConfigStore store = new VersionedConfigStore();
                ConfigStateMachine sm = new ConfigStateMachine(store);
                RaftTransport transport = (target, message) ->
                        sim.network().send(id, target, message, sim.clock().currentTimeMillis());
                RaftNode node = new RaftNode(config, log, transport, sm, sim.electionRandom(id));
                nodes.add(node);
                stores.add(store);
            }
            sim.network().setDeliveryHandler((target, message) -> {
                int idx = target.id();
                if (idx >= 0 && idx < n && !crashed[idx]) {
                    nodes.get(idx).handleMessage((RaftMessage) message);
                }
            });
            this.whenCommitOutcome = resolveWhenCommitOutcome();
            this.committedKind = resolveCommittedKind();
        }

        private static Method resolveWhenCommitOutcome() {
            try {
                return RaftNode.class.getMethod("whenCommitOutcome",
                        long.class, long.class, java.util.function.Consumer.class);
            } catch (NoSuchMethodException e) {
                return null; // pre-fix tree: no commit-outcome seam
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Object resolveCommittedKind() {
            try {
                Class<?> kind = Class.forName("io.configd.raft.CommitOutcome$Kind");
                return Enum.valueOf((Class<Enum>) kind, "COMMITTED");
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        VersionedConfigStore store(int i) { return stores.get(i); }
        AckObserver ackObserver() { return new AckObserver(); }

        void tick() {
            sim.tick();
            for (int i = 0; i < n; i++) {
                if (!crashed[i]) nodes.get(i).tick();
            }
        }

        void runTicks(int ticks) { for (int i = 0; i < ticks; i++) tick(); }

        void crashNode(int idx) {
            crashed[idx] = true;
            isolateNode(idx);
        }

        void isolateNode(int idx) {
            for (int j = 0; j < n; j++) {
                if (j != idx) sim.network().isolate(NodeId.of(idx), NodeId.of(j));
            }
        }

        void isolatePair(int a, int b) {
            sim.network().isolate(NodeId.of(a), NodeId.of(b));
        }

        int findLeader(Set<Integer> exclude) {
            for (int i = 0; i < n; i++) {
                if (exclude.contains(i) || crashed[i]) continue;
                if (nodes.get(i).role() == RaftRole.LEADER) return i;
            }
            return -1;
        }

        int electLeader(int maxTicks) { return awaitStableLeader(Set.of(), maxTicks); }

        int awaitStableLeader(Set<Integer> exclude, int maxTicks) {
            int stable = 0, candidate = -1;
            for (int t = 0; t < maxTicks; t++) {
                tick();
                int leader = findLeader(exclude);
                if (leader >= 0 && leader == candidate) {
                    if (++stable >= 120) return leader;
                } else if (leader >= 0) {
                    candidate = leader;
                    stable = 1;
                } else {
                    candidate = -1;
                    stable = 0;
                }
            }
            return -1;
        }

        /**
         * Propose a PUT on {@code idx}. Wires the ack observer to the live ack
         * boundary: post-fix registers whenCommitOutcome (marks committed only on
         * COMMITTED); pre-fix, acceptance at local append is the ack. Returns true
         * iff the entry was appended (propose accepted).
         */
        boolean propose(int idx, String key, String value, AckObserver obs) {
            byte[] cmd = CommandCodec.encodePut(key, value.getBytes(StandardCharsets.UTF_8));
            ProposeOutcome outcome = nodes.get(idx).propose(cmd);
            if (!outcome.accepted()) {
                return false;
            }
            if (whenCommitOutcome != null && committedKind != null) {
                // Post-fix: the HTTP ack boundary is the commit seam.
                AtomicReference<Object> kindRef = new AtomicReference<>();
                try {
                    whenCommitOutcome.invoke(nodes.get(idx), outcome.index(), outcome.term(),
                            (java.util.function.Consumer<Object>) co -> {
                                try {
                                    Object kind = co.getClass().getMethod("kind").invoke(co);
                                    kindRef.set(kind);
                                    if (committedKind.equals(kind)) {
                                        obs.markCommitted();
                                    }
                                } catch (ReflectiveOperationException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException("whenCommitOutcome invocation failed", e);
                }
            } else {
                // Pre-fix: acceptance at local append IS the ack (the defect).
                obs.markCommitted();
            }
            return true;
        }

        /** Propose and tick until the store version advances (commit), or timeout. */
        long proposeAndAwaitAck(int idx, String key, String value, int maxTicks, AckObserver obs) {
            long prev = stores.get(idx).currentVersion();
            if (!propose(idx, key, value, obs)) return -1;
            for (int t = 0; t < maxTicks; t++) {
                tick();
                long cur = stores.get(idx).currentVersion();
                if (cur > prev) return cur;
            }
            return -1;
        }
    }
}
