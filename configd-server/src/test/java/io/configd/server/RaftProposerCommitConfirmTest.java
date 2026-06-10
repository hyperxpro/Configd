package io.configd.server;

import io.configd.api.ConfigWriteService;
import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftTransport;
import io.configd.raft.StateMachine;
import io.configd.replication.MultiRaftDriver;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * RR-004 / ADR-0033: proves {@code ConfigdServer.raftProposer} is
 * <b>commit-confirmed</b> and produces the distinguishable failure taxonomy a
 * client needs — an uncommitted write is reported as something OTHER than success
 * and OTHER than a permanent failure (charter §3: "the error a client receives
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

    /** Minimal mutating state machine: assigns a monotonic seq per non-empty command. */
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
        RaftConfig config = RaftConfig.of(NodeId.of(1), Set.of()); // single-node
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

            var result = proposer.propose(null, put("k", "v"));
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
            // Multi-node config, no election driven → node stays FOLLOWER.
            RaftConfig config = RaftConfig.of(NodeId.of(1), Set.of(NodeId.of(2), NodeId.of(3)));
            RaftNode follower = new RaftNode(config, new RaftLog(),
                    (target, message) -> { }, new SeqStateMachine(), new java.util.Random(7));
            ConfigWriteService.RaftProposer proposer =
                    ConfigdServer.raftProposer(driverFor(follower), GROUP, exec, 5000);

            var result = proposer.propose(null, put("k", "v"));
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
            // expires — the canonical "outcome unknown within deadline" case (quorum
            // slow / tick queue stalled). The proposer must report Indeterminate,
            // distinct from both success (Committed) and definite failure
            // (NotLeader/Lost), and then dispatch the cleanup that cancels the
            // abandoned callback (no map-entry leak).
            ConfigWriteService.RaftProposer proposer =
                    ConfigdServer.raftProposer(driverFor(node), GROUP, exec, 1 /* ms */);

            exec.execute(() -> {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            var result = proposer.propose(null, put("k", "v"));
            assertInstanceOf(ConfigWriteService.ProposeCommitResult.Indeterminate.class, result,
                    "outcome-unknown-within-deadline must be Indeterminate (distinct from success and "
                            + "from definite failure)");
        } finally {
            exec.shutdownNow();
        }
    }
}
