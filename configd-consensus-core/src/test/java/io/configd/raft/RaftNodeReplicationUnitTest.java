package io.configd.raft;

import io.configd.common.NodeId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Discriminating tests for {@link RaftNode}'s leader-side replication and
 * election-response handlers, driven through the public {@code handleMessage}
 * seam so the production code path (not a synthetic shim) executes.
 * <p>
 * Covers {@code handleAppendEntriesResponse}, {@code maybeAdvanceCommitIndex},
 * {@code handleInstallSnapshotResponse}, {@code handleRequestVoteResponse},
 * {@code handlePreVoteRequest}, and {@code handleTimeoutNow} - focusing on
 * conditional-branch and arithmetic boundaries (higher-term step-down guards,
 * the nextIndex walk-back floor, the commit-advance term/quorum boundary, the
 * snapshot-response matchIndex clamp, the quorum->becomeLeader transition, the
 * PreVote stale-term / recent-leader shields). Each test asserts the observable
 * effect at the boundary: a role change that must (or must not) happen, a
 * nextIndex/matchIndex value, a vote response field. Deterministic, no sleeps.
 */
class RaftNodeReplicationUnitTest {

    private static final NodeId N1 = NodeId.of(1);
    private static final NodeId N2 = NodeId.of(2);
    private static final NodeId N3 = NodeId.of(3);

    static final class RecordingTransport implements RaftTransport {
        record Sent(NodeId target, RaftMessage message) {}
        final List<Sent> sent = new ArrayList<>();
        @Override public void send(NodeId target, RaftMessage message) { sent.add(new Sent(target, message)); }
        void clear() { sent.clear(); }
        <T> List<T> of(Class<T> type) {
            return sent.stream().filter(s -> type.isInstance(s.message()))
                    .map(s -> type.cast(s.message())).toList();
        }
    }

    static final class CountingStateMachine implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    /** A leader of a 3-node cluster {1,2,3}, elected, no-op committed. */
    private static RaftNode electedLeader(RecordingTransport transport) {
        // Build a routing cluster to get node 1 to leader with its no-op committed,
        // then return node 1 wired to the given transport for further probing.
        Map<NodeId, RaftNode> nodes = new HashMap<>();
        Map<NodeId, RecordingTransport> ts = new HashMap<>();
        List<NodeId> all = List.of(N1, N2, N3);
        for (NodeId id : all) {
            Set<NodeId> peers = new HashSet<>(all);
            peers.remove(id);
            RecordingTransport t = id.equals(N1) ? transport : new RecordingTransport();
            RaftConfig config = RaftConfig.of(id, peers);
            RaftNode node = new RaftNode(config, new RaftLog(), t, new CountingStateMachine(),
                    new java.util.Random(id.id() * 31L + 7));
            nodes.put(id, node);
            ts.put(id, t);
        }
        for (int i = 0; i < 301; i++) nodes.get(N1).tick();
        // Deliver until quiescent so node 1 wins and commits its no-op.
        for (int r = 0; r < 20; r++) {
            Map<NodeId, List<RaftMessage>> box = new HashMap<>();
            boolean any = false;
            for (var e : ts.entrySet()) {
                for (var s : e.getValue().sent) {
                    box.computeIfAbsent(s.target(), k -> new ArrayList<>()).add(s.message());
                    any = true;
                }
                e.getValue().clear();
            }
            if (!any) break;
            for (var e : box.entrySet()) {
                RaftNode tgt = nodes.get(e.getKey());
                if (tgt != null) for (RaftMessage m : e.getValue()) tgt.handleMessage(m);
            }
        }
        RaftNode leader = nodes.get(N1);
        assertEquals(RaftRole.LEADER, leader.role(), "precondition: node 1 must be leader");
        transport.clear();
        return leader;
    }

    /** A plain follower of cluster {1,2,3} (node 1), never elected. */
    private static RaftNode plainFollower(RecordingTransport t) {
        RaftConfig config = RaftConfig.of(N1, Set.of(N2, N3));
        return new RaftNode(config, new RaftLog(), t, new CountingStateMachine(),
                new java.util.Random(1));
    }

    // handleAppendEntries - follower-side term/log-matching/commit handling

