package io.configd.raft;

import io.configd.common.NodeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests leader {@code nextIndex} walk-back arithmetic in
 * {@code RaftNode.handleAppendEntriesResponse} on a rejected AppendEntries:
 * {@code nextIndex.put(from, Math.max(1, ni - 1))} then retry (Raft section 5.3). The
 * arithmetic mutants at this site ({@code ni - 1} -> {@code ni}, the
 * {@code Math.max(1, ...)} floor) went untested because the existing
 * {@code RaftNodeTest.leaderDecrementsNextIndexOnRejection} asserts only
 * {@code commitIndex() > 0} and never forces a multi-step walk-back, so the
 * decrement could be wrong and the test still pass (the other follower carries
 * the commit). With {@code ni - 1} -> {@code ni} the leader is STUCK: it resends
 * the same too-high {@code prevLogIndex} forever and a divergent/behind follower
 * NEVER converges (a liveness/replication break).
 * <p>
 * This test drives the production rejection loop and observes the retried
 * AppendEntries' {@code prevLogIndex} STRICTLY DECREASE by one per rejection,
 * then confirms the follower converges to the leader's log.
 */
class NextIndexWalkBackTest {

    private static final NodeId LEADER = NodeId.of(1);
    private static final NodeId FOLLOWER = NodeId.of(2);

    private static final class Recorder implements RaftTransport {
        final List<RaftMessage> sent = new ArrayList<>();
        @Override public void send(NodeId target, RaftMessage message) { sent.add(message); }
        void clear() { sent.clear(); }
        AppendEntriesRequest lastAppendEntries() {
            AppendEntriesRequest last = null;
            for (RaftMessage m : sent) {
                if (m instanceof AppendEntriesRequest ae) last = ae;
            }
            return last;
        }
    }

    private static final class NoopStateMachine implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] s) { }
    }

    private static void relay(Recorder from, RaftNode to) {
        List<RaftMessage> batch = new ArrayList<>(from.sent);
        from.clear();
        for (RaftMessage m : batch) to.handleMessage(m);
    }

    @Test
    void rejectionWalksNextIndexBackOneStepAtATimeUntilTheFollowerConverges() {
        Recorder leaderTx = new Recorder();
        Recorder followerTx = new Recorder();
        RaftLog leaderLog = new RaftLog();
        RaftLog followerLog = new RaftLog();
        RaftNode leader = new RaftNode(RaftConfig.of(LEADER, Set.of(FOLLOWER)),
                leaderLog, leaderTx, new NoopStateMachine(), new java.util.Random(1));
        RaftNode follower = new RaftNode(RaftConfig.of(FOLLOWER, Set.of(LEADER)),
                followerLog, followerTx, new NoopStateMachine(), new java.util.Random(2));

        for (int i = 0; i < 400 && leader.role() != RaftRole.LEADER; i++) {
            leader.tick();
            relay(leaderTx, follower);
            relay(followerTx, leader);
        }
        assertEquals(RaftRole.LEADER, leader.role(), "leader must self-elect with the follower's vote");
        for (int i = 0; i < 5; i++) leader.propose(new byte[]{(byte) (100 + i)});
        for (int r = 0; r < 30; r++) {
            relay(leaderTx, follower);
            relay(followerTx, leader);
        }
        assertEquals(leaderLog.lastIndex(), followerLog.lastIndex(), "precondition: follower in sync");
        long top = leaderLog.lastIndex();
        assertTrue(top >= 6, "leader has a multi-entry log to walk back over (was " + top + ")");

        AppendEntriesRequest first = null;
        for (int i = 0; i < 50 && first == null; i++) {
            leaderTx.clear();
            leader.tick();
            first = leaderTx.lastAppendEntries();
        }
        assertNotNull(first, "the leader must send an AppendEntries on heartbeat");
        long prev = first.prevLogIndex();
        assertEquals(top, prev,
                "the in-sync follower's nextIndex is lastIndex+1, so the heartbeat prevLogIndex = lastIndex");

        for (long expected = prev - 1; expected >= 1; expected--) {
            leaderTx.clear();
            leader.handleMessage(new AppendEntriesResponse(leader.currentTerm(), false, 0, FOLLOWER));
            AppendEntriesRequest retry = leaderTx.lastAppendEntries();
            assertNotNull(retry, "a rejection must trigger a retried AppendEntries");
            assertEquals(expected, retry.prevLogIndex(),
                    "RR-085: each rejection must decrement the retried prevLogIndex by exactly one "
                            + "(Raft §5.3 walk-back). A frozen prevLogIndex (the ni-1 -> ni mutant) "
                            + "means the leader can never reconcile a behind/divergent follower.");
        }

        // The floor: one more rejection at prevLogIndex==1 walks nextIndex to 1
        // (prevLogIndex 0), where an empty/behind follower finally accepts. Math.max(1,...)
        // keeps nextIndex >= 1 (prevLogIndex never negative). Drive the catch-up to
        // convergence over the real protocol from here.
        for (int r = 0; r < 80; r++) {
            relay(leaderTx, follower);
            relay(followerTx, leader);
            leader.tick();
        }
        assertEquals(leaderLog.lastIndex(), followerLog.lastIndex(),
                "the follower must converge to the leader's log via the walk-back");
        assertEquals(leaderLog.lastTerm(), followerLog.lastTerm(),
                "the converged follower must match the leader's last term");
    }
}
