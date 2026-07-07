package io.configd.server.balance;

import io.configd.common.NodeId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The pure decision core of the leadership balancer: given a {@link LeaderView.Snapshot}, the runtime
 * instability gate, and the imbalance threshold, it decides whether to shed one group's leadership and to
 * where. No threads, no I/O, no time - all timekeeping-derived state is passed in via {@link Gate} - so
 * the whole decision matrix (convergence, no-thrash, back-off, target selection) is deterministically
 * unit-testable.
 *
 * <p>Design (decentralized, per-leader shedding - the CockroachDB model):
 * <ol>
 *   <li>Tally the leader count per candidate node from the snapshot. A candidate leading zero groups
 *       counts as zero, so it is eligible as an under-loaded target.</li>
 *   <li>Back off (do nothing this cycle) if any group's leader is unknown (mid-election), or the runtime
 *       gate reports recent term churn or an active post-transfer cooldown. When in doubt, do nothing - a
 *       rebalance is never urgent.</li>
 *   <li>Act only if the spread {@code max-min} is at least the threshold AND this node is a max-holder.
 *       Only the current leader of a group can transfer it, so authority is already sharded by ownership
 *       and two nodes can never contend to move the same group; each node only ever sheds its own.</li>
 *   <li>Shed exactly one led group to one strictly-under-loaded (minimum-count) candidate, chosen with
 *       per-round jitter so two simultaneous shedders do not herd onto the same victim.</li>
 * </ol>
 *
 * <p><b>Safety:</b> a max-holder shedding to a min-holder can never worsen the imbalance - because the
 * loop only acts when {@code max-min >= 2}, moving one leader makes the source {@code max-1} and the
 * target {@code min+1}, and {@code max-1 >= min+1}, so no new inversion is created. This is what makes
 * decentralized, one-at-a-time shedding provably convergent without a coordinator.
 */
final class LeaderBalancePlanner {

    private LeaderBalancePlanner() {
    }

    /** The single leadership move to initiate this cadence. */
    record Move(int groupId, NodeId target) {
    }

    /**
     * Runtime-derived instability inputs the planner cannot compute from a single snapshot (they need
     * cross-cadence timekeeping the loop owns).
     *
     * @param recentTermChurn a group's term bumped within the instability window (election storm)
     * @param selfInCooldown  this node is inside its post-transfer cooldown
     */
    record Gate(boolean recentTermChurn, boolean selfInCooldown) {
    }

    /**
     * The planner's decision. Exactly one of {@code move}/{@code backoffReason} is non-null, or both are
     * null for a no-op on a balanced (or below-threshold) cluster. {@code leaderSpread} is always the
     * observed {@code max-min} for the gauge, computed even when backing off.
     */
    record Plan(int leaderSpread, String backoffReason, Move move) {
        boolean actionable() {
            return move != null;
        }

        boolean backedOff() {
            return backoffReason != null;
        }

        static Plan backoff(int spread, String reason) {
            return new Plan(spread, reason, null);
        }

        static Plan noAction(int spread) {
            return new Plan(spread, null, null);
        }

        static Plan transfer(int spread, Move move) {
            return new Plan(spread, null, move);
        }
    }

    // Back-off reasons (stable, low-cardinality - they name the skipped_unstable metric series).
    static final String REASON_UNKNOWN_LEADER = "unknown_leader";
    static final String REASON_TERM_CHURN = "term_churn";
    static final String REASON_COOLDOWN = "cooldown";

    /**
     * Computes the balance decision. {@code jitter} is consumed only to break ties among equally
     * under-loaded targets; a fixed seed makes the choice reproducible in tests.
     */
    static Plan plan(LeaderView.Snapshot snapshot, Gate gate, int imbalanceThreshold, Random jitter) {
        // Tally leaders over the fixed candidate domain (each candidate seeded at 0 so a node leading
        // nothing is still counted as a minimum-load target).
        Map<NodeId, Integer> leaders = new HashMap<>();
        for (NodeId candidate : snapshot.candidates()) {
            leaders.put(candidate, 0);
        }
        boolean unknownLeader = false;
        List<Integer> selfLed = new ArrayList<>();
        for (LeaderView.GroupLeader group : snapshot.groups()) {
            NodeId leader = group.leader();
            if (leader == null) {
                unknownLeader = true; // mid-election / incomplete view
                continue;
            }
            leaders.merge(leader, 1, Integer::sum);
            if (leader.equals(snapshot.self())) {
                selfLed.add(group.groupId());
            }
        }

        int max = 0;
        int min = Integer.MAX_VALUE;
        for (int count : leaders.values()) {
            max = Math.max(max, count);
            min = Math.min(min, count);
        }
        int spread = max - min;

        // Instability back-off, in priority order. leaderId==null is derived here from the snapshot; the
        // other signals need the loop's cross-cadence timekeeping and arrive via the gate.
        if (unknownLeader) {
            return Plan.backoff(spread, REASON_UNKNOWN_LEADER);
        }
        if (gate.recentTermChurn()) {
            return Plan.backoff(spread, REASON_TERM_CHURN);
        }
        if (gate.selfInCooldown()) {
            return Plan.backoff(spread, REASON_COOLDOWN);
        }

        if (spread < imbalanceThreshold) {
            return Plan.noAction(spread); // balanced, or the only imbalance is the unavoidable spread-of-1
        }

        // Only the most-loaded nodes shed, one group each. A node below the max leaves the correction to
        // the max-holder(s) this cadence, which keeps the loop conservative and still converges.
        int selfCount = leaders.getOrDefault(snapshot.self(), 0);
        if (selfCount != max || selfLed.isEmpty()) {
            return Plan.noAction(spread);
        }

        // Target = a strictly-under-loaded (minimum) candidate. Since spread >= 2, min < max == selfCount,
        // so such a target always exists and is never self. Jitter among equal minima to avoid herding.
        List<NodeId> minima = new ArrayList<>();
        for (Map.Entry<NodeId, Integer> entry : leaders.entrySet()) {
            if (entry.getValue() == min) {
                minima.add(entry.getKey());
            }
        }
        minima.sort(NodeId::compareTo); // deterministic order before the jittered pick
        NodeId target = minima.get(jitter.nextInt(minima.size()));

        // Shed the lowest-gid group this node leads - deterministic, and since the group leaves this
        // node's led set once moved, the choice never ping-pongs a single group between two nodes.
        int group = selfLed.stream().mapToInt(Integer::intValue).min().orElseThrow();
        return Plan.transfer(spread, new Move(group, target));
    }
}
