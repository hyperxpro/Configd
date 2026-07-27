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


@FunctionalInterface
public interface LeaderView {

    
    Snapshot snapshot();

    
    record GroupLeader(int groupId, NodeId leader, long term) {
    }

    
    record Snapshot(NodeId self, Set<NodeId> candidates, List<GroupLeader> groups) {
        public Snapshot {
            Objects.requireNonNull(self, "self");
            candidates = Set.copyOf(candidates);
            groups = List.copyOf(groups);
        }
    }

    
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
