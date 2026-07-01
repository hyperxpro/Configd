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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Backs the ADMIN-gated leadership-transfer endpoint with the {@link MultiRaftDriver}: resolves a group's
 * {@link RaftNode} and drives {@link RaftNode#transferLeadership} through the built {@link AdminService}
 * guard, returning its {@link AdminService.AdminResult}. One {@code AdminService} is constructed per call
 * over tiny per-group adapters - the built guard (is-leader check, NotLeader result, Success/Failure
 * shaping) stays the single source of truth, and there is no shared mutable state to reason about.
 *
 * <p><b>Owner-thread confinement is load-bearing.</b> {@link RaftNode#transferLeadership} mutates consensus
 * state and asserts it runs on the group's single owner thread, so it is posted to
 * {@link MultiRaftDriver#ownerExecutor(int)} and awaited under a bounded deadline - never called from the
 * HTTP thread. The leader/role reads used by the guard are the volatile, off-owner-safe accessors
 * ({@link RaftNode#leaderId()} / {@link RaftNode#role()}). If the owner does not confirm within the bound
 * (a wedged or overloaded owner), a {@link AdminApiHandler.LeadershipTransferTimeout} is raised (mapped to
 * 503) rather than blocking the HTTP thread indefinitely or reporting a false negative.
 */
final class DriverLeadershipAdmin implements AdminApiHandler.LeadershipAdmin {

    /** Default bounded wait for the owner thread to run the transfer; expiry -> 503 (unknown, retryable). */
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

    /**
     * Reads a group's leader/role for the transfer guard using ONLY the volatile, off-owner-safe
     * {@link RaftNode} accessors. The cluster-status methods are deliberately unimplemented: this adapter
     * backs the leadership-transfer surface, which never calls {@link AdminService#clusterStatus()}. A
     * future status endpoint must resolve term/commit/nodes via owner-confined reads, not off-owner here -
     * throwing keeps that requirement loud rather than silently returning a possibly-torn value.
     */
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

    /**
     * Marshals {@link RaftNode#transferLeadership} onto the group's owner thread and awaits the boolean
     * under a bounded deadline. {@code addNode}/{@code removeNode} are intentionally unexposed (this gate
     * exposes leadership transfer only); invoking them is a wiring error.
     */
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
            Future<Boolean> f = owner.submit(() -> node.transferLeadership(target));
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
