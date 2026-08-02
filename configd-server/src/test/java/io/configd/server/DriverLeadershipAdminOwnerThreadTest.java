package io.configd.server;

import io.configd.api.AdminService;
import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftMessage;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.raft.RaftTransport;
import io.configd.raft.StateMachine;
import io.configd.replication.MultiRaftDriver;
import io.configd.replication.OwnerExecutorPool;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverLeadershipAdminOwnerThreadTest {

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
        InvariantMonitor monitor = new InvariantMonitor(registry, false); // false = production: metric, no throw
        RaftNode.InvariantChecker checker = monitor::check;
        return new RaftNode(config, new RaftLog(), new NoopTransport(), new NoopStateMachine(),
                new java.util.Random(42), Storage.inMemory(), checker);
    }

    private static long violations(MetricsRegistry registry) {
        return registry.counter(VIOLATION_METRIC).get();
    }

    @Test
    @Timeout(30)
    void transferIsPostedToTheOwnerThreadAndADirectCallWouldTrip() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(1);
        try {
            MetricsRegistry registry = new MetricsRegistry();
            RaftNode node = buildNode(registry, RaftConfig.of(NodeId.of(1), Set.of()));
            // Bind the owner + self-elect as the first tasks on the group's owner executor, so the
            // bind/elect path runs on-owner and the guard is active for the assertions below.
            pool.ownerExecutor(GROUP).submit(() -> {
                node.bindOwnerThread();
                for (int i = 0; i < 400; i++) node.tick();
            }).get(5, TimeUnit.SECONDS);
            assertEquals(RaftRole.LEADER, node.role(), "single-node cluster should self-elect to LEADER");

            MultiRaftDriver driver = new MultiRaftDriver(NodeId.of(1), Clock.system());
            driver.setOwnerPool(pool);
            driver.addGroup(GROUP, node);
            DriverLeadershipAdmin admin = new DriverLeadershipAdmin(driver, 5_000L);

            // The transfer runs on the owner thread (posted through driver.ownerExecutor), so the guard
            // stays silent. isLeader() saw LEADER, so the changer ran; the target is not a voter in a
            // single-node cluster, so the mechanism returns false, which maps to Failure.
            AdminService.AdminResult result = admin.transferLeadership(GROUP, NodeId.of(2));
            assertInstanceOf(AdminService.AdminResult.Failure.class, result,
                    "single-node transfer to a non-voter must be a precondition Failure");
            assertEquals(0L, violations(registry),
                    "the posted transfer must run ON the owner thread (no off-owner guard trip)");

            // The same call made directly from the test thread (the missed-hop bug an HTTP-thread call
            // would be) must trip the owner guard - proving the marshalling above is load-bearing.
            long before = violations(registry);
            node.transferLeadership(NodeId.of(2));
            assertTrue(violations(registry) > before,
                    "a direct off-owner transferLeadership call must trip raft_owner_thread - the guard is live "
                            + "and the DriverLeadershipAdmin marshalling is what keeps it silent");
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @Timeout(30)
    void transferOnAFollowerReturnsNotLeaderWithoutTouchingTheOwner() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(1);
        try {
            MetricsRegistry registry = new MetricsRegistry();
            // A 3-voter config with no election: the node stays FOLLOWER (no quorum over the noop transport).
            RaftNode node = buildNode(registry, RaftConfig.of(NodeId.of(1), Set.of(NodeId.of(2), NodeId.of(3))));
            assertEquals(RaftRole.FOLLOWER, node.role(), "a fresh un-elected node is a FOLLOWER");

            MultiRaftDriver driver = new MultiRaftDriver(NodeId.of(1), Clock.system());
            driver.setOwnerPool(pool);
            driver.addGroup(GROUP, node);
            DriverLeadershipAdmin admin = new DriverLeadershipAdmin(driver, 5_000L);

            AdminService.AdminResult result = admin.transferLeadership(GROUP, NodeId.of(2));
            assertInstanceOf(AdminService.AdminResult.NotLeader.class, result,
                    "a transfer requested on a follower must be NotLeader (not attempted)");
            assertEquals(0L, violations(registry),
                    "resolving NotLeader uses only the off-owner-safe volatile reads - no guard trip");

            assertTrue(admin.hasGroup(GROUP), "the registered group is reported present");
            assertTrue(!admin.hasGroup(99), "an unregistered group is reported absent");
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @Timeout(30)
    void transferDuringOwnerShutdownIsARetryableTimeoutNotA500() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(1);
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
        DriverLeadershipAdmin admin = new DriverLeadershipAdmin(driver, 5_000L);

        // The node still reads as LEADER (role() is volatile), so the guard reaches the owner post - but the
        // executor now rejects it. That must convert to the retryable timeout path, not escape as a 500.
        pool.shutdown();
        assertThrows(AdminApiHandler.LeadershipTransferTimeout.class,
                () -> admin.transferLeadership(GROUP, NodeId.of(2)),
                "a transfer rejected by a shutting-down owner executor must surface the retryable 503 path");
    }
}
