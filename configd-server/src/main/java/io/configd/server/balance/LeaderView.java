package io.configd.server.balance;

import io.configd.common.NodeId;
import io.configd.raft.RaftMetrics;
import io.configd.raft.RaftNode;
import io.configd.replication.MultiRaftDriver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Supplies the cluster-wide leader distribution as this node currently observes it. No gossip and no new
 * RPC are needed: every node hosts a replica of every Raft group, so each per-group {@link RaftNode}
 * already tracks that group's current leader, and a node derives the whole distribution from its own
 * local reads.
 *
 * <p>The read is off-owner-safe: it goes through {@link RaftNode#monitorView()}, an immutable,
 * never-torn, at-most-one-tick-stale snapshot the balance loop reads without touching the 10ms consensus
 * tick. A follower's {@code leaderId} is only as fresh as its last AppendEntries (null during an
 * election); the balance loop's instability gate backs off on any null leader, so staleness only ever
 * delays a correction by one cadence, never causes a wrong move.
 */
@FunctionalInterface
public interface LeaderView {

    /** A point-in-time observation of who leads each group, plus the fixed candidate node set. */
    Snapshot snapshot();

    /**
     * One group's leader as this node currently sees it.
     *
     * @param groupId the Raft group id
     * @param leader  the node this replica believes leads the group, or {@code null} if unknown
     *                (mid-election / no AppendEntries seen yet)
     * @param term    the group's current term, used to detect election churn across cadences
     */
    record GroupLeader(int groupId, NodeId leader, long term) {
    }

    /**
     * A whole-cluster leader observation.
     *
     * @param self       this node's id
     * @param candidates every node that can hold leadership (peers plus self) - the domain over which the
     *                   leader-count distribution is tallied, so a node currently leading zero groups
     *                   still counts as a valid under-loaded target
     * @param groups     the per-group leader observations
     */
    record Snapshot(NodeId self, Set<NodeId> candidates, List<GroupLeader> groups) {
        public Snapshot {
            Objects.requireNonNull(self, "self");
            candidates = Set.copyOf(candidates);
            groups = List.copyOf(groups);
        }
    }

    /**
     * A view that derives the distribution from a live {@link MultiRaftDriver}: for every registered
     * group it reads {@code getGroup(g).monitorView()} to learn that group's leader and term. The
     * candidate set is {@code peers} plus this node, captured once (uniform static membership - every
     * node is a voter in every group, so any candidate is a legal transfer target).
     */
    static LeaderView overDriver(MultiRaftDriver driver, Set<NodeId> peers) {
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(peers, "peers");
        NodeId self = driver.localNode();
        Set<NodeId> candidates = new LinkedHashSet<>();
        candidates.add(self);
        candidates.addAll(peers);
        Set<NodeId> frozenCandidates = Collections.unmodifiableSet(candidates);
        return () -> {
            List<GroupLeader> observed = new ArrayList<>();
            for (int gid : driver.groupIds()) {
                RaftNode node = driver.getGroup(gid);
                if (node == null) {
                    continue; // group removed between groupIds() and getGroup() - skip, re-observed next cadence
                }
                RaftMetrics view = node.monitorView();
                observed.add(new GroupLeader(gid, view.leaderId(), view.currentTerm()));
            }
            return new Snapshot(self, frozenCandidates, observed);
        };
    }
}
