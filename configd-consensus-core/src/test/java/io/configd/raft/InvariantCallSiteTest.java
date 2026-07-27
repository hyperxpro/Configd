package io.configd.raft;

import io.configd.common.NodeId;
import io.configd.common.Storage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards against removal of the production {@code invariantChecker.check(...)}
 * call sites.
 * <p>
 * The existing {@link AssertionTwinFiringTest} verifies each twin's expression
 * is correctly phrased by forcing the condition false through a synthetic seam
 * ({@code fireInNodeTwinForTest}). That proves the check would fire on a real
 * violation, but it does not traverse the production call site, so deleting the
 * production {@code check(...)} call would leave it green.
 * <p>
 * This test closes that gap with a checker that records every {@code check(name,
 * ...)} invocation regardless of condition, then drives the real protocol paths
 * (apply, election, follower append, reconfig) and asserts the production code
 * actually called each named check. Removing any of those calls makes the
 * recorded set miss that name, so this test fails - the observable here is
 * "the call happened", not "the condition was false".
 * Deterministic, in-process, no sleeps.
 */
class InvariantCallSiteTest {

    /** Records every check(name,...) call, whatever the condition. Never throws. */
    static final class ObservingChecker implements RaftNode.InvariantChecker {
        final List<String> calls = new ArrayList<>();
        @Override public void check(String name, boolean condition, String message) {
            calls.add(name);
        }
        boolean observed(String name) { return calls.contains(name); }
        void clear() { calls.clear(); }
    }

    static final class CountingStateMachine implements StateMachine {
        long seq;
        @Override public long apply(long index, long term, byte[] command) {
            return (command == null || command.length == 0) ? StateMachine.NON_MUTATING : ++seq;
        }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    private static final NodeId N1 = NodeId.of(1);
    private static final NodeId N2 = NodeId.of(2);
    private static final NodeId N3 = NodeId.of(3);

    static final class CapturingTransport implements RaftTransport {
        final List<RaftMessage> sent = new ArrayList<>();
        @Override public void send(NodeId target, RaftMessage message) { sent.add(message); }
    }

    private static RaftNode singleNodeLeader(ObservingChecker checker) {
        RaftConfig config = RaftConfig.of(N1, Set.of());
        RaftNode node = new RaftNode(config, new RaftLog(), new CapturingTransport(),
                new CountingStateMachine(), new java.util.Random(11), Storage.inMemory(), checker);
        for (int i = 0; i < 301; i++) node.tick();
        assertEquals(RaftRole.LEADER, node.role());
        return node;
    }


    @Test
    void becomeLeaderInvokesElectionSafetyAndLeaderCompleteness() {
        ObservingChecker checker = new ObservingChecker();
        singleNodeLeader(checker); // election runs becomeLeader()
        assertTrue(checker.observed("election_safety"),
                "becomeLeader must invoke the election_safety check");
        assertTrue(checker.observed("leader_completeness"),
                "becomeLeader must invoke the leader_completeness check");
    }


    @Test
    void applyCommittedInvokesVersionMonotonicityAndStateMachineSafety() {
        ObservingChecker checker = new ObservingChecker();
        RaftNode node = singleNodeLeader(checker);
        checker.clear();
        // A real user proposal commits + applies inline on the single-node path,
        // traversing the production applyCommitted() check calls.
        assertEquals(ProposalResult.ACCEPTED, node.propose(new byte[]{1, 2, 3}).result());
        assertTrue(checker.observed("version_monotonicity"),
                "applyCommitted must invoke the version_monotonicity check on each apply");
        assertTrue(checker.observed("state_machine_safety"),
                "applyCommitted must invoke the state_machine_safety check on each apply");
    }

    // handleAppendEntries: log_matching (follower side)

    @Test
    void followerAppendInvokesLogMatching() {
        ObservingChecker checker = new ObservingChecker();
        RaftConfig config = RaftConfig.of(N1, Set.of(N2, N3));
        RaftNode follower = new RaftNode(config, new RaftLog(), new CapturingTransport(),
                new CountingStateMachine(), new java.util.Random(1), Storage.inMemory(), checker);
        // A non-empty AppendEntries batch reaches the log_matching check.
        follower.handleMessage(new AppendEntriesRequest(1, N2, 0, 0,
                List.of(new LogEntry(1, 1, new byte[]{1})), 0));
        assertTrue(checker.observed("log_matching"),
                "a non-empty follower append must invoke the log_matching check");
    }


    @Test
    void proposeConfigChangeInvokesReconfigTwins() {
        ObservingChecker checker = new ObservingChecker();
        RaftNode leader = singleNodeLeader(checker); // no-op commits instantly
        checker.clear();
        assertTrue(leader.proposeConfigChange(Set.of(N1, N2)));
        assertTrue(checker.observed("single_server_invariant"),
                "proposeConfigChange must invoke single_server_invariant");
        assertTrue(checker.observed("no_op_before_reconfig"),
                "proposeConfigChange must invoke no_op_before_reconfig");
        assertTrue(checker.observed("reconfig_safety"),
                "proposeConfigChange must invoke reconfig_safety");
    }

    // tickHeartbeat: inflight_window_progress (per peer)

    @Test
    void leaderHeartbeatInvokesInflightWindowProgress() {
        ObservingChecker checker = new ObservingChecker();
        // A 3-node cluster with the observing checker wired into the leader; route to
        // leadership, then step past a heartbeat interval so tickHeartbeat fires.
        RoutingCluster cluster = new RoutingCluster(3, Map.of(N1, checker));
        cluster.electFirst();
        checker.clear();
        cluster.step(150); // >= 2 heartbeat intervals with quorum - the per-peer loop runs
        assertTrue(checker.observed("inflight_window_progress"),
                "a leader heartbeat must invoke the inflight_window_progress check per peer");
    }
}
