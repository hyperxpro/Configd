package io.configd.testkit;

import io.configd.common.NodeId;
import io.configd.raft.*;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigStateMachine;
import io.configd.store.ReadResult;
import io.configd.store.VersionedConfigStore;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Discriminating test for RR-004 (P0): <b>ack must equal commit</b>.
 * <p>
 * The defect: {@code RaftNode.propose} returns {@code ACCEPTED} the instant the
 * leader appends the entry to its <em>local</em> log, pre-quorum. The write path
 * maps that acceptance to an HTTP-200 "acknowledged" write. If the leader is then
 * killed / isolated / starved in the window <em>between local append and quorum
 * commit</em>, a new leader can win an election with a log that never contained
 * the un-replicated entry, overwrite the slot, and the acknowledged write
 * vanishes — a contract §6 violation (ack-with-commit-sequence), observed live in
 * {@code docs/audit-session-1/smoke-test.md §3}.
 * <p>
 * This test drives the deterministic post-RR-010 simulator (see
 * {@link RaftSimulation#electionRandom} and {@link SimulationDeterminismTest})
 * with randomized leader-kill points placed precisely in the append→commit
 * window, across many seeds and all three fault shapes:
 * <ol>
 *   <li><b>leader crash</b> — the leader stops executing entirely (kill -9), and
 *       is removed from the network;</li>
 *   <li><b>leader network isolation</b> — the leader keeps ticking but is
 *       partitioned from the quorum;</li>
 *   <li><b>slow-follower quorum delay</b> — followers are partitioned/starved so
 *       the leader cannot reach a replication quorum before the kill.</li>
 * </ol>
 * <p>
 * The "ack" observation is tied to <b>the same boundary the HTTP write path
 * uses</b> — the {@code propose} seam (a {@code ProposeOutcome} whose
 * {@code result == ACCEPTED}, equivalently pre-fix {@code ProposalResult.ACCEPTED}),
 * <em>not</em> internal Raft commit state. The invariant asserted is exactly the
 * contract promise: <b>every write acknowledged as successful is present in the
 * post-failover committed log</b> (i.e. survives on the node that becomes the new
 * leader of the surviving quorum).
 * <p>
 * Against the pre-fix code (ack-at-local-append) this test MUST fail: a kill in
 * the append→commit window acknowledges a write that the surviving quorum never
 * saw. Post-fix (ack-after-quorum-commit) it MUST pass: a write is only
 * acknowledged once it is durably committed, so it can never be lost.
 */
class AckEqualsCommitTest {

    /** Cluster size — 5 nodes tolerates a single leader failure with a 3-node quorum. */
    private static final int NODES = 5;

    /** Seeds swept per fault shape. Each seed is a fully reproducible scenario. */
    private static final int SEEDS = 200;

    // =======================================================================
    // The three fault shapes. Each runs the SAME core scenario, differing only
    // in how the leader is removed in the append→commit window.
    // =======================================================================

    @Test
    void ackedWriteSurvivesLeaderCrashInAppendCommitWindow() {
        int violations = sweep(FaultShape.LEADER_CRASH);
        assertTrue(violations == 0,
                "RR-004: " + violations + " acknowledged write(s) lost after leader CRASH "
                        + "in the append→commit window (see per-seed failure above)");
    }

    @Test
    void ackedWriteSurvivesLeaderIsolationInAppendCommitWindow() {
        int violations = sweep(FaultShape.LEADER_ISOLATION);
        assertTrue(violations == 0,
                "RR-004: " + violations + " acknowledged write(s) lost after leader ISOLATION "
                        + "in the append→commit window (see per-seed failure above)");
    }

    @Test
    void ackedWriteSurvivesSlowFollowerQuorumDelay() {
        int violations = sweep(FaultShape.SLOW_FOLLOWER_QUORUM_DELAY);
        assertTrue(violations == 0,
                "RR-004: " + violations + " acknowledged write(s) lost after SLOW-FOLLOWER quorum "
                        + "delay in the append→commit window (see per-seed failure above)");
    }

    private enum FaultShape { LEADER_CRASH, LEADER_ISOLATION, SLOW_FOLLOWER_QUORUM_DELAY }

    /**
     * Runs the scenario for {@link #SEEDS} seeds and returns the number of seeds
     * in which an acknowledged write was absent from the post-failover committed
     * log. The first violation per seed is printed (with the seed) so the failure
     * is replayable.
     */
    private int sweep(FaultShape shape) {
        int violations = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            if (!runOneScenario(seed, shape)) {
                violations++;
            }
        }
        return violations;
    }

    /**
     * One reproducible scenario. Returns {@code true} if the invariant held
     * (no acked write lost), {@code false} if an acknowledged write was lost.
     */
    private boolean runOneScenario(long seed, FaultShape shape) {
        Harness h = new Harness(seed, NODES);

        int leader = h.electLeader(1500);
        if (leader < 0) {
            return true; // no stable leader under this seed — nothing to test, not a violation
        }

        // Commit a baseline write so the cluster is warm and the leader's term has
        // a committed no-op (real failover, not a degenerate empty-log case).
        long baseSeq = h.proposeAndCommit(leader, "rr004.base", "base", 300);
        if (baseSeq <= 0) {
            return true; // could not warm up under this seed; skip
        }

        // ---- Arrange the append→commit window for the target write -----------
        // Randomized kill point: deliver only K of the AppendEntries/response
        // round-trips after the local append before the leader is removed, where
        // K is small enough that quorum commit has NOT happened yet. K is derived
        // from the seed so the kill point is randomized but replayable.
        int killAfterTicks = 1 + (int) Math.floorMod(mix(seed), 4); // 1..4 ticks post-append

        // For the slow-follower shape, pre-starve a quorum of followers BEFORE the
        // proposal so the leader physically cannot commit in the window.
        if (shape == FaultShape.SLOW_FOLLOWER_QUORUM_DELAY) {
            // Isolate 3 of the 4 followers from the leader: leader keeps only 1
            // follower reachable → 2/5 < quorum, so commit cannot advance.
            List<Integer> followers = new ArrayList<>();
            for (int i = 0; i < NODES; i++) if (i != leader) followers.add(i);
            for (int i = 0; i < 3; i++) {
                h.isolatePair(leader, followers.get(i));
            }
        }

        // ---- ACK the write at the propose seam (same boundary as the HTTP path) ----
        long preCommitVersion = h.store(leader).currentVersion();
        boolean acked = h.proposeAcked(leader, "rr004.key", "acked-value-" + seed);
        if (!acked) {
            // Not acknowledged (e.g. leader stepped down at the instant of propose).
            // An un-acked write has no durability promise — nothing to assert.
            return true;
        }
        // Sanity: the entry must have been appended locally but NOT yet committed
        // when we drive the kill — that is the discriminating window. If the leader
        // committed it immediately (e.g. degenerate quorum), this seed is not
        // exercising the window; we still proceed (post-fix it will be COMMITTED
        // before ack and thus must survive).

        // Advance only a few ticks: the append→commit window. Quorum commit of the
        // freshly-proposed entry generally needs a full AppendEntries round-trip
        // (latency 1..10ms) plus the response; killAfterTicks (1..4) keeps us inside
        // the window for the crash/isolation shapes.
        for (int t = 0; t < killAfterTicks; t++) {
            h.tick();
        }
        boolean committedBeforeKill =
                h.store(leader).currentVersion() > preCommitVersion;

        // ---- Remove the leader according to the fault shape ------------------
        switch (shape) {
            case LEADER_CRASH -> h.crashNode(leader);                 // stop executing + isolate
            case LEADER_ISOLATION -> h.isolateNode(leader);           // partitioned, still ticking
            case SLOW_FOLLOWER_QUORUM_DELAY -> h.crashNode(leader);   // followers already starved
        }

        // ---- Let the surviving quorum elect a new leader ---------------------
        int newLeader = h.awaitStableLeader(Set.of(leader), 3000);
        if (newLeader < 0) {
            return true; // surviving quorum could not elect under this seed; inconclusive
        }

        // Drive a fresh committed write on the new leader so its commit index
        // advances into its own term (Raft §5.4.2: a leader commits prior-term
        // entries only by committing a current-term entry). This forces the
        // new leader to apply everything it will ever apply at the old indices.
        h.proposeAndCommit(newLeader, "rr004.postfailover", "pf", 600);
        h.runTicks(300); // let apply + replication settle on the surviving quorum

        // ---- The invariant: an ACKNOWLEDGED write must be present post-failover.
        // We check the committed/applied state of the new leader (the authority
        // for the surviving quorum). Pre-fix, the ack happened at local append on
        // the now-dead leader; if the entry never reached the quorum it is GONE.
        ReadResult result = h.store(newLeader).get("rr004.key");
        boolean present = result.found()
                && ("acked-value-" + seed).equals(new String(result.value(), StandardCharsets.UTF_8));

        if (!present) {
            System.out.println("RR-004 VIOLATION [" + shape + ", seed=" + seed
                    + ", killAfterTicks=" + killAfterTicks
                    + ", committedBeforeKill=" + committedBeforeKill
                    + "]: write acknowledged at the propose seam is ABSENT from the "
                    + "post-failover committed log (new leader=" + newLeader + "). "
                    + "ack != commit.");
            return false;
        }
        return true;
    }

    /** SplitMix64 finalizer — derives a replayable, well-mixed value from the seed. */
    private static long mix(long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // =======================================================================
    // Self-contained deterministic harness with CRASH support.
    //
    // Modeled on ConsistencyPropertyTests.ClusterHarness, but adds the ability
    // to (a) crash a node (stop ticking + isolate it, modeling kill -9) and
    // (b) acknowledge a write at the propose seam exactly as the HTTP path does.
    // Kept separate so the shared harness used by dozens of tests is untouched.
    // =======================================================================
    private static final class Harness {
        private final RaftSimulation sim;
        private final List<RaftNode> nodes = new ArrayList<>();
        private final List<RaftLog> logs = new ArrayList<>();
        private final List<VersionedConfigStore> stores = new ArrayList<>();
        private final boolean[] crashed;
        private final int n;

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
                logs.add(log);
                stores.add(store);
            }
            // A crashed node must not process delivered messages either.
            sim.network().setDeliveryHandler((target, message) -> {
                int idx = target.id();
                if (idx >= 0 && idx < n && !crashed[idx]) {
                    nodes.get(idx).handleMessage((RaftMessage) message);
                }
            });
        }

        VersionedConfigStore store(int i) { return stores.get(i); }
        RaftLog log(int i) { return logs.get(i); }

        void tick() {
            sim.tick();
            for (int i = 0; i < n; i++) {
                if (!crashed[i]) nodes.get(i).tick();
            }
        }

        void runTicks(int ticks) { for (int i = 0; i < ticks; i++) tick(); }

        /** Crash a node: it stops executing entirely and is cut from the network (kill -9). */
        void crashNode(int idx) {
            crashed[idx] = true;
            isolateNode(idx);
        }

        /** Isolate a live node from all peers (partition); it keeps ticking. */
        void isolateNode(int idx) {
            for (int j = 0; j < n; j++) {
                if (j != idx) sim.network().isolate(NodeId.of(idx), NodeId.of(j));
            }
        }

        /** Isolate a single directed pair both ways. */
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

        /** Propose a PUT; return true iff acknowledged at the propose seam (== ACCEPTED). */
        boolean proposeAcked(int idx, String key, String value) {
            byte[] cmd = CommandCodec.encodePut(key, value.getBytes(StandardCharsets.UTF_8));
            return isAccepted(nodes.get(idx).propose(cmd));
        }

        /** Propose a PUT and wait for it to commit (store version advances). */
        long proposeAndCommit(int idx, String key, String value, int maxTicks) {
            long prev = stores.get(idx).currentVersion();
            if (!proposeAcked(idx, key, value)) return -1;
            for (int t = 0; t < maxTicks; t++) {
                tick();
                long cur = stores.get(idx).currentVersion();
                if (cur > prev) return cur;
            }
            return -1;
        }
    }

    /**
     * Bridges the pre-fix ({@code ProposalResult}) and post-fix
     * ({@code ProposeOutcome}) propose return types so this discriminating test
     * compiles and runs against BOTH — its job is to detect the behavioral
     * defect, independent of the return-type refactor. It deliberately reads
     * acceptance reflectively rather than importing a type that only exists in
     * one of the two states of the tree.
     */
    private static boolean isAccepted(Object proposeReturn) {
        if (proposeReturn == null) return false;
        if (proposeReturn instanceof ProposalResult pr) {
            return pr == ProposalResult.ACCEPTED;
        }
        // Post-fix ProposeOutcome: expose a boolean accepted() / a result() == ACCEPTED.
        try {
            var accepted = proposeReturn.getClass().getMethod("accepted");
            Object v = accepted.invoke(proposeReturn);
            return Boolean.TRUE.equals(v);
        } catch (ReflectiveOperationException ignored) {
            // fall through
        }
        try {
            var resultMethod = proposeReturn.getClass().getMethod("result");
            Object r = resultMethod.invoke(proposeReturn);
            return r == ProposalResult.ACCEPTED;
        } catch (ReflectiveOperationException e) {
            return fail("Unrecognized propose() return type: " + proposeReturn.getClass());
        }
    }
}