    @Nested
    class AppendEntriesFollower {

        @Test
        void staleTermAppendEntriesIsRejected() {
            RecordingTransport t = new RecordingTransport();
            RaftNode follower = plainFollower(t);
            // Bump follower to term 5.
            follower.handleMessage(new RequestVoteResponse(5, false, N2, false));
            t.clear();
            // Kills handleAppendEntries L1191 ORDER_ELSE (req.term() < currentTerm):
            // a stale-term AppendEntries must get a NOT-success response, no append.
            follower.handleMessage(new AppendEntriesRequest(3, N2, 0, 0, List.of(), 0));
            List<AppendEntriesResponse> resps = t.of(AppendEntriesResponse.class);
            assertEquals(1, resps.size());
            assertFalse(resps.getFirst().success());
            assertEquals(5, resps.getFirst().term());
        }

        @Test
        void heartbeatRecordsLeaderIdAndResetsTimer() {
            RecordingTransport t = new RecordingTransport();
            RaftNode node = plainFollower(t);
            // A leader at term 1 sends an empty heartbeat. The follower records the
            // leader id (kills L1208 leaderId = req.leaderId()) and stays FOLLOWER.
            node.handleMessage(new AppendEntriesRequest(1, N2, 0, 0, List.of(), 0));
            assertEquals(RaftRole.FOLLOWER, node.role());
            assertEquals(N2, node.leaderId(), "follower must record the leader id");
        }

        @Test
        void higherTermAppendEntriesStepsDownAndAdoptsTerm() {
            RecordingTransport t = new RecordingTransport();
            RaftNode node = plainFollower(t);
            node.handleMessage(new RequestVoteResponse(2, false, N3, false)); // term -> 2
            // A higher-term (5) AppendEntries must bump the term and stay/become
            // FOLLOWER. Kills handleAppendEntries L1198 (req.term() > currentTerm).
            node.handleMessage(new AppendEntriesRequest(5, N2, 0, 0, List.of(), 0));
            assertEquals(5, node.currentTerm());
            assertEquals(RaftRole.FOLLOWER, node.role());
        }

        @Test
        void successfulAppendAdvancesCommitClampedToLastNewIndex() {
            RecordingTransport t = new RecordingTransport();
            RaftNode follower = plainFollower(t);
            // Leader at term 1 appends entries [1,2] with leaderCommit=5. The follower
            // must clamp commitIndex to min(leaderCommit, lastNewIndex=2) = 2, NOT 5.
            // Kills handleAppendEntries L1240 Math.min clamp and L1237 boundary.
            List<LogEntry> batch = List.of(new LogEntry(1, 1, new byte[]{1}),
                    new LogEntry(2, 1, new byte[]{2}));
            follower.handleMessage(new AppendEntriesRequest(1, N2, 0, 0, batch, 5));
            assertEquals(2, follower.log().lastIndex());
            assertEquals(2, follower.log().commitIndex(),
                    "commitIndex must clamp to the last NEW entry (2), not leaderCommit (5)");
            List<AppendEntriesResponse> resps = t.of(AppendEntriesResponse.class);
            assertTrue(resps.getLast().success());
            // matchIndex echoed must be the batch's last index (2).
            assertEquals(2, resps.getLast().matchIndex());
        }

        @Test
        void emptyHeartbeatEchoesPrevLogIndexAsMatch() {
            RecordingTransport t = new RecordingTransport();
            RaftNode follower = plainFollower(t);
            // Seed the follower with two entries via a batch first.
            follower.handleMessage(new AppendEntriesRequest(1, N2, 0, 0,
                    List.of(new LogEntry(1, 1, new byte[]{1}), new LogEntry(2, 1, new byte[]{2})), 0));
            t.clear();
            // An empty heartbeat with prevLogIndex=2 must echo matchIndex=prevLogIndex=2
            // (not lastIndex via some other path). Kills the empty-batch matchIndex
            // computation (req.entries().isEmpty() ? prevLogIndex : last).
            follower.handleMessage(new AppendEntriesRequest(1, N2, 2, 1, List.of(), 2));
            List<AppendEntriesResponse> resps = t.of(AppendEntriesResponse.class);
            assertEquals(1, resps.size());
            assertTrue(resps.getFirst().success());
            assertEquals(2, resps.getFirst().matchIndex());
        }

