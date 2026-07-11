package io.configd.testkit;

import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntPredicate;

/**
 * Continuous edge-data-plane invariant checker for {@link EdgeFanOutSim}, mirroring
 * {@link SimInvariants}' style and seed-message discipline. {@link #checkAll} runs
 * after every {@code EdgeFanOutSim} tick; a <b>safety</b> breach throws
 * {@link SimInvariants.SafetyViolation} (fails the seed with replay context).
 * <b>Liveness</b> (eventual delivery) is recorded into {@link EdgeActivity}, never
 * thrown.
 *
 * <h2>Safety invariants (throw)</h2>
 * <ul>
 *   <li><b>(a) Per-edge version monotonicity ({@code monotonic_read}).</b> Within an edge
 *       incarnation, {@link EdgeActor#currentVersion()} never decreases tick-to-tick. The
 *       read-side half - a cursor-bound read never returning a version below the
 *       cursor - is enforced by the test-mode {@code InvariantMonitor} wired into
 *       the edge's read {@code LocalConfigStore} (it throws inside
 *       {@link EdgeActor#get}). A crash resets the incarnation, after which a
 *       version drop to 0 is expected and not a violation.</li>
 *   <li><b>(b) No stale overwrite ({@code per_key_order}).</b>
 *       Per edge, per key, the applied {@link VersionedValue#version()} never
 *       decreases across ticks within an incarnation (full-store diff per tick).</li>
 *   <li><b>(c) Snapshot - delta convergence.</b> An
 *       end-of-run check ({@link #finalCheck}): after a heal-all + drain window,
 *       every live edge's store content (key -> value bytes + version) byte-equals
 *       the CP leader's authoritative store.</li>
 * </ul>
 *
 * <h2>Liveness (record, never throw)</h2>
 * <ul>
 *   <li><b>(d) Eventual delivery bound.</b> For every
 *       {@link io.configd.distribution.CommitNotification} published at an edge's
 *       subscribed CP node at sim time T, a live + connected + non-lagging edge must
 *       observe it (cursor >= seq) by {@code T + BOUND_MS}. A miss is recorded into
 *       {@link EdgeActivity} with (seed, seq, edgeId, lateness). With
 *       {@link StreamDriver#NONE} every publication violates - the executable
 *       backlog.</li>
 * </ul>
 *
 * <p>Not thread-safe; single sim thread.
 */
final class EdgeInvariants {

    /** Default eventual-delivery bound (the p99 propagation budget). */
    static final long BOUND_MS = 500;

    private final long seed;
    private final long boundMs;
    private final EdgeActivity activity;

    /** Identity of an edge incarnation: bumps when an edge crashes. */
    private record Incarnation(int edgeId, int incarnation) {}

    /** Highest store version seen per edge incarnation (invariant a). */
    private final Map<Incarnation, Long> lastVersionPerIncarnation = new HashMap<>();

    /** Per edge incarnation: key -> highest applied version (invariant b). */
    private final Map<Incarnation, Map<String, Long>> lastKeyVersionPerIncarnation = new HashMap<>();

    /**
     * Outstanding publications awaiting observation (invariant d). One entry per
     * (cpNode, seq); checked each tick against every eligible edge. Removed once
     * every subscribed edge has observed it OR been recorded as a violation, so the
     * list stays bounded by the in-flight window.
     */
    private final List<Outstanding> outstanding = new ArrayList<>();

    /** A published notification awaiting edge observation. */
    private static final class Outstanding {
        final long seq;
        final int cpNode;
        final long publishedAtMs;
        /** Edges (by id) that still owe observation of this seq. */
        final List<Integer> owingEdgeIds = new ArrayList<>();

        Outstanding(long seq, int cpNode, long publishedAtMs) {
            this.seq = seq;
            this.cpNode = cpNode;
            this.publishedAtMs = publishedAtMs;
        }
    }

    EdgeInvariants(long seed, EdgeActivity activity) {
        this(seed, activity, BOUND_MS);
    }

    EdgeInvariants(long seed, EdgeActivity activity, long boundMs) {
        this.seed = seed;
        this.activity = activity;
        this.boundMs = boundMs;
    }

    /**
     * Records that a notification with {@code seq} was published at CP node
     * {@code cpNode} at {@code publishedAtMs}. {@code subscribedEdgeIds} are the live
     * edges subscribed to {@code cpNode} at publish time that owe observation.
     */
    void recordPublication(long seq, int cpNode, long publishedAtMs, List<Integer> subscribedEdgeIds) {
        if (subscribedEdgeIds.isEmpty()) {
            return; // nobody to deliver to - no liveness obligation
        }
        Outstanding o = new Outstanding(seq, cpNode, publishedAtMs);
        o.owingEdgeIds.addAll(subscribedEdgeIds);
        outstanding.add(o);
    }

