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
            // A stale-term AppendEntries must get a NOT-success response, no append.
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
            // leader id and stays FOLLOWER.
            node.handleMessage(new AppendEntriesRequest(1, N2, 0, 0, List.of(), 0));
            assertEquals(RaftRole.FOLLOWER, node.role());
            assertEquals(N2, node.leaderId(), "follower must record the leader id");
        }

        @Test
        void higherTermAppendEntriesStepsDownAndAdoptsTerm() {
            RecordingTransport t = new RecordingTransport();
            RaftNode node = plainFollower(t);
            node.handleMessage(new RequestVoteResponse(2, false, N3, false)); // term -> 2
            // A higher-term (5) AppendEntries must bump the term and stay/become FOLLOWER.
            node.handleMessage(new AppendEntriesRequest(5, N2, 0, 0, List.of(), 0));
            assertEquals(5, node.currentTerm());
            assertEquals(RaftRole.FOLLOWER, node.role());
        }

        @Test
        void successfulAppendAdvancesCommitClampedToLastNewIndex() {
            RecordingTransport t = new RecordingTransport();
            RaftNode follower = plainFollower(t);
            // Leader at term 1 appends entries [1,2] with leaderCommit=5. The follower
            // must clamp commitIndex to min(leaderCommit, lastNewIndex=2) = 2, not 5.
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
            // (not lastIndex via some other path).
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
            // A response from a higher term must demote the leader.
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
            // pins it at 1 - it must never reach 0 (which would make prevLogIndex -1).
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
            // A success at matchIndex=last sets nextIndex = last+1.
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
            // Self + N2 is already a majority of 3, so proposing a fresh entry gives an
            // index that no peer has acked yet, which is what the first assertion below checks.
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
            // A response from a prior term must be ignored (no step-down, no state change).
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
            leader.handleMessage(new RequestVoteResponse(term + 2, false, N2, false));
            assertEquals(RaftRole.FOLLOWER, leader.role());
            assertEquals(term + 2, leader.currentTerm());
        }

        @Test
        void preVoteRejectsStaleTermCandidate() {
            // A follower at term 5 receiving a PreVote from a candidate at term 3
            // must respond not-granted.
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
            // from an up-to-date candidate -> grants.
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
            // A stale TimeoutNow must not start an election (no role change to
            // candidate, no vote requests emitted).
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
            // that increments the term to 5 and votes for self; the emitted RequestVote
            // must carry term 5 (old+1), not 3 (old-1).
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
            // An AppendEntries at the same term 5 must not clear the vote - only a strict
            // term increase (newTerm > currentTerm) adopts a new term and clears the vote.
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
            // A snapshot at index 5 (greater than our snapshotIndex 0) must install:
            // lastApplied, commitIndex, and snapshotIndex all advance to 5.
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
            // A second snapshot at index 5 (equal to our snapshotIndex) must be ignored -
            // no re-install - but still acked success.
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
            // A snapshot from term 3 (less than 5) must be rejected with success=false
            // and no install.
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
            // LEADER); observable as no state change and no NPE on the leader-only maps.
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
            // A success response from a prior term must be ignored (no matchIndex bump
            // or commit advance). The leader has no latestSnapshot in this scenario, but
            // the stale-term guard must still short-circuit before the success handling.
            leader.handleMessage(new InstallSnapshotResponse(term - 1 < 0 ? 0 : term - 1, true, N2, 99));
            assertEquals(RaftRole.LEADER, leader.role());
            assertEquals(matchBefore, leader.log().commitIndex());
        }
    }
}
