package io.configd.server;

import io.configd.api.AdminService;
import io.configd.common.NodeId;
import io.configd.raft.RaftMetrics;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.replication.MultiRaftDriver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


final class DriverRaftClusterAdmin implements AdminApiHandler.RaftClusterAdmin {

    
    static final long DEFAULT_AWAIT_MILLIS = 5_000L;

    private final MultiRaftDriver driver;
    private final long awaitMillis;

    DriverRaftClusterAdmin(MultiRaftDriver driver) {
        this(driver, Long.getLong("configd.admin.raftAwaitMillis", DEFAULT_AWAIT_MILLIS));
    }

    DriverRaftClusterAdmin(MultiRaftDriver driver, long awaitMillis) {
        this.driver = Objects.requireNonNull(driver, "driver");
        if (awaitMillis <= 0) {
            throw new IllegalArgumentException("awaitMillis must be > 0, was " + awaitMillis);
        }
        this.awaitMillis = awaitMillis;
    }

    @Override
    public boolean hasGroup(int groupId) {
        return driver.getGroup(groupId) != null;
    }

    @Override
    public List<AdminApiHandler.GroupStatus> status() {
        List<AdminApiHandler.GroupStatus> out = new ArrayList<>();
        // Ascending group order so the status payload is stable across scrapes.
        for (int groupId : new TreeSet<>(driver.groupIds())) {
            RaftNode node = driver.getGroup(groupId);
            if (node == null) {
                continue; // removed in the window between groupIds() and here; skip it
            }
            RaftMetrics view = node.monitorView(); // off-owner safe published snapshot
            Set<NodeId> voters = votersOwnerConfined(groupId, node);
            out.add(new AdminApiHandler.GroupStatus(
                    groupId,
                    node.role().name(),   // off-owner safe volatile
                    node.leaderId(),      // off-owner safe volatile
                    view.currentTerm(),
                    view.commitIndex(),
                    view.lastApplied(),
                    voters));
        }
        return out;
    }

    @Override
    public AdminService.AdminResult addServer(int groupId, NodeId target) {
        RaftNode node = driver.getGroup(groupId);
        if (node == null) {
            // The handler checks hasGroup() first; defensive only (a group removed in the window).
            return new AdminService.AdminResult.Failure("Group " + groupId + " is not registered");
        }
        // Resolve the current voters owner-confined so an already-a-voter add yields a CLEAR 409 reason,
        // not the generic "no change needed" false from proposeConfigChange.
        Set<NodeId> voters = votersOwnerConfined(groupId, node);
        if (voters.contains(target)) {
            return new AdminService.AdminResult.Failure(
                    "node " + target + " is already a voter of group " + groupId);
        }
        AdminService adminService = new AdminService(
                new GroupStateProvider(node),
                new OwnerThreadAddChanger(groupId, node));
        return adminService.addNode(target);
    }

    
    private Set<NodeId> votersOwnerConfined(int groupId, RaftNode node) {
        return awaitOnOwner(groupId, "raft-status voter read", node,
                () -> Set.copyOf(node.clusterConfig().voters()));
    }

    
    private <T> T awaitOnOwner(int groupId, String operation, RaftNode node, Callable<T> task) {
        ScheduledExecutorService owner = driver.ownerExecutor(groupId);
        final Future<T> f;
        try {
            f = owner.submit(task);
        } catch (RejectedExecutionException e) {
            throw new AdminApiHandler.RaftAdminTimeout(operation + " for group " + groupId, awaitMillis);
        }
        try {
            return f.get(awaitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(false); // drop if still queued; never interrupt the owner mid-consensus
            throw new AdminApiHandler.RaftAdminTimeout(operation + " for group " + groupId, awaitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdminApiHandler.RaftAdminTimeout(operation + " for group " + groupId, awaitMillis);
        } catch (ExecutionException e) {
            throw new IllegalStateException(
                    operation + " failed on the owner thread for group " + groupId, e.getCause());
        }
    }

    
    private static final class GroupStateProvider implements AdminService.ClusterStateProvider {
        private final RaftNode node;

        GroupStateProvider(RaftNode node) {
            this.node = node;
        }

        @Override
        public NodeId currentLeader() {
            return node.leaderId(); // volatile, off-owner safe
        }

        @Override
        public boolean isLeader() {
            return node.role() == RaftRole.LEADER; // volatile, off-owner safe
        }

        @Override
        public Set<NodeId> clusterNodes() {
            throw notExposed();
        }

        @Override
        public long currentTerm() {
            throw notExposed();
        }

        @Override
        public long commitIndex() {
            throw notExposed();
        }

        private static UnsupportedOperationException notExposed() {
            return new UnsupportedOperationException(
                    "cluster status is not exposed by the add-server admin surface");
        }
    }

    
    private final class OwnerThreadAddChanger implements AdminService.MembershipChanger {
        private final int groupId;
        private final RaftNode node;

        OwnerThreadAddChanger(int groupId, RaftNode node) {
            this.groupId = groupId;
            this.node = node;
        }

        @Override
        public boolean addNode(NodeId target) {
            return awaitOnOwner(groupId, "add-server propose", node, () -> {
                Set<NodeId> newVoters = new HashSet<>(node.clusterConfig().voters());
                newVoters.add(target);
                return node.proposeConfigChange(newVoters);
            });
        }

        @Override
        public boolean removeNode(NodeId n) {
            throw unexposed("removeNode");
        }

        @Override
        public boolean transferLeadership(NodeId target) {
            throw unexposed("transferLeadership");
        }

        private static UnsupportedOperationException unexposed(String op) {
            return new UnsupportedOperationException(
                    op + " is not exposed; this admin surface exposes add-server only");
        }
    }
}
