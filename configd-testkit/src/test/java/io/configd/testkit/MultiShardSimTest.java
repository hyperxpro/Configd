package io.configd.testkit;

import io.configd.replication.ShardMap;
import io.configd.replication.StaticShardMap;
import io.configd.store.ReadResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-Raft Phase 1 — the verification-machinery-FIRST test (charter §2). Proves the multi-shard
 * simulator + the six new invariants are both <b>sound</b> (GREEN under a correct router) and
 * <b>NON-VACUOUS</b> (a deliberately-broken router / injected bug drives a RED for each new invariant).
 * This test is the backlog made executable; nothing in C1–C5 starts until it is green and proven
 * non-vacuous (an injected mis-route / wrong-shard-write goes RED).
 *
 * <p>Invariant → test map:
 * <ul>
 *   <li>Routing correctness ............ {@link #routingCorrectness_keyAlwaysResolvesToOneShard} (green) /
 *       {@link #nonVacuity_nonFunctionalRouter_failsRouting} (RED)</li>
 *   <li>Disjoint ownership ............. {@link #disjointOwnership_greenAcrossSeeds} (green) /
 *       {@link #nonVacuity_crossShardRedirect_failsDisjointOwnership} (RED)</li>
 *   <li>Per-shard linearizability ...... exercised every tick by {@link SimInvariants} across the sweep
 *       ({@link #fullSurface_greenAcrossSeeds}); SimInvariants' own non-vacuity is proven by SeedSweepTest</li>
 *   <li>Cross-shard isolation .......... {@link #crossShardIsolation_oneShardStallDoesNotStopOthers} (green) /
 *       the leak that would break it is caught RED by {@link #nonVacuity_crossShardRedirect_failsDisjointOwnership}</li>
 *   <li>Stale-map redirect (exactly-once) {@link #staleLeaderRedirect_recoversTheWrite} (green) /
 *       {@link #nonVacuity_noRedirect_losesTheWrite} (RED — write lost without redirect)</li>
 *   <li>N=1 equivalence ................ {@link #nEqualsOne_byteIdenticalToSingleGroup} (green) /
 *       {@link #nonVacuity_droppedOpAtN1_divergesFromSingleGroup} (RED)</li>
 * </ul>
 */
class MultiShardSimTest {

    private static final int R = 5;            // nodes per shard (matches the single-group sweeps)
    private static final int ELECT_TICKS = 2000;

    // =============================================================================================
    // Routing correctness
    // =============================================================================================

    @Test
    void routingCorrectness_keyAlwaysResolvesToOneShard() {
        MultiShardSim sim = new MultiShardSim(1L, 4, R, new StaticShardMap(4), Set.of());
        sim.electAllLeaders(ELECT_TICKS);
        // Writing the same key 50 times must always land on the same shard (shardFor is a stable
        // function); checkRoutingStability inside write() would throw if it ever drifted.
        String key = "svc/cfg/key-7";
        int first = sim.shardMap().shardFor(MultiShardSim.SCOPE, key);
        for (int i = 0; i < 50; i++) {
            int s = sim.write("client", key, i);
            assertEquals(first, s, "key routed to a different shard on op " + i);
            sim.drain(3);
        }
        sim.healAllShards();
        sim.drain(200);
        sim.checkAll(); // disjoint ownership + routing-to-owner + no-loss all hold
    }

    /** NON-VACUITY: a router that is not a stable function fails routing correctness on the 2nd write. */
    @Test
    void nonVacuity_nonFunctionalRouter_failsRouting() {
        MultiShardSim sim = new MultiShardSim(1L, 4, R, ShardRouters.rotating(4), Set.of());
        // Routing stability is checked BEFORE proposing, so no leaders are needed: the 2nd write of the
        // same key resolves to a different shard than the 1st → SafetyViolation.
        sim.write("client", "svc/cfg/key-0", 0);
        SimInvariants.SafetyViolation ex = assertThrows(SimInvariants.SafetyViolation.class,
                () -> sim.write("client", "svc/cfg/key-0", 1),
                "a non-functional router (same key → different shard) MUST fail routing correctness");
        assertTrue(ex.getMessage().contains("ROUTING correctness"),
                "the RED must be the routing-correctness check, not an unrelated violation: " + ex.getMessage());
    }

    // =============================================================================================
    // Disjoint ownership
    // =============================================================================================

    @ParameterizedTest
    @MethodSource("smallSweep")
    void disjointOwnership_greenAcrossSeeds(long seed) {
        MultiShardSim sim = new MultiShardSim(seed, 4, R, new StaticShardMap(4), Set.of());
        sim.electAllLeaders(ELECT_TICKS);
        sim.runWorkload(80, 4); // routes 80 writes; runWorkload heals+drains+checkAll at the end
    }

    /**
     * NON-VACUITY: a redirect that crosses to the WRONG shard writes a key onto a shard that does not own
     * it → checkDisjointOwnership RED. This is also the leak that would break cross-shard isolation, so it
     * doubles as the isolation non-vacuity proof.
     */
    @Test
    void nonVacuity_crossShardRedirect_failsDisjointOwnership() {
        MultiShardSim sim = new MultiShardSim(1L, 3, R, new StaticShardMap(3),
                Set.of(MultiShardSim.Bug.CROSS_SHARD_REDIRECT));
        sim.electAllLeaders(ELECT_TICKS);
        String key = "svc/cfg/key-3";
        int s = sim.shardMap().shardFor(MultiShardSim.SCOPE, key);
        int leader = sim.shard(s).findLeader();
        // Force a stale cache (a non-leader) so write() takes the redirect path; the bug redirects to the
        // wrong shard, landing the key where shardFor does not point.
        sim.setCachedLeader(s, (leader + 1) % R);
        SimInvariants.SafetyViolation ex = assertThrows(SimInvariants.SafetyViolation.class, () -> {
            sim.write("client", key, 0);
            sim.drain(200);
            sim.checkDisjointOwnership();
        }, "a cross-shard redirect MUST fail disjoint ownership (key on a shard that does not own it)");
        assertTrue(ex.getMessage().contains("DISJOINT-OWNERSHIP")
                        || ex.getMessage().contains("ROUTING/OWNERSHIP mismatch"),
                "the RED must be the disjoint-ownership/routing-to-owner check: " + ex.getMessage());
    }

    // =============================================================================================
    // Cross-shard isolation
    // =============================================================================================

    @Test
    void crossShardIsolation_oneShardStallDoesNotStopOthers() {
        MultiShardSim sim = new MultiShardSim(7L, 3, R, new StaticShardMap(3), Set.of());
        sim.electAllLeaders(ELECT_TICKS);
        // Warm up: every shard commits, then drain so warmup apply-lag fully settles before we baseline.
        sim.applyOps(sim.generateOps(20), 5);
        sim.drain(80);
        // Reset the progress witnesses (baseline = settled version), then KILL shard 0 (majority isolated
        // → no quorum → full stall).
        for (int s = 0; s < 3; s++) sim.commitsAdvancedOn(s);
        sim.faultShardMajority(0);

        // Drive more writes while shard 0 is dead; shards 1 and 2 must keep committing AND stay safe
        // (the per-tick SimInvariants for every shard run inside sim.tick(); a leak would throw).
        sim.applyOps(sim.generateOps(60), 5);

        boolean shard0Advanced = sim.commitsAdvancedOn(0);
        boolean shard1Advanced = sim.commitsAdvancedOn(1);
        boolean shard2Advanced = sim.commitsAdvancedOn(2);
        assertFalse(shard0Advanced,
                "cross-shard isolation: shard 0 lost quorum and MUST be stalled (no commits) — if it "
                        + "advanced, the scenario degenerated and the test is not exercising isolation");
        assertTrue(shard1Advanced || shard2Advanced,
                "cross-shard isolation: a dead shard 0 must NOT stop the other shards committing");
        // Shard 0 recovers once healed (no permanent damage from the isolation). Disjoint ownership is
        // always sound; no-loss is NOT asserted here (writes accepted by shard 0's isolated old leader
        // legitimately never committed — RR-004 — which is not a redirect bug).
        sim.healAllShards();
        sim.drain(2500);
        sim.checkDisjointOwnership();
    }

    // =============================================================================================
    // Stale-map redirect correctness (exactly-once: no loss, no scatter)
    // =============================================================================================

    @Test
    void staleLeaderRedirect_recoversTheWrite() {
        MultiShardSim sim = new MultiShardSim(2L, 2, R, new StaticShardMap(2), Set.of());
        sim.electAllLeaders(ELECT_TICKS);
        String key = "svc/cfg/key-5";
        int s = sim.shardMap().shardFor(MultiShardSim.SCOPE, key);
        int leader = sim.shard(s).findLeader();
        sim.setCachedLeader(s, (leader + 1) % R); // stale: a non-leader
        sim.write("client", key, 0);             // rejected by the non-leader → redirect → retry on leader
        sim.drain(200);
        assertEquals("client:0", sim.committedValueOf(key),
                "a correct intra-shard redirect must recover the write (no loss)");
        sim.checkAll(); // and never scattered the key across shards
    }

    /** NON-VACUITY: with redirect DISABLED, the stale-leader write is never accepted → lost. */
    @Test
    void nonVacuity_noRedirect_losesTheWrite() {
        MultiShardSim sim = new MultiShardSim(2L, 2, R, new StaticShardMap(2),
                Set.of(MultiShardSim.Bug.NO_REDIRECT));
        sim.electAllLeaders(ELECT_TICKS);
        String key = "svc/cfg/key-5";
        int s = sim.shardMap().shardFor(MultiShardSim.SCOPE, key);
        int leader = sim.shard(s).findLeader();
        sim.setCachedLeader(s, (leader + 1) % R);
        sim.write("client", key, 0); // rejected by the non-leader; no redirect → never reaches a leader
        sim.drain(200);
        assertNull(sim.committedValueOf(key),
                "without redirect, the stale-leader write MUST be lost (the non-vacuity proof)");
    }

    // =============================================================================================
    // N=1 equivalence (byte-identical to the single-group path)
    // =============================================================================================

    @ParameterizedTest
    @MethodSource("smallSweep")
    void nEqualsOne_byteIdenticalToSingleGroup(long seed) {
        // Generate one op stream, drive it through BOTH the N=1 multi-shard sim and a bare single-group
        // control on the identical per-shard seed; the committed key→value views must be identical.
        MultiShardSim sim = new MultiShardSim(seed, 1, R, new StaticShardMap(1), Set.of());
        List<MultiShardSim.Op> ops = sim.generateOps(60);
        sim.electAllLeaders(ELECT_TICKS);
        sim.applyOps(ops, 5);
        sim.healAllShards();
        sim.drain(300);
        Map<String, String> multi = sim.committedView();

        Map<String, String> control = singleGroupControl(seed, ops, 5);
        assertTrue(multi.size() >= 5,
                "N=1 equivalence would be vacuous if nothing committed (seed=" + seed + ", committed="
                        + multi.size() + ") — the cluster must elect + commit for the comparison to mean anything");
        assertEquals(control, multi,
                "N=1 multi-shard committed state must be byte-identical to the single-group control");
    }

    /** NON-VACUITY: at N=1, dropping ops makes the multi-shard committed state diverge from the control. */
    @Test
    void nonVacuity_droppedOpAtN1_divergesFromSingleGroup() {
        // Deterministic: DROP_OP_AT_N1 skips every 7th op (indices 0,7,14,…); divergence requires at
        // least one skipped op to be the LAST writer of its key. With 60 ops over a 40-key space, seed 3
        // deterministically satisfies that — the sim is a pure function of the seed, so this is stable.
        long seed = 3L;
        MultiShardSim sim = new MultiShardSim(seed, 1, R, new StaticShardMap(1),
                Set.of(MultiShardSim.Bug.DROP_OP_AT_N1));
        List<MultiShardSim.Op> ops = sim.generateOps(60);
        sim.electAllLeaders(ELECT_TICKS);
        sim.applyOps(ops, 5);
        sim.healAllShards();
        sim.drain(300);
        Map<String, String> multi = sim.committedView();

        Map<String, String> control = singleGroupControl(seed, ops, 5);
        assertNotEquals(control, multi,
                "dropping ops at N=1 MUST diverge from the single-group control (the non-vacuity proof)");
    }

    // =============================================================================================
    // The integrated full-surface sweep (all invariants, every seed) + vacuity guard
    // =============================================================================================

    @ParameterizedTest
    @MethodSource("fullSweep")
    void fullSurface_greenAcrossSeeds(long seed) {
        MultiShardSim sim = new MultiShardSim(seed, 4, R, new StaticShardMap(4), Set.of());
        sim.electAllLeaders(ELECT_TICKS);
        // A workload with a mid-run per-shard fault: routing + disjoint + per-shard safety + redirect
        // (the fault staled the cache) + cross-shard isolation are all on the surface every tick/seed.
        sim.applyOps(sim.generateOps(40), 4);
        sim.faultShardLeader(seed % 4 == 0 ? 1 : 0);
        sim.applyOps(sim.generateOps(40), 4);
        sim.healAllShards();
        sim.drain(400);
        // Disjoint ownership is always sound; no-loss is not asserted here (a mid-run leader fault can
        // leave an accepted-but-uncommitted write that legitimately never commits — RR-004).
        sim.checkDisjointOwnership();
    }

    /** Vacuity guard: the sweep must actually commit writes on every shard (else it would pass empty). */
    @Test
    void sweepIsNotVacuous() {
        MultiShardSim sim = new MultiShardSim(11L, 4, R, new StaticShardMap(4), Set.of());
        sim.electAllLeaders(ELECT_TICKS);
        sim.runWorkload(120, 4);
        Map<String, String> committed = sim.committedView();
        assertTrue(committed.size() >= 10,
                "the workload must commit a healthy number of distinct keys (got " + committed.size()
                        + ") — else the sweep is passing vacuously");
        // And the keys are spread across more than one shard (sharding actually happened).
        long shardsUsed = committed.keySet().stream()
                .map(k -> sim.shardMap().shardFor(MultiShardSim.SCOPE, k))
                .distinct().count();
        assertTrue(shardsUsed >= 2,
                "committed keys must span >= 2 shards (got " + shardsUsed + ") — sharding is exercised");
    }

    // =============================================================================================
    // Helpers
    // =============================================================================================

    static LongStream smallSweep() {
        return LongStream.range(0, Integer.getInteger("configd.multiShard.smallSweep.count", 12));
    }

    static LongStream fullSweep() {
        // Default small for fast PR CI; C5 cranks this to >=10k via the system property (gate nightly).
        return LongStream.range(0, Integer.getInteger("configd.multiShard.seedSweep.count", 40));
    }

    /**
     * A bare single-group control: replays the SAME op stream (positional tokens, intra-shard redirect)
     * against one {@link ConsistencyPropertyTests.ClusterHarness} seeded EXACTLY as the N=1 multi-shard
     * sim seeds its one shard ({@code mix(seed,0)}). With the same seed + ops, the committed view must
     * match the N=1 multi-shard view — proving the routing layer is transparent at N=1.
     */
    private static Map<String, String> singleGroupControl(long seed, List<MultiShardSim.Op> ops, int ticksPerOp) {
        ConsistencyPropertyTests.ClusterHarness h = new ConsistencyPropertyTests.ClusterHarness(
                MultiShardSim.mix(seed, 0), R, io.configd.raft.RaftNode.InvariantChecker.NOOP);
        h.electLeader(ELECT_TICKS);
        int cached = h.findLeader();
        for (int i = 0; i < ops.size(); i++) {
            String key = ops.get(i).key();
            String token = ops.get(i).clientId() + ":" + i;
            int target = cached >= 0 ? cached : 0;
            boolean accepted = h.proposePut(target, key, token);
            if (!accepted) {
                int real = h.findLeader();
                if (real >= 0) {
                    cached = real;
                    h.proposePut(real, key, token);
                }
            }
            for (int t = 0; t < ticksPerOp; t++) {
                h.tick();
            }
        }
        h.sim().healAllPartitions();
        for (int t = 0; t < 300; t++) {
            h.tick();
        }
        Map<String, String> view = new HashMap<>();
        int reader = h.findLeader();
        if (reader < 0) reader = 0;
        for (String key : MultiShardSim.keyspace()) {
            ReadResult r = h.store(reader).get(key);
            if (r.found()) {
                view.put(key, new String(r.value(), StandardCharsets.UTF_8));
            }
        }
        return view;
    }
}
