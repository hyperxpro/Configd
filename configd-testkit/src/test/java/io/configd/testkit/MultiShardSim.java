package io.configd.testkit;

import io.configd.common.ConfigScope;
import io.configd.common.NodeId;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.replication.ShardMap;
import io.configd.store.ReadResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * The deterministic multi-shard simulator. Composes {@code S} independent single-group clusters -
 * each a proven {@link ConsistencyPropertyTests.ClusterHarness} of {@code R} nodes - under a
 * {@link ShardMap} that routes a deterministic client workload to the shard that owns each key. Every
 * tick it checks the cross-shard invariants AND each shard's existing safety surface
 * ({@link SimInvariants}).
 *
 * <p>The whole run is a pure function of the master {@code seed} (each shard is seeded from
 * {@code mix(seed, shardId)}, the workload from {@code mix(seed, WORKLOAD_TAG)}), so a failing seed is
 * replayable.
 *
 * <h2>The six multi-shard invariants, and how each is checked here</h2>
 * <ol>
 *   <li><b>Routing correctness</b> - every write for key K is proposed ONLY to {@code shardFor(scope,K)};
 *       {@link #routedShardOf} records the (key->shard) decision and {@link #checkRoutingStability} fails
 *       the seed if a key's shard ever changes. {@link #checkDisjointOwnership} additionally proves K's
 *       value is physically present on exactly that one shard.</li>
 *   <li><b>Disjoint ownership</b> - {@link #checkDisjointOwnership}: across every shard's committed store,
 *       no key is owned by two shards, and the owning shard equals {@code shardFor(key)}.</li>
 *   <li><b>Per-shard linearizability</b> - each shard runs its own {@link SimInvariants#checkAll()} every
 *       tick (version monotonicity, log matching, state-machine safety, single-leader-per-term) plus the
 *       throwing in-node checker.</li>
 *   <li><b>Cross-shard isolation</b> - {@link #faultShardLeader} isolates one shard's leader; the other
 *       shards must keep their safety invariants green AND keep committing ({@link #commitsAdvancedOn}).
 *       Because shards are independent harnesses, a fault cannot leak - the check proves the machinery did
 *       not wrongly couple them (e.g. via a routing leak, which {@link #checkDisjointOwnership} catches).</li>
 *   <li><b>Stale-map redirect correctness</b> - the client caches a leader per shard; when it goes stale
 *       (failover), {@link #write} redirects to the shard's current leader (intra-shard, never crossing
 *       shards) and retries. {@link #checkNoWritesLost} proves every committed-intent write landed (no
 *       loss); disjoint ownership proves redirect never scattered a key across shards (no duplicate).</li>
 *   <li><b>N=1 equivalence</b> - at {@code shardCount==1} the router resolves every key to the one group,
 *       so the sim drives a single {@link ConsistencyPropertyTests.ClusterHarness} exactly as the
 *       single-group path does; {@link MultiShardSimTest} asserts byte-identical committed state versus a
 *       bare control harness on the same per-shard seed + op stream.</li>
 * </ol>
 *
 * <p><b>Non-vacuity.</b> The machinery is proven to catch the bugs multi-shard routing could introduce: the
 * {@code injectBug(...)} flags (and the deliberately-broken routers in {@link ShardRouters}) drive a
 * deliberate mis-route / cross-shard-redirect / dropped-redirect / N=1-divergence, and
 * {@link MultiShardSimTest} asserts the corresponding check goes RED. A correct router stays green.
 *
 * <p>Not thread-safe; single sim thread, like every harness here.
 */
final class MultiShardSim {

    private static final long WORKLOAD_TAG = 0x77F1_5EEDL;
    static final ConfigScope SCOPE = ConfigScope.GLOBAL; // the only scope wired on the write path

    /** A fixed, small keyspace so hash collisions onto shards are genuinely exercised (immutable). */
    private static final String[] KEYSPACE = buildKeyspace();

    private static String[] buildKeyspace() {
        String[] ks = new String[40];
        for (int i = 0; i < ks.length; i++) {
            ks[i] = "svc/cfg/key-" + i;
        }
        return ks;
    }

    /** Deliberate bugs the test can inject to PROVE a check is non-vacuous (each must drive a RED). */
    enum Bug {
        /** Redirect crosses to a DIFFERENT shard instead of a new node in the same shard -> scatter. */
        CROSS_SHARD_REDIRECT,
        /** Never update the cached leader on reject -> a stale-leader write is lost (no-redirect). */
        NO_REDIRECT,
        /** At N=1, silently drop every Kth op -> the committed history diverges from the control. */
        DROP_OP_AT_N1
    }

    private final long seed;
    private final int shardCount;
    private final int nodesPerShard;
    private final ShardMap shardMap;
    private final Set<Bug> bugs;

    private final List<ConsistencyPropertyTests.ClusterHarness> shards = new ArrayList<>();
    private final List<SimInvariants> shardInvariants = new ArrayList<>();

    /** The client's cached leader index per shard (the stale-map surface). -1 = unknown -> discover. */
    private final int[] cachedLeader;

    private final Map<String, Integer> routedShardOf = new HashMap<>();

    /** Every write the client COMMITTED TO (kept retrying until accepted): key -> last accepted token. */
    private final Map<String, String> intendedWrites = new HashMap<>();

    /**
     * Per-shard commit-progress witness: the max applied store version across the shard's replicas (a
     * genuine new-commit signal). Not the sum -- a sum rises when a lagging replica merely catches up to an
     * existing committed version, so it would falsely report progress during a total stall. The max only
     * rises when a new entry commits and applies on the most-advanced replica.
     */
    private final long[] lastSeenMaxVersion;

    private final RandomGenerator workloadRng;

    MultiShardSim(long seed, int shardCount, int nodesPerShard, ShardMap shardMap, Set<Bug> bugs) {
        this.seed = seed;
        this.shardCount = shardCount;
        this.nodesPerShard = nodesPerShard;
        this.shardMap = shardMap;
        this.bugs = bugs;
        this.cachedLeader = new int[shardCount];
        this.lastSeenMaxVersion = new long[shardCount];
        for (int s = 0; s < shardCount; s++) {
            cachedLeader[s] = -1;
            // Two-phase wiring (mirrors SeedSweepTest.newCheckedCluster): the throwing in-node checker
            // needs the harness (for nodeCount/seed), but the harness needs the checker at construction.
            // Resolve with a forwarding checker whose target is set immediately after.
            final int shard = s;
            final long shardSeed = mix(seed, s);
            RaftNode.InvariantChecker[] target = new RaftNode.InvariantChecker[1];
            RaftNode.InvariantChecker forwarding = (name, cond, msg) -> {
                if (target[0] != null) {
                    target[0].check(name, cond, msg);
                } else if (!cond) {
                    throw new SimInvariants.SafetyViolation("IN-NODE invariant '" + name
                            + "' violated during construction (seed=" + seed + ", shard=" + shard + "): " + msg);
                }
            };
            ConsistencyPropertyTests.ClusterHarness harness =
                    new ConsistencyPropertyTests.ClusterHarness(shardSeed, nodesPerShard, forwarding);
            SimInvariants inv = new SimInvariants(harness, shardSeed);
            target[0] = inv.throwingNodeChecker();
            shards.add(harness);
            shardInvariants.add(inv);
        }
        this.workloadRng = RandomGeneratorFactory.of("L64X128MixRandom").create(mix(seed, WORKLOAD_TAG));
    }

    void electAllLeaders(int maxTicks) {
        for (int s = 0; s < shardCount; s++) {
            int leader = shards.get(s).electLeader(maxTicks);
            cachedLeader[s] = leader;
        }
    }

    /**
     * One multi-shard tick: advance every shard and check each shard's S2 - S4 safety surface (the
     * per-shard linearizability invariant). A violation throws {@link SimInvariants.SafetyViolation},
     * failing the seed with replay context.
     *
     * <p>Disjoint ownership / routing-to-owner is a GLOBAL store property that only changes when writes
     * commit, so it is checked periodically and at end of run ({@link #runWorkload}, {@link #checkAll})
     * rather than on this hot per-tick path - much cheaper, equally sound (a violation, once created by a
     * mis-route, persists in the store until the next scan).
     */
    void tick() {
        for (int s = 0; s < shardCount; s++) {
            shards.get(s).tick();
            shardInvariants.get(s).checkAll();
        }
    }

    /** A logical client write: a (clientId, key) pair; the value token is positional (op index). */
    record Op(String clientId, String key) {}

    List<Op> generateOps(int count) {
        List<Op> ops = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = KEYSPACE[workloadRng.nextInt(KEYSPACE.length)];
            ops.add(new Op("c" + workloadRng.nextInt(4), key));
        }
        return ops;
    }

    void applyOps(List<Op> ops, int ticksPerOp) {
        for (int i = 0; i < ops.size(); i++) {
            write(ops.get(i).clientId(), ops.get(i).key(), i);
            for (int t = 0; t < ticksPerOp; t++) {
                tick();
            }
            if ((i & 0xF) == 0xF) {
                checkDisjointOwnership();
            }
        }
    }

    void runWorkload(int ops, int ticksPerOp) {
        applyOps(generateOps(ops), ticksPerOp);
        healAllShards();
        drain(300); // let every accepted write commit before the no-loss assertion
        checkAll();
    }

    void checkAll() {
        checkDisjointOwnership();
        checkNoWritesLost();
    }

    /**
     * Route + (redirect-aware) propose a single logical write of {@code key} = token({@code opIndex}).
     * Returns the resolved shard.
     *
     * <p>The sharding-layer contract under test: the key resolves to exactly one shard via
     * {@link ShardMap#shardFor}, and a stale cached leader is recovered by an INTRA-shard redirect (the
     * single-group {@code X-Leader-Hint} generalized per shard) - never by crossing to another shard. The
     * value token is positional ({@code clientId:opIndex}) so a single-group control replaying the same op
     * stream writes byte-identical values (the N=1-equivalence check).
     */
    int write(String clientId, String key, int opIndex) {
        int s = shardMap.shardFor(SCOPE, key);
        if (s < 0 || s >= shardCount) {
            throw new SimInvariants.SafetyViolation("ROUTING out of range (seed=" + seed + "): key '"
                    + key + "' resolved to shard " + s + " not in [0," + shardCount + ")");
        }
        checkRoutingStability(key, s);

        // Non-vacuity for N=1 equivalence: silently drop every 7th op so the committed history diverges
        // from a faithful single-group control. Routing is still exercised above (the drop is post-route).
        if (bugs.contains(Bug.DROP_OP_AT_N1) && (opIndex % 7 == 0)) {
            return s;
        }

        String token = clientId + ":" + opIndex;

        int redirectShard = s;
        if (bugs.contains(Bug.CROSS_SHARD_REDIRECT)) {
            // Non-vacuity: a redirect that lands on the WRONG shard scatters the key -> disjoint RED.
            redirectShard = (s + 1) % shardCount;
        }

        ConsistencyPropertyTests.ClusterHarness shard = shards.get(s);
        int target = cachedLeader[s] >= 0 ? cachedLeader[s] : 0;
        boolean accepted = shard.proposePut(target, key, token);
        if (!accepted) {
            if (!bugs.contains(Bug.NO_REDIRECT)) {
                ConsistencyPropertyTests.ClusterHarness rShard = shards.get(redirectShard);
                int real = rShard.findLeader();
                if (real >= 0) {
                    if (redirectShard == s) {
                        cachedLeader[s] = real;
                    }
                    accepted = rShard.proposePut(real, key, token);
                }
            }
        }
        if (accepted) {
            intendedWrites.put(key, token);
        }
        return s;
    }

    void drain(int n) {
        for (int t = 0; t < n; t++) {
            tick();
        }
    }

    void healAllShards() {
        for (int s = 0; s < shardCount; s++) {
            shards.get(s).sim().healAllPartitions();
        }
    }

    Map<String, String> committedView() {
        Map<String, String> view = new HashMap<>();
        for (int s = 0; s < shardCount; s++) {
            for (String key : KEYSPACE) {
                String v = committedValue(shards.get(s), key);
                if (v != null) {
                    view.put(key, v);
                }
            }
        }
        return view;
    }

    int faultShardLeader(int s) {
        int leader = shards.get(s).findLeader();
        if (leader >= 0) {
            shards.get(s).sim().isolateNode(NodeId.of(leader));
            cachedLeader[s] = -1;
        }
        return leader;
    }

    /**
     * Isolate a MAJORITY of shard {@code s}'s nodes so it loses quorum and STALLS entirely (no commits) - 
     * the strong cross-shard-isolation stimulus: every other shard must keep committing while this one is
     * dead. Isolates {@code ceil((R+1)/2)} nodes, each from all others.
     */
    void faultShardMajority(int s) {
        int majority = (nodesPerShard / 2) + 1;
        for (int i = 0; i < majority; i++) {
            shards.get(s).sim().isolateNode(NodeId.of(i));
        }
        cachedLeader[s] = -1;
    }

    void healShard(int s) {
        shards.get(s).sim().healAllPartitions();
    }

    private void checkRoutingStability(String key, int s) {
        Integer prior = routedShardOf.putIfAbsent(key, s);
        if (prior != null && prior != s) {
            throw new SimInvariants.SafetyViolation("ROUTING correctness violated (seed=" + seed
                    + "): key '" + key + "' resolved to shard " + s + " but earlier to " + prior
                    + " — shardFor is not a stable function");
        }
    }

    /**
     * Disjoint ownership: scan every shard's committed store; each key must be present on at most one
     * shard, and that shard must equal {@code shardFor(key)}. Catches a routing leak / cross-shard
     * redirect (the key physically lands on a shard that does not own it).
     *
     * <p><b>Requires a PURE {@link ShardMap}</b> (the production {@link io.configd.replication.StaticShardMap}
     * is): this calls {@code shardFor} mid-scan, which would perturb a stateful router - exactly the
     * failure mode {@link ShardRouters#rotating} models, which is therefore only used in the
     * routing-stability test (it throws before any disjoint scan), never here.
     */
    void checkDisjointOwnership() {
        Map<String, Integer> ownerOf = new HashMap<>();
        for (int s = 0; s < shardCount; s++) {
            ConsistencyPropertyTests.ClusterHarness shard = shards.get(s);
            for (String key : KEYSPACE) {
                if (presentOnAnyReplica(shard, key)) {
                    Integer prior = ownerOf.putIfAbsent(key, s);
                    if (prior != null && prior != s) {
                        throw new SimInvariants.SafetyViolation("DISJOINT-OWNERSHIP violated (seed="
                                + seed + "): key '" + key + "' is present on shards " + prior + " AND "
                                + s + " — a key is owned by two shards");
                    }
                    int expected = shardMap.shardFor(SCOPE, key);
                    if (expected != s) {
                        throw new SimInvariants.SafetyViolation("ROUTING/OWNERSHIP mismatch (seed=" + seed
                                + "): key '" + key + "' is on shard " + s + " but shardFor says " + expected
                                + " — written to a shard that does not own it");
                    }
                }
            }
        }
    }

    /**
     * No write lost on redirect: every key whose write was ACCEPTED by a leader must be present, with its
     * last token, on its owning shard. A dropped/scattered redirect leaves it missing or stale -> RED.
     *
     * <p><b>Soundness precondition:</b> call only after heal + drain in a run with no post-acceptance
     * leadership loss - i.e. a fault-free run, or one fully recovered to a stable leader that retains the
     * accepted entries. A write accepted by a leader that is then isolated before replicating legitimately
     * never commits (accepted != committed) and is NOT a redirect bug, so the faulting sweeps assert
     * only {@link #checkDisjointOwnership} (always sound), never this.
     */
    void checkNoWritesLost() {
        // Iterate in sorted key order so the FIRST reported violation is stable across runs (replay
        // determinism); pass/fail is order-independent regardless.
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(intendedWrites).entrySet()) {
            String key = e.getKey();
            String token = e.getValue();
            int s = shardMap.shardFor(SCOPE, key);
            String committed = committedValue(shards.get(s), key);
            if (committed == null) {
                throw new SimInvariants.SafetyViolation("REDIRECT no-loss violated (seed=" + seed
                        + "): key '" + key + "' was accepted by a leader on shard " + s
                        + " but is absent from the committed store — write lost");
            }
            // The committed value is the LAST accepted write to key; if our token is the last intent, it
            // must match. (Overwrites by later intents are tracked in intendedWrites as the last token.)
            if (!token.equals(committed)) {
                throw new SimInvariants.SafetyViolation("REDIRECT no-loss violated (seed=" + seed
                        + "): key '" + key + "' last-accepted token '" + token + "' but committed '"
                        + committed + "' — a redirect dropped the latest write");
            }
        }
    }

    /**
     * Cross-shard isolation liveness witness: shard {@code s} made genuine new-commit progress since the
     * last call. Uses the strictly-increasing max applied version across replicas - a new entry committed
     * and applied on the most-advanced replica. A dead shard (lost quorum) cannot raise its max even as
     * lagging replicas catch up, so this correctly reports {@code false} for a stalled shard (a prior
     * sum-of-versions witness rose on catch-up and falsely reported progress).
     */
    boolean commitsAdvancedOn(int s) {
        long max = 0;
        ConsistencyPropertyTests.ClusterHarness shard = shards.get(s);
        for (int i = 0; i < nodesPerShard; i++) {
            max = Math.max(max, shard.store(i).currentVersion());
        }
        boolean advanced = max > lastSeenMaxVersion[s];
        lastSeenMaxVersion[s] = max;
        return advanced;
    }

    int shardCount() { return shardCount; }
    ConsistencyPropertyTests.ClusterHarness shard(int s) { return shards.get(s); }
    ShardMap shardMap() { return shardMap; }
    long seed() { return seed; }

    void setCachedLeader(int s, int node) { cachedLeader[s] = node; }

    String committedValueOf(String key) {
        int s = shardMap.shardFor(SCOPE, key);
        return committedValue(shards.get(s), key);
    }

    static String[] keyspace() { return KEYSPACE.clone(); }

    private String committedValue(ConsistencyPropertyTests.ClusterHarness shard, String key) {
        int leader = shard.findLeader();
        int reader = leader >= 0 ? leader : 0;
        ReadResult r = shard.store(reader).get(key);
        return r.found() ? new String(r.value(), StandardCharsets.UTF_8) : null;
    }

    private boolean presentOnAnyReplica(ConsistencyPropertyTests.ClusterHarness shard, String key) {
        for (int i = 0; i < nodesPerShard; i++) {
            if (shard.store(i).get(key).found()) {
                return true;
            }
        }
        return false;
    }

    boolean isLeaderHealthy(int s) {
        int leader = shards.get(s).findLeader();
        return leader >= 0 && shards.get(s).node(leader).role() == RaftRole.LEADER;
    }

    /**
     * SplitMix64 finalizer over (seed, tag): decorrelates each shard's stream and the workload stream
     * while staying a pure deterministic function of the master seed (the deterministic mix pattern).
     */
    static long mix(long seed, long tag) {
        long z = seed + 0x9E3779B97F4A7C15L * (tag + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