    /**
     * Runs every per-tick edge invariant.
     *
     * @param edges      the edge roster (deterministic order)
     * @param nowMs      current sim logical time
     * @param connected  predicate: is edge id currently connected to its CP node
     *                   (not partitioned on the edge network)?
     */
    void checkAll(List<EdgeActor> edges, long nowMs, IntPredicate connected) {
        checkPerEdgeVersionMonotonicity(edges); // (a)
        checkNoStaleOverwrite(edges);           // (b)
        checkEventualDelivery(edges, nowMs, connected); // (d) - recorded, never thrown
    }

    // ---- (a) per-edge version monotonicity ---------------------------------

    private void checkPerEdgeVersionMonotonicity(List<EdgeActor> edges) {
        for (EdgeActor edge : edges) {
            if (!edge.alive()) {
                continue; // crashed: no store to read; restart re-incarnates
            }
            Incarnation inc = new Incarnation(edge.edgeId(), edge.incarnation());
            long v = edge.currentVersion();
            Long prev = lastVersionPerIncarnation.get(inc);
            if (prev != null && v < prev) {
                throw new SimInvariants.SafetyViolation(
                        "edge version monotonicity violated (seed=" + seed + ") at edge "
                                + edge.edgeId() + " incarnation " + edge.incarnation()
                                + ": version went " + prev + " -> " + v);
            }
            lastVersionPerIncarnation.put(inc, v);
        }
    }

    // ---- (b) no stale overwrite (per edge, per key) ------------------------

    private void checkNoStaleOverwrite(List<EdgeActor> edges) {
        for (EdgeActor edge : edges) {
            if (!edge.alive()) {
                continue;
            }
            Incarnation inc = new Incarnation(edge.edgeId(), edge.incarnation());
            Map<String, Long> seen =
                    lastKeyVersionPerIncarnation.computeIfAbsent(inc, k -> new HashMap<>());
            ConfigSnapshot snap = edge.snapshot();
            HamtMap<String, VersionedValue> data = snap.data();
            data.forEach((key, vv) -> {
                long version = vv.version();
                Long prior = seen.get(key);
                if (prior != null && version < prior) {
                    throw new SimInvariants.SafetyViolation(
                            "edge stale overwrite violated (seed=" + seed + ") at edge "
                                    + edge.edgeId() + " incarnation " + edge.incarnation()
                                    + " key '" + key + "': version went " + prior
                                    + " -> " + version);
                }
                if (prior == null || version > prior) {
                    seen.put(key, version);
                }
            });
        }
    }

    // ---- (d) eventual delivery bound (recorded liveness) -------------------

    private void checkEventualDelivery(List<EdgeActor> edges, long nowMs, IntPredicate connected) {
        // Index edges by id for O(1) lookup (deterministic - read-only).
        Map<Integer, EdgeActor> byId = new HashMap<>();
        for (EdgeActor e : edges) {
            byId.put(e.edgeId(), e);
        }

        var it = outstanding.iterator();
        while (it.hasNext()) {
            Outstanding o = it.next();
            // Satisfy: drop any edge that has now observed this seq (cursor >= seq).
            o.owingEdgeIds.removeIf(edgeId -> {
                EdgeActor e = byId.get(edgeId);
                return e != null && e.alive() && e.cursor() >= o.seq;
            });
            if (o.owingEdgeIds.isEmpty()) {
                it.remove();
                continue;
            }
            // Past the deadline: any still-owing edge that is live + connected +
            // non-lagging is a recorded violation. Disconnected/lagging/crashed
            // edges are excused for this window (you cannot deliver to them).
            if (nowMs - o.publishedAtMs > boundMs) {
                long lateness = nowMs - o.publishedAtMs - boundMs;
                o.owingEdgeIds.removeIf(edgeId -> {
                    EdgeActor e = byId.get(edgeId);
                    boolean eligible = e != null && e.alive() && !e.lagging()
                            && connected.test(edgeId);
                    if (eligible) {
                        activity.recordDeliveryViolation(o.seq, edgeId, o.publishedAtMs, lateness);
                    } else {
                        // An edge ineligible (crashed / lagging / partitioned) exactly at the
                        // deadline tick is excused for this seq. Count it so excused-vs-delivered
                        // is observable - this keeps the liveness checker honest (a high excused
                        // count under a should-deliver schedule flags a possible fan-out bug)
                        // without making it throw.
                        activity.recordExcusedAtDeadline();
                    }
                    // Drop eligible (recorded) AND ineligible (excused) edges so the
                    // entry retires; an edge that becomes eligible again would be
                    // re-obligated by a later publication, keeping the window bounded.
                    return true;
                });
                it.remove();
            }
        }
    }

    // ---- (c) convergence - end-of-run only ---------------------------------

