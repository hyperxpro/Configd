package io.configd.server.balance;

import io.configd.common.NodeId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;


final class LeaderBalancePlanner {

    private LeaderBalancePlanner() {
    }

    
    record Move(int groupId, NodeId target) {
    }

    
    record Gate(boolean recentTermChurn, boolean selfInCooldown) {
    }

    
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
