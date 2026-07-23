package io.configd.server;

import io.configd.api.AdminService;
import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;
import io.configd.raft.ClusterConfig;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftMessage;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.raft.RaftTransport;
import io.configd.raft.StateMachine;
import io.configd.replication.MultiRaftDriver;
import io.configd.replication.OwnerExecutorPool;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the add-server mechanism against the REAL {@link MultiRaftDriver}: the joint-consensus membership
 * proposal is marshalled onto the group's owner thread (never the calling thread), a real leader actually
 * moves node 2 into its new voter set, an already-a-voter add is a clear precondition failure, and a
 * follower yields NotLeader without touching the owner. Wiring mirrors {@link DriverLeadershipAdminOwnerThreadTest}:
 * a production-mode {@link InvariantMonitor} makes an off-owner {@code RaftNode} touch increment the
 * {@code invariant.violation.raft_owner_thread} counter, so we can assert the proposal ran on-owner.
 */
class DriverRaftClusterAdminAddServerTest {

    private static final int GROUP = 0;
    private static final String VIOLATION_METRIC = "invariant.violation.raft_owner_thread";

    private static final class NoopTransport implements RaftTransport {
        @Override public void send(NodeId target, RaftMessage message) { }
    }

    private static final class NoopStateMachine implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    private static RaftNode buildNode(MetricsRegistry registry, RaftConfig config) {
        InvariantMonitor monitor = new InvariantMonitor(registry, false); // production: metric, no throw
        RaftNode.InvariantChecker checker = monitor::check;
        return new RaftNode(config, new RaftLog(), new NoopTransport(), new NoopStateMachine(),
                new java.util.Random(42), Storage.inMemory(), checker);
    }

    private static long violations(MetricsRegistry registry) {
        return registry.counter(VIOLATION_METRIC).get();
    }