    /**
     * End-of-run convergence (invariant c): after the harness heals all faults and
     * drains, every live edge's store must converge to the authoritative CP leader store.
     * Throws {@link SimInvariants.SafetyViolation} with a precise diff on the first
     * divergence.
     *
     * <h2>Convergence is over effect (value bytes + store version), not per-key version
     * provenance</h2>
     * Convergence is asserted on the <b>effect</b> the system guarantees: the same key
     * set, the same value bytes per key, and the same store version - exactly-once over
     * <em>effect</em>, the edge observes every mutation's effect on the store. It deliberately
     * does NOT require per-key {@link VersionedValue#version()} equality. Reason: a
     * snapshot-recovered edge legitimately carries different per-key version stamps. The
     * catch-up snapshot byte format carries no per-key versions; {@code ConfigStateMachine.restoreSnapshot}
     * (and the edge's {@code EdgeSnapshotCodec}) therefore stamp every restored entry with the
     * snapshot seq, exactly as a Raft {@code InstallSnapshot} does on a follower. So a key the
     * leader last wrote at version 18, delivered to a snapshot-recovered edge inside a snapshot
     * at seq 30, has per-key version 30 on the edge and 18 on the leader - identical value bytes,
     * identical store version, different provenance stamp. That divergence is inherent to
     * snapshot recovery and is NOT a data error; requiring per-key version equality would
     * make the invariant assert something the system (by design) does not
     * guarantee across a snapshot boundary. Per-key version MONOTONICITY (no decrease within
     * an incarnation) is a separate invariant (b) and is unaffected.
     *
     * <p>As a guard against masking a real bug behind this relaxation, the edge's per-key
     * version must still be <= the edge's store version (no impossible future stamp).
     *
     * @param edges            live edge roster
     * @param authoritative    the CP leader's authoritative snapshot (the convergence target)
     */
    void finalCheck(List<EdgeActor> edges, ConfigSnapshot authoritative) {
        Map<String, VersionedValue> leader = sortedView(authoritative);
        for (EdgeActor edge : edges) {
            if (!edge.alive()) {
                continue; // crashed-and-not-restarted edge has no state to converge
            }
            ConfigSnapshot edgeSnap = edge.snapshot();
            Map<String, VersionedValue> edgeView = sortedView(edgeSnap);
            String diff = diff(leader, edgeView, authoritative.version(), edgeSnap.version());
            if (diff != null) {
                throw new SimInvariants.SafetyViolation(
                        "edge convergence violated (seed=" + seed + ") at edge "
                                + edge.edgeId() + ": " + diff
                                + " [leaderVersion=" + authoritative.version()
                                + " edgeVersion=" + edge.currentVersion() + "]");
            }
        }
    }

    /** Deterministic key-sorted view of a snapshot's contents. */
    private static Map<String, VersionedValue> sortedView(ConfigSnapshot snap) {
        Map<String, VersionedValue> out = new TreeMap<>();
        snap.data().forEach(out::put);
        return out;
    }

    /**
     * Returns a precise, deterministic description of the first key where the two views
     * differ in EFFECT (key set, value bytes, or store version), or {@code null} if they
     * converge. Per-key version stamps are NOT required to match (see {@link #finalCheck}'s
     * Javadoc) - only that the edge's stamp is a sane value <= its own store version.
     */
    private static String diff(Map<String, VersionedValue> leader,
                               Map<String, VersionedValue> edge,
                               long leaderVersion, long edgeVersion) {
        // Store version is the convergence anchor (effect): both must agree.
        if (leaderVersion != edgeVersion) {
            return "store version mismatch: leader " + leaderVersion + " vs edge " + edgeVersion;
        }
        // Keys present in the leader but missing/divergent on the edge.
        for (var entry : leader.entrySet()) {
            String key = entry.getKey();
            VersionedValue lv = entry.getValue();
            VersionedValue ev = edge.get(key);
            if (ev == null) {
                return "missing key '" + key + "' (leader version " + lv.version() + ")";
            }
            if (!Arrays.equals(lv.valueUnsafe(), ev.valueUnsafe())) {
                return "key '" + key + "' value bytes mismatch (leader v" + lv.version()
                        + ", edge v" + ev.version() + ")";
            }
            // Sanity: the edge's per-key stamp must not exceed its store version (no
            // impossible future write); a snapshot stamps to the snapshot seq, deltas to
            // their seq - both are <= the store version.
            if (ev.version() > edgeVersion) {
                return "key '" + key + "' has an impossible future version " + ev.version()
                        + " > edge store version " + edgeVersion;
            }
        }
        // Keys present on the edge but absent from the leader (stale leftover).
        for (String key : edge.keySet()) {
            if (!leader.containsKey(key)) {
                return "extra key '" + key + "' on edge (absent from leader)";
            }
        }
        return null;
    }
}