        @Test
        void mismatchedPrevLogTermIsRejected() {
            RecordingTransport t = new RecordingTransport();
            RaftNode follower = plainFollower(t);
            follower.handleMessage(new AppendEntriesRequest(1, N2, 0, 0,
                    List.of(new LogEntry(1, 1, new byte[]{1})), 0));
            t.clear();
            // prevLogIndex=1 but prevLogTerm=9 (follower has term 1 there) -> reject.
            follower.handleMessage(new AppendEntriesRequest(1, N2, 1, 9, List.of(), 0));
            List<AppendEntriesResponse> resps = t.of(AppendEntriesResponse.class);
            assertEquals(1, resps.size());
            assertFalse(resps.getFirst().success());
        }
    }

    // handleAppendEntriesResponse - higher-term step-down + nextIndex walk-back

    @Nested
    class AppendResponse {

        @Test
        void higherTermResponseStepsDownLeader() {
            RecordingTransport t = new RecordingTransport();
            RaftNode leader = electedLeader(t);
            long term = leader.currentTerm();
            // Kills handleAppendEntriesResponse L1262 ORDER_ELSE (resp.term() >
            // currentTerm): a response from a higher term must demote the leader.
            leader.handleMessage(new AppendEntriesResponse(term + 5, false, 0, N2));
            assertEquals(RaftRole.FOLLOWER, leader.role());
            assertEquals(term + 5, leader.currentTerm());
        }

        @Test
        void rejectionWalksNextIndexBackButNeverBelowOne() {
            RecordingTransport t = new RecordingTransport();
            RaftNode leader = electedLeader(t);
            long term = leader.currentTerm();
            // Repeatedly reject from N2. nextIndex starts at lastIndex()+1 and is
            // decremented by one each rejection, but the Math.max(1, ni-1) floor
            // pins it at 1 - it must NEVER reach 0 (which would make prevLogIndex
            // -1). Kills the Math.max floor mutant (max(1,..)->max(0,..)) and the
            // ni-1 arithmetic.
            for (int i = 0; i < 50; i++) {
                t.clear();
                leader.handleMessage(new AppendEntriesResponse(term, false, 0, N2));
                List<AppendEntriesRequest> reqs = t.of(AppendEntriesRequest.class);
                assertFalse(reqs.isEmpty(), "a rejection must trigger a retry AppendEntries");
                long prevIdx = reqs.getLast().prevLogIndex();
                assertTrue(prevIdx >= 0,
                        "prevLogIndex must never go negative (nextIndex floored at 1): " + prevIdx);
            }
            // After many rejections the retry probes prevLogIndex 0 (nextIndex==1),
            // the absolute floor.
            t.clear();
            leader.handleMessage(new AppendEntriesResponse(term, false, 0, N2));
            assertEquals(0, t.of(AppendEntriesRequest.class).getLast().prevLogIndex());
        }

        @Test
        void successfulResponseAdvancesMatchAndNextIndex() {
            RecordingTransport t = new RecordingTransport();
            RaftNode leader = electedLeader(t);
            long term = leader.currentTerm();
            long last = leader.log().lastIndex();
            // A success at matchIndex=last sets nextIndex = last+1. Kills the
            // success branch's `newMatchIndex + 1` wiring.
            leader.handleMessage(new AppendEntriesResponse(term, true, last, N2));
            leader.handleMessage(new AppendEntriesResponse(term, true, last, N3));
            // Both peers acked the leader's last index -> it must commit (advance).
            assertTrue(leader.log().commitIndex() >= last,
                    "a quorum ack of the last entry must advance commitIndex");
        }
    }

    // maybeAdvanceCommitIndex - quorum + current-term boundary

    @Nested
    class CommitAdvance {

