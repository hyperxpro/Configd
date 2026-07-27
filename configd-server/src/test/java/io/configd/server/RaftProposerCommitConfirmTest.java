package io.configd.server;

import io.configd.api.ConfigWriteService;
import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.raft.RaftTransport;
import io.configd.raft.StateMachine;
import io.configd.replication.MultiRaftDriver;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Proves {@code ConfigdServer.raftProposer} is
 * <b>commit-confirmed</b> and produces the distinguishable failure taxonomy a
 * client needs - an uncommitted write is reported as something OTHER than success
 * and OTHER than a permanent failure ("the error a client receives
 * for an uncommitted write is distinguishable from success and from permanent
 * failure"). Complements the deterministic-simulator discriminator
 * {@code AckEqualsCommitTest} with a direct test of the production seam that
 * marshals propose + commit-outcome registration on the tick executor.
 */
class RaftProposerCommitConfirmTest {

    private static final int GROUP = 0;

    private static ScheduledExecutorService raftExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "raft-commit-confirm-exec");
            t.setDaemon(true);
            return t;
        });
    }

    private static final class SeqStateMachine implements StateMachine {
        private long seq;
        @Override public long apply(long index, long term, byte[] command) {
            if (command == null || command.length == 0) {
                return StateMachine.NON_MUTATING;
            }
            return ++seq;
        }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    private static RaftNode singleNodeLeader(ScheduledExecutorService exec) throws Exception {
        RaftConfig config = RaftConfig.of(NodeId.of(1), Set.of());
        RaftNode node = new RaftNode(config, new RaftLog(),
                (target, message) -> { }, new SeqStateMachine(), new java.util.Random(7));
        exec.submit(() -> { for (int i = 0; i < 400; i++) node.tick(); }).get(5, TimeUnit.SECONDS);
        return node;
    }

    private static MultiRaftDriver driverFor(RaftNode node) {
        MultiRaftDriver driver = new MultiRaftDriver(NodeId.of(1), Clock.system());
        driver.addGroup(GROUP, node);
        return driver;
    }

    private static byte[] put(String key, String value) {
        return io.configd.store.CommandCodec.encodePut(key, value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void singleNodeLeaderReturnsCommittedWithSeq() throws Exception {
        ScheduledExecutorService exec = raftExecutor();
        try {
            RaftNode node = singleNodeLeader(exec);
            ConfigWriteService.RaftProposer proposer =
                    ConfigdServer.raftProposer(driverFor(node), GROUP, exec, 5000);

            var result = proposer.propose(null, java.util.List.of("k"), put("k", "v"));
            // A single-node leader commits + applies inline, so the proposer
            // returns Committed (NOT a bare local-append accept) carrying the
            // applied-mutation seq.
            var committed = assertInstanceOf(
                    ConfigWriteService.ProposeCommitResult.Committed.class, result,
                    "single-node leader must commit-confirm the write");
            org.junit.jupiter.api.Assertions.assertTrue(committed.seq() >= 1,
                    "Committed must carry the applied-mutation seq; got " + committed.seq());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void followerReturnsNotLeaderPreAppend() throws Exception {
        ScheduledExecutorService exec = raftExecutor();
        try {
            // Multi-node config, no election driven -> node stays FOLLOWER.
            RaftConfig config = RaftConfig.of(NodeId.of(1), Set.of(NodeId.of(2), NodeId.of(3)));
            RaftNode follower = new RaftNode(config, new RaftLog(),
                    (target, message) -> { }, new SeqStateMachine(), new java.util.Random(7));
            ConfigWriteService.RaftProposer proposer =
                    ConfigdServer.raftProposer(driverFor(follower), GROUP, exec, 5000);

            var result = proposer.propose(null, java.util.List.of("k"), put("k", "v"));
            assertInstanceOf(ConfigWriteService.ProposeCommitResult.NotLeader.class, result,
                    "a follower must reject pre-append as NotLeader (definite, distinct from Indeterminate)");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void outcomeUnknownWithinDeadlineReturnsIndeterminate() throws Exception {
        ScheduledExecutorService exec = raftExecutor();
        try {
            RaftNode node = singleNodeLeader(exec);
            // 1ms end-to-end deadline. We then saturate the single tick executor so
            // the marshalled propose+register task cannot run before the deadline
            // expires - the canonical "outcome unknown within deadline" case (quorum
            // slow / tick queue stalled). The proposer must report Indeterminate,
            // distinct from both success (Committed) and definite failure
            // (NotLeader/Lost), and then dispatch the cleanup that cancels the
            // abandoned callback (no map-entry leak).
            ConfigWriteService.RaftProposer proposer =
                    ConfigdServer.raftProposer(driverFor(node), GROUP, exec, 1);

            exec.execute(() -> {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            var result = proposer.propose(null, java.util.List.of("k"), put("k", "v"));
            assertInstanceOf(ConfigWriteService.ProposeCommitResult.Indeterminate.class, result,
                    "outcome-unknown-within-deadline must be Indeterminate (distinct from success and "
                            + "from definite failure)");
        } finally {
            exec.shutdownNow();
        }
    }

    /**
     * The load-bearing "200 only on commit" property: a leader that APPENDS but
     * cannot COMMIT (its followers are severed, so quorum never forms) must NOT be
     * acknowledged as Committed - the proposer blocks past the local append and
     * returns Indeterminate at the deadline. An implementation that (incorrectly)
     * acknowledges on local append instead of on commit would return Committed here.
     */
    @Test
    void appendedButUncommittedIsNotAckedAsCommitted() throws Exception {
        ScheduledExecutorService exec = raftExecutor();
        try {
            Bus bus = new Bus();
            RaftNode n1 = busNode(1, Set.of(NodeId.of(2), NodeId.of(3)), bus);
            RaftNode n2 = busNode(2, Set.of(NodeId.of(1), NodeId.of(3)), bus);
            RaftNode n3 = busNode(3, Set.of(NodeId.of(1), NodeId.of(2)), bus);
            bus.register(NodeId.of(1), n1);
            bus.register(NodeId.of(2), n2);
            bus.register(NodeId.of(3), n3);

            exec.submit(() -> {
                for (int round = 0; round < 400 && n1.role() != RaftRole.LEADER; round++) {
                    n1.tick();
                    bus.deliverAll();
                }
            }).get(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertEquals(RaftRole.LEADER, n1.role(),
                    "n1 must be elected leader");

            // Now SEVER the followers: no further messages are delivered, so a new
            // proposal appends on the leader but can never reach a commit quorum.
            bus.sever();

            MultiRaftDriver driver = new MultiRaftDriver(NodeId.of(1), Clock.system());
            driver.addGroup(GROUP, n1);
            // Real, modest deadline so the proposer genuinely waits for a commit
            // that will never come - and keep the leader ticking so it is not the
            // executor-saturation case but a true no-quorum case.
            ConfigWriteService.RaftProposer proposer =
                    ConfigdServer.raftProposer(driver, GROUP, exec, 200);
            ScheduledFuture<?> ticker = exec.scheduleAtFixedRate(
                    n1::tick, 0, 5, TimeUnit.MILLISECONDS);
            try {
                var result = proposer.propose(null, java.util.List.of("k"), put("k", "v"));
                assertInstanceOf(ConfigWriteService.ProposeCommitResult.Indeterminate.class, result,
                        "an APPENDED-but-UNCOMMITTED write must not be acked as Committed; "
                                + "it must block to the deadline and report Indeterminate");
            } finally {
                ticker.cancel(false);
            }
        } finally {
            exec.shutdownNow();
        }
    }

    private static final class Bus {
        private final java.util.Map<NodeId, RaftNode> nodes = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.List<Runnable> queue =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private volatile boolean severed;

        void register(NodeId id, RaftNode node) { nodes.put(id, node); }
        void sever() { severed = true; queue.clear(); }

        void send(NodeId to, io.configd.raft.RaftMessage msg) {
            if (severed) return;
            RaftNode target = nodes.get(to);
            if (target != null) {
                queue.add(() -> target.handleMessage(msg));
            }
        }

        void deliverAll() {
            for (int i = 0; i < 50 && !queue.isEmpty(); i++) {
                java.util.List<Runnable> batch;
                synchronized (queue) {
                    batch = new java.util.ArrayList<>(queue);
                    queue.clear();
                }
                for (Runnable r : batch) r.run();
            }
        }
    }

    private static RaftNode busNode(int id, Set<NodeId> peers, Bus bus) {
        RaftConfig config = RaftConfig.of(NodeId.of(id), peers);
        RaftTransport transport = (target, message) -> bus.send(target, message);
        return new RaftNode(config, new RaftLog(), transport, new SeqStateMachine(),
                new java.util.Random(id * 31L + 5));
    }
}
