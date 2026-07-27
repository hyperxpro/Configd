package io.configd.server.balance;

import io.configd.common.Clock;
import io.configd.common.NodeId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, network-free fixtures for the leadership-balance tests: an in-memory model of "who leads
 * each group" that the balance loop drives through its own seams, a settable clock, and a recording
 * metrics sink. No Raft, no threads, no I/O - the loop's control decisions are exercised in isolation, at
 * exactly the altitude they own (the real transfer primitive is already covered by
 * {@code LeadershipTransferAdminTest} and the consensus tests).
 */
final class BalanceTestSupport {

    private BalanceTestSupport() {
    }

    /** A clock the test moves by hand, so cooldown/instability timing is fully deterministic. */
    static final class MutableClock implements Clock {
        private long millis;

        void set(long m) {
            this.millis = m;
        }

        void advance(long delta) {
            this.millis += delta;
        }

        @Override
        public long currentTimeMillis() {
            return millis;
        }

        @Override
        public long nanoTime() {
            return millis * 1_000_000L;
        }
    }

    /**
     * A shared, globally-consistent model of leadership across a fixed candidate set. A transfer only
     * succeeds for a group's current leader (mirroring the primitive, which returns false off-leader), and
     * a successful transfer bumps the group's term (mirroring a real leadership change). {@code refuse}
     * models the primitive declining every transfer (e.g. a config change pending) so the loop's fold-into-
     * cooldown behavior can be asserted.
     */
    static final class FakeCluster {
        final Map<Integer, NodeId> leaderOf = new HashMap<>();
        final Map<Integer, Long> termOf = new HashMap<>();
        final Set<NodeId> candidates = new LinkedHashSet<>();
        boolean refuse;
        int transferAttempts;
        int transfersApplied;

        FakeCluster(int nodeCount) {
            for (int i = 0; i < nodeCount; i++) {
                candidates.add(NodeId.of(i));
            }
        }

        void placeAllOn(int groupCount, NodeId leader) {
            for (int g = 0; g < groupCount; g++) {
                leaderOf.put(g, leader);
                termOf.put(g, 1L);
            }
        }

        void place(int group, NodeId leader) {
            leaderOf.put(group, leader);
            termOf.putIfAbsent(group, 1L);
        }

        void bumpTerm(int group) {
            termOf.merge(group, 1L, Long::sum);
        }

        LeaderView viewFor(NodeId self) {
            return () -> {
                List<LeaderView.GroupLeader> groups = new ArrayList<>();
                for (Map.Entry<Integer, NodeId> e : leaderOf.entrySet()) {
                    groups.add(new LeaderView.GroupLeader(
                            e.getKey(), e.getValue(), termOf.getOrDefault(e.getKey(), 1L)));
                }
                groups.sort(Comparator.comparingInt(LeaderView.GroupLeader::groupId));
                return new LeaderView.Snapshot(self, candidates, groups);
            };
        }

        LeaderBalanceLoop.LeadershipTransfer transferFor(NodeId self) {
            return (groupId, target) -> {
                transferAttempts++;
                if (refuse) {
                    return false;
                }
                NodeId current = leaderOf.get(groupId);
                if (current == null || !current.equals(self)) {
                    return false; // only a group's current leader can move it
                }
                leaderOf.put(groupId, target);
                bumpTerm(groupId);
                transfersApplied++;
                return true;
            };
        }

        Map<NodeId, Integer> distribution() {
            Map<NodeId, Integer> counts = new HashMap<>();
            for (NodeId c : candidates) {
                counts.put(c, 0);
            }
            for (NodeId leader : leaderOf.values()) {
                counts.merge(leader, 1, Integer::sum);
            }
            return counts;
        }

        int spread() {
            var counts = distribution().values();
            return Collections.max(counts) - Collections.min(counts);
        }
    }

    /** A metrics sink that records call counts for assertions. */
    static final class RecordingMetrics implements LeaderBalanceMetrics {
        int transfersInitiated;
        int transfersRefused;
        int wouldTransfers;
        int cycleErrors;
        int lastSpread = -1;
        boolean lastCooldownActive;
        final Map<String, Integer> skipped = new HashMap<>();

        @Override
        public void leaderSpread(int spread) {
            lastSpread = spread;
        }

        @Override
        public void cooldownActive(boolean active) {
            lastCooldownActive = active;
        }

        @Override
        public void transferInitiated() {
            transfersInitiated++;
        }

        @Override
        public void transferRefused() {
            transfersRefused++;
        }

        @Override
        public void wouldTransfer() {
            wouldTransfers++;
        }

        @Override
        public void skippedUnstable(String reason) {
            skipped.merge(reason, 1, Integer::sum);
        }

        @Override
        public void cycleError() {
            cycleErrors++;
        }

        int skipped(String reason) {
            return skipped.getOrDefault(reason, 0);
        }
    }

    /** A config with the given knobs; unspecified dampening off by default for isolated assertions. */
    static LeaderBalanceConfig config(int threshold, long cooldownMs, long instabilityWindowMs, boolean dryRun) {
        return new LeaderBalanceConfig(
                true, dryRun, 30_000L, 25, threshold, cooldownMs, 1, instabilityWindowMs);
    }
}