    /**
     * A real single-node leader: add-server for node 2 is proposed on the owner thread (the guard stays
     * silent), returns Success, and an owner-confined read shows the joint config now carries node 2 in its
     * new voter set - the mechanism genuinely added the voter, not just returned a status.
     */
    @Test
    @Timeout(30)
    void addServerProposesOnOwnerThreadAndActuallyAddsTheVoter() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(1);
        try {
            MetricsRegistry registry = new MetricsRegistry();
            RaftNode node = buildNode(registry, RaftConfig.of(NodeId.of(1), Set.of())); // self-elects
            pool.ownerExecutor(GROUP).submit(() -> {
                node.bindOwnerThread();
                for (int i = 0; i < 400; i++) node.tick(); // elect + commit the leader no-op
            }).get(5, TimeUnit.SECONDS);
            assertEquals(RaftRole.LEADER, node.role(), "single-node cluster should self-elect to LEADER");

            MultiRaftDriver driver = new MultiRaftDriver(NodeId.of(1), Clock.system());
            driver.setOwnerPool(pool);
            driver.addGroup(GROUP, node);
            DriverRaftClusterAdmin admin = new DriverRaftClusterAdmin(driver, 5_000L);

            AdminService.AdminResult result = admin.addServer(GROUP, NodeId.of(2));
            assertInstanceOf(AdminService.AdminResult.Success.class, result,
                    "adding a new voter to a real leader with a committed no-op must be accepted");
            assertEquals(0L, violations(registry),
                    "the joint-consensus proposal must run ON the owner thread (no off-owner guard trip)");

            // Owner-confined read proves the voter was actually moved into the new config (joint consensus).
            ClusterConfig cfg = pool.ownerExecutor(GROUP).submit(node::clusterConfig).get(5, TimeUnit.SECONDS);
            assertTrue(cfg.isJoint(), "after add-server the group must be in a joint configuration");
            assertTrue(cfg.newVoters().contains(NodeId.of(2)),
                    "the add-server proposal must place node 2 in the new voter set");
        } finally {
            pool.shutdown();
        }
    }

    /** Adding a node that is already the sole voter is a clear precondition Failure (mapped to 409). */
    @Test
    @Timeout(30)
    void addServerForAnExistingVoterIsAClearFailure() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(1);
        try {
            MetricsRegistry registry = new MetricsRegistry();
            RaftNode node = buildNode(registry, RaftConfig.of(NodeId.of(1), Set.of()));
            pool.ownerExecutor(GROUP).submit(() -> {
                node.bindOwnerThread();
                for (int i = 0; i < 400; i++) node.tick();
            }).get(5, TimeUnit.SECONDS);
            assertEquals(RaftRole.LEADER, node.role());

            MultiRaftDriver driver = new MultiRaftDriver(NodeId.of(1), Clock.system());
            driver.setOwnerPool(pool);
            driver.addGroup(GROUP, node);
            DriverRaftClusterAdmin admin = new DriverRaftClusterAdmin(driver, 5_000L);

            AdminService.AdminResult result = admin.addServer(GROUP, NodeId.of(1)); // node 1 is already a voter
            AdminService.AdminResult.Failure failure = assertInstanceOf(AdminService.AdminResult.Failure.class,
                    result, "adding an existing voter must be a precondition Failure");
            assertTrue(failure.reason().contains("already a voter"),
                    "the failure must name the already-a-voter precondition: " + failure.reason());
            assertEquals(0L, violations(registry), "the owner-confined voter read must not trip the guard");
        } finally {
            pool.shutdown();
        }
    }

    /** A follower yields NotLeader from the built AdminService guard, without ever posting to the owner. */
    @Test
    @Timeout(30)
    void addServerOnAFollowerIsNotLeader() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(1);
        try {
            MetricsRegistry registry = new MetricsRegistry();
            RaftNode node = buildNode(registry, RaftConfig.of(NodeId.of(1), Set.of(NodeId.of(2), NodeId.of(3))));
            assertEquals(RaftRole.FOLLOWER, node.role(), "a fresh un-elected node is a FOLLOWER");

            MultiRaftDriver driver = new MultiRaftDriver(NodeId.of(1), Clock.system());
            driver.setOwnerPool(pool);
            driver.addGroup(GROUP, node);
            DriverRaftClusterAdmin admin = new DriverRaftClusterAdmin(driver, 5_000L);

            AdminService.AdminResult result = admin.addServer(GROUP, NodeId.of(4));
            assertInstanceOf(AdminService.AdminResult.NotLeader.class, result,
                    "add-server on a follower must be NotLeader (the proposal is not attempted)");
        } finally {
            pool.shutdown();
        }
    }

    /** The status read enumerates the hosted group with its off-owner fields and owner-confined voter set. */
    @Test
    @Timeout(30)
    void statusEnumeratesHostedGroups() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(1);
        try {
            MetricsRegistry registry = new MetricsRegistry();
            RaftNode node = buildNode(registry, RaftConfig.of(NodeId.of(1), Set.of()));
            pool.ownerExecutor(GROUP).submit(() -> {
                node.bindOwnerThread();
                for (int i = 0; i < 400; i++) node.tick();
            }).get(5, TimeUnit.SECONDS);
            assertEquals(RaftRole.LEADER, node.role());

            MultiRaftDriver driver = new MultiRaftDriver(NodeId.of(1), Clock.system());
            driver.setOwnerPool(pool);
            driver.addGroup(GROUP, node);
            DriverRaftClusterAdmin admin = new DriverRaftClusterAdmin(driver, 5_000L);

            List<AdminApiHandler.GroupStatus> status = admin.status();
            assertEquals(1, status.size(), "one hosted group must be reported");
            AdminApiHandler.GroupStatus g = status.get(0);
            assertEquals(GROUP, g.groupId());
            assertEquals("LEADER", g.role(), "a self-elected single node reports LEADER");
            assertEquals(NodeId.of(1), g.leaderId());
            assertTrue(g.voters().contains(NodeId.of(1)), "the sole voter must be reported");
            assertEquals(0L, violations(registry), "the owner-confined voter read must not trip the guard");
        } finally {
            pool.shutdown();
        }
    }
}