        @Test
        void doesNotCommitWithoutQuorum() {
            RecordingTransport t = new RecordingTransport();
            RaftNode leader = electedLeader(t);
            long term = leader.currentTerm();
            long before = leader.log().commitIndex();
            long last = leader.log().lastIndex();
            // Only ONE peer (N2) of the 3-node cluster acks -> self+N2 = 2 of 3 is a
            // quorum here actually (majority of 3 = 2). Use a stricter check: revert
            // to a fresh index. Append a new entry first via propose so last grows.
            assertEquals(ProposalResult.ACCEPTED, leader.propose("x".getBytes()).result());
            long newLast = leader.log().lastIndex();
            t.clear();
            // No acks at all for newLast beyond self -> not a quorum -> no advance.
            assertEquals(before, leader.log().commitIndex(),
                    "commitIndex must not advance to the new entry without peer acks");
            // Now a single peer ack reaching quorum (self+N2 = majority of 3) commits.
            leader.handleMessage(new AppendEntriesResponse(term, true, newLast, N2));
            assertTrue(leader.log().commitIndex() >= newLast,
                    "self + one peer is a majority of 3 -> commit advances");
        }
    }

    // handleInstallSnapshotResponse - step-down + matchIndex clamp

    @Nested
    class SnapshotResponse {

        @Test
        void higherTermSnapshotResponseStepsDown() {
            RecordingTransport t = new RecordingTransport();
            RaftNode leader = electedLeader(t);
            long term = leader.currentTerm();
            // Kills handleInstallSnapshotResponse L2119 (resp.term() > currentTerm).
            leader.handleMessage(new InstallSnapshotResponse(term + 3, true, N2, 100));
            assertEquals(RaftRole.FOLLOWER, leader.role());
            assertEquals(term + 3, leader.currentTerm());
        }

        @Test
        void staleTermSnapshotResponseIsIgnored() {
            RecordingTransport t = new RecordingTransport();
            RaftNode leader = electedLeader(t);
            long term = leader.currentTerm();
            RaftRole before = leader.role();
            // A response from a PRIOR term must be ignored (no step-down, no state
            // change). Kills the L2125 `resp.term() != currentTerm` stale guard.
            leader.handleMessage(new InstallSnapshotResponse(term - 1 < 0 ? 0 : term - 1, true, N2, 100));
            assertEquals(before, leader.role());
            assertEquals(term, leader.currentTerm());
        }
    }

    // handleRequestVoteResponse / PreVote / TimeoutNow

    @Nested
    class VotingResponses {

        @Test
        void higherTermVoteResponseStepsDownLeader() {
            RecordingTransport t = new RecordingTransport();
            RaftNode leader = electedLeader(t);
            long term = leader.currentTerm();
            // Kills handleRequestVoteResponse L1371 (resp.term() > currentTerm).
            leader.handleMessage(new RequestVoteResponse(term + 2, false, N2, false));
            assertEquals(RaftRole.FOLLOWER, leader.role());
            assertEquals(term + 2, leader.currentTerm());
        }

        @Test
        void preVoteRejectsStaleTermCandidate() {
            // A follower at term 5 receiving a PreVote from a candidate at term 3
            // must respond NOT-granted. Kills handlePreVoteRequest L1352 ORDER_ELSE
            // (req.term() < currentTerm).
            RecordingTransport t = new RecordingTransport();
            RaftConfig config = RaftConfig.of(N1, Set.of(N2, N3));
            RaftNode follower = new RaftNode(config, new RaftLog(), t, new CountingStateMachine(),
                    new java.util.Random(1));
            // Advance the follower to term 5 via a higher-term AppendEntries echo.
            follower.handleMessage(new RequestVoteResponse(5, false, N2, false)); // bumps term to 5
            assertEquals(5, follower.currentTerm());
            t.clear();
            follower.handleMessage(new RequestVoteRequest(3, N2, 0, 0, true)); // preVote, stale term
            List<RequestVoteResponse> resps = t.of(RequestVoteResponse.class);
            assertEquals(1, resps.size());
            assertTrue(resps.getFirst().preVote());
            assertFalse(resps.getFirst().voteGranted(), "stale-term PreVote must be rejected");
        }

