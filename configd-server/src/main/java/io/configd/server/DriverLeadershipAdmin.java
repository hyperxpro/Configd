package io.configd.server;

import io.configd.api.AdminService;
import io.configd.common.NodeId;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.replication.MultiRaftDriver;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


final class DriverLeadershipAdmin implements AdminApiHandler.LeadershipAdmin {

    
    static final long DEFAULT_AWAIT_MILLIS = 5_000L;

    private final MultiRaftDriver driver;
    private final long awaitMillis;

    DriverLeadershipAdmin(MultiRaftDriver driver) {
        this(driver, Long.getLong("configd.admin.transferAwaitMillis", DEFAULT_AWAIT_MILLIS));
    }

    DriverLeadershipAdmin(MultiRaftDriver driver, long awaitMillis) {
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
    public AdminService.AdminResult transferLeadership(int groupId, NodeId target) {
        RaftNode node = driver.getGroup(groupId);
        if (node == null) {
            // The handler checks hasGroup() before calling; this is defensive only (a group removed in the
            // window between the check and here). Report a failed precondition rather than throwing.
            return new AdminService.AdminResult.Failure("Group " + groupId + " is not registered");
        }
        ScheduledExecutorService owner = driver.ownerExecutor(groupId);
        AdminService adminService = new AdminService(
                new GroupStateProvider(node),
                new OwnerThreadTransferChanger(groupId, node, owner, awaitMillis));
        return adminService.transferLeadership(target);
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
                    "cluster status is not exposed by the leadership-transfer admin surface");
        }
    }

    
    private static final class OwnerThreadTransferChanger implements AdminService.MembershipChanger {
        private final int groupId;
        private final RaftNode node;
        private final ScheduledExecutorService owner;
        private final long awaitMillis;

        OwnerThreadTransferChanger(int groupId, RaftNode node, ScheduledExecutorService owner, long awaitMillis) {
            this.groupId = groupId;
            this.node = node;
            this.owner = owner;
            this.awaitMillis = awaitMillis;
        }

        @Override
        public boolean addNode(NodeId n) {
            throw unexposed("addNode");
        }

        @Override
        public boolean removeNode(NodeId n) {
            throw unexposed("removeNode");
        }

        @Override
        public boolean transferLeadership(NodeId target) {
            // transferLeadership asserts the owner thread, so it MUST run there, never on the HTTP thread.
            // The bounded await keeps a wedged owner from blocking the HTTP thread; on expiry we raise a
            // timeout (503) rather than reporting a false negative.
            final Future<Boolean> f;
            try {
                f = owner.submit(() -> node.transferLeadership(target));
            } catch (RejectedExecutionException e) {
                // The owner executor is shutting down or draining: the node is unavailable, and the
                // request is safe to retry elsewhere - surface the SAME retryable path as a timeout (503),
                // never let it propagate to a 500.
                throw new AdminApiHandler.LeadershipTransferTimeout(groupId, awaitMillis);
            }
            try {
                return f.get(awaitMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // Drop the task if it is still queued; never interrupt the owner mid-consensus (a true
                // cancel could tear an in-flight tick / replication step).
                f.cancel(false);
                throw new AdminApiHandler.LeadershipTransferTimeout(groupId, awaitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AdminApiHandler.LeadershipTransferTimeout(groupId, awaitMillis);
            } catch (ExecutionException e) {
                // transferLeadership returns booleans for validated inputs, so an execution failure is a
                // genuine defect - surface it rather than silently reporting a false.
                throw new IllegalStateException(
                        "leadership transfer failed on the owner thread for group " + groupId, e.getCause());
            }
        }

        private static UnsupportedOperationException unexposed(String op) {
            return new UnsupportedOperationException(
                    op + " is not exposed; this admin surface exposes leadership transfer only");
        }
    }
}