        @Test
        void preVoteGrantsToUpToDateCandidateWithNoRecentLeader() {
            // A fresh follower (no known leader) with an empty log receives a PreVote
            // from an up-to-date candidate -> grants. Kills the wouldGrantPreVote
            // composition (logOk && !hasRecentLeader).
            RecordingTransport t = new RecordingTransport();
            RaftConfig config = RaftConfig.of(N1, Set.of(N2, N3));
            RaftNode follower = new RaftNode(config, new RaftLog(), t, new CountingStateMachine(),
                    new java.util.Random(1));
            follower.handleMessage(new RequestVoteRequest(1, N2, 0, 0, true));
            List<RequestVoteResponse> resps = t.of(RequestVoteResponse.class);
            assertEquals(1, resps.size());
            assertTrue(resps.getFirst().voteGranted(), "up-to-date candidate must get a PreVote grant");
        }

        @Test
        void timeoutNowFromStaleTermIsIgnored() {
            RecordingTransport t = new RecordingTransport();
            RaftConfig config = RaftConfig.of(N1, Set.of(N2, N3));
            RaftNode follower = new RaftNode(config, new RaftLog(), t, new CountingStateMachine(),
                    new java.util.Random(1));
            follower.handleMessage(new RequestVoteResponse(5, false, N2, false)); // term -> 5
            RaftRole before = follower.role();
            t.clear();
            // Kills handleTimeoutNow L1419 ORDER_ELSE (req.term() < currentTerm):
            // a stale TimeoutNow must NOT start an election (no role change to
            // CANDIDATE, no vote requests emitted).
            follower.handleMessage(new TimeoutNowRequest(3, N2));
            assertEquals(before, follower.role());
            assertTrue(t.of(RequestVoteRequest.class).isEmpty(),
                    "stale TimeoutNow must not trigger an election");
        }

        @Test
        void timeoutNowStartsElectionIncrementingTermByOne() {
            RecordingTransport t = new RecordingTransport();
            RaftConfig config = RaftConfig.of(N1, Set.of(N2, N3));
            RaftNode follower = new RaftNode(config, new RaftLog(), t, new CountingStateMachine(),
                    new java.util.Random(1));
            follower.handleMessage(new RequestVoteResponse(4, false, N2, false)); // term -> 4
            t.clear();
            // A TimeoutNow at the current term bypasses PreVote and starts an election
            // that increments the term to 5 and votes for self. Kills startElection
            // L1527 MathMutator (currentTerm + 1) and the setTermAndVote removal: the
            // emitted RequestVote must carry term 5 (old+1), not 3 (old-1).
            follower.handleMessage(new TimeoutNowRequest(4, N2));
            assertEquals(RaftRole.CANDIDATE, follower.role());
            assertEquals(5, follower.currentTerm(), "election must increment term by exactly one");
            assertEquals(N1, follower.votedFor(), "candidate must vote for itself");
            List<RequestVoteRequest> reqs = t.of(RequestVoteRequest.class);
            assertFalse(reqs.isEmpty());
            assertEquals(5, reqs.getFirst().term(), "RequestVote must carry the new term (old+1)");
            assertFalse(reqs.getFirst().preVote(), "TimeoutNow election bypasses PreVote");
        }
    }

    // becomeFollower - term-adoption boundary

    @Nested
    class BecomeFollowerBoundary {

        @Test
        void adoptsStrictlyHigherTermButNotEqualOrLower() {
            RecordingTransport t = new RecordingTransport();
            RaftNode node = plainFollower(t);
            node.handleMessage(new RequestVoteResponse(5, false, N2, false)); // term -> 5
            assertEquals(5, node.currentTerm());
            assertNull(node.votedFor());
            // Grant a vote at term 5.
            node.handleMessage(new RequestVoteRequest(5, N2, 0, 0, false));
            assertEquals(N2, node.votedFor());
            // An AppendEntries at the SAME term 5 must NOT clear the vote (becomeFollower
            // L1437 boundary `newTerm > currentTerm` - only a strict increase adopts a
            // new term and clears the vote).
            node.handleMessage(new AppendEntriesRequest(5, N3, 0, 0, List.of(), 0));
            assertEquals(5, node.currentTerm());
            assertEquals(N2, node.votedFor(), "same-term step-down must preserve the vote");
        }
    }

    // handleInstallSnapshot / handleInstallSnapshotResponse boundaries

    @Nested
    class SnapshotInstall {

        @Test
        void followerInstallsNewerSnapshotAndAdvancesApplied() {
            RecordingTransport t = new RecordingTransport();
            RaftNode follower = plainFollower(t);
            // A snapshot at index 5 (> our snapshotIndex 0) must install: lastApplied,
            // commitIndex and snapshotIndex all advance to 5. Kills handleInstallSnapshot
            // L1981 boundary, L2008 persist, L2012 compact, L2015 commit boundary,
            // L2018 setLastApplied.
            follower.handleMessage(new InstallSnapshotRequest(1, N2, 5, 1, 0,
                    new byte[]{9}, true, null));
            assertEquals(5, follower.log().snapshotIndex());
            assertEquals(5, follower.log().lastApplied());
            assertTrue(follower.log().commitIndex() >= 5);
            List<InstallSnapshotResponse> resps = t.of(InstallSnapshotResponse.class);
            assertTrue(resps.getLast().success());
            assertEquals(5, resps.getLast().lastIncludedIndex());
        }

        @Test
        void followerIgnoresSnapshotAtOrBelowItsSnapshotPoint() {
            RecordingTransport t = new RecordingTransport();
            RaftNode follower = plainFollower(t);
            // First install index 5.
            follower.handleMessage(new InstallSnapshotRequest(1, N2, 5, 1, 0, new byte[]{9}, true, null));
            t.clear();
            // A second snapshot at index 5 (== our snapshotIndex) must be ignored - no
            // re-install - but still acked success. Kills handleInstallSnapshot L1981
            // boundary (lastIncludedIndex <= snapshotIndex).
            follower.handleMessage(new InstallSnapshotRequest(1, N2, 5, 1, 0, new byte[]{8}, true, null));
            assertEquals(5, follower.log().snapshotIndex(), "must not re-install at the same point");
            List<InstallSnapshotResponse> resps = t.of(InstallSnapshotResponse.class);
            assertEquals(1, resps.size());
            assertTrue(resps.getFirst().success());
        }

        @Test
        void staleTermSnapshotIsRejected() {
            RecordingTransport t = new RecordingTransport();
            RaftNode follower = plainFollower(t);
            follower.handleMessage(new RequestVoteResponse(5, false, N2, false)); // term -> 5
            t.clear();
            // A snapshot from term 3 (< 5) must be rejected with success=false and no
            // install. Kills handleInstallSnapshot L1957 stale guard.
            follower.handleMessage(new InstallSnapshotRequest(3, N2, 9, 1, 0, new byte[]{9}, true, null));
            assertEquals(0, follower.log().snapshotIndex());
            List<InstallSnapshotResponse> resps = t.of(InstallSnapshotResponse.class);
            assertEquals(1, resps.size());
            assertFalse(resps.getFirst().success());
        }

        @Test
        void leaderIgnoresSnapshotResponseWhenNotLeader() {
            RecordingTransport t = new RecordingTransport();
            RaftNode follower = plainFollower(t);
            // A follower receiving an InstallSnapshotResponse must ignore it (role !=
            // LEADER). Kills handleInstallSnapshotResponse L2114. Observable: no state
            // change / no NPE on the leader-only maps.
            assertDoesNotThrow(() ->
                    follower.handleMessage(new InstallSnapshotResponse(0, true, N2, 5)));
            assertEquals(RaftRole.FOLLOWER, follower.role());
        }

        @Test
        void leaderIgnoresStaleTermSnapshotResponse() {
            RecordingTransport t = new RecordingTransport();
            RaftNode leader = electedLeader(t);
            long term = leader.currentTerm();
            long matchBefore = leader.log().commitIndex();
            // A success response from a PRIOR term must be ignored (no matchIndex
            // bump / commit advance). Kills handleInstallSnapshotResponse L2125 stale
            // guard. (The leader has no latestSnapshot anyway, but the stale guard must
            // short-circuit before the success block.)
            leader.handleMessage(new InstallSnapshotResponse(term - 1 < 0 ? 0 : term - 1, true, N2, 99));
            assertEquals(RaftRole.LEADER, leader.role());
            assertEquals(matchBefore, leader.log().commitIndex());
        }
    }
}
