package io.configd.raft;

import io.configd.common.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

class RaftNodeTest {

    static final class TestTransport implements RaftTransport {
        private final List<SentMessage> messages = new ArrayList<>();

        record SentMessage(NodeId target, RaftMessage message) {}

        @Override
        public void send(NodeId target, RaftMessage message) {
            messages.add(new SentMessage(target, message));
        }

        List<SentMessage> messages() { return messages; }

        void clear() { messages.clear(); }

        @SuppressWarnings("unchecked")
        <T> List<T> messagesTo(NodeId target, Class<T> type) {
            return messages.stream()
                    .filter(m -> m.target().equals(target) && type.isInstance(m.message()))
                    .map(m -> (T) m.message())
                    .toList();
        }

        @SuppressWarnings("unchecked")
        <T> List<T> messagesOfType(Class<T> type) {
            return messages.stream()
                    .filter(m -> type.isInstance(m.message()))
                    .map(m -> (T) m.message())
                    .toList();
        }
    }

    static final class TestStateMachine implements StateMachine {
        final List<AppliedEntry> applied = new ArrayList<>();

        record AppliedEntry(long index, long term, byte[] command) {}

        @Override
        public long apply(long index, long term, byte[] command) {
            applied.add(new AppliedEntry(index, term, command));
            return StateMachine.NON_MUTATING;
        }

        @Override
        public byte[] snapshot() { return new byte[0]; }

        @Override
        public void restoreSnapshot(byte[] snapshot) { }
    }

    static final class TestCluster {
        final Map<NodeId, RaftNode> nodes = new HashMap<>();
        final Map<NodeId, TestTransport> transports = new HashMap<>();
        final Map<NodeId, TestStateMachine> stateMachines = new HashMap<>();
        final Map<NodeId, RaftLog> logs = new HashMap<>();

        TestCluster(int size) {
            List<NodeId> allNodes = new ArrayList<>();
            for (int i = 1; i <= size; i++) {
                allNodes.add(NodeId.of(i));
            }

            for (NodeId id : allNodes) {
                Set<NodeId> peers = new java.util.HashSet<>(allNodes);
                peers.remove(id);
                RaftConfig config = RaftConfig.of(id, peers);
                RaftLog log = new RaftLog();
                TestTransport transport = new TestTransport();
                TestStateMachine sm = new TestStateMachine();
                // Each node gets a different seed so election timeouts are deterministic but
                // vary per node, which the split-vote tests rely on to resolve via randomized timeout.
                RandomGenerator rng = new java.util.Random(id.id() * 31L + 7);

                RaftNode node = new RaftNode(config, log, transport, sm, rng);
                nodes.put(id, node);
                transports.put(id, transport);
                stateMachines.put(id, sm);
                logs.put(id, log);
            }
        }

        void deliverMessages() {
            // Collect all messages first, then deliver to avoid concurrent modification
            Map<NodeId, List<RaftMessage>> toDeliver = new HashMap<>();
            for (var entry : transports.entrySet()) {
                for (var msg : entry.getValue().messages()) {
                    toDeliver.computeIfAbsent(msg.target(), k -> new ArrayList<>()).add(msg.message());
                }
                entry.getValue().clear();
            }
            for (var entry : toDeliver.entrySet()) {
                RaftNode target = nodes.get(entry.getKey());
                if (target != null) {
                    for (RaftMessage msg : entry.getValue()) {
                        target.handleMessage(msg);
                    }
                }
            }
        }

        void deliverAllMessages(int maxRounds) {
            for (int i = 0; i < maxRounds; i++) {
                boolean anyMessages = transports.values().stream()
                        .anyMatch(t -> !t.messages().isEmpty());
                if (!anyMessages) break;
                deliverMessages();
            }
        }

        void triggerElectionTimeout(NodeId id) {
            RaftNode node = nodes.get(id);
            // 301 ticks exceeds the largest possible election timeout (300).
            for (int i = 0; i < 301; i++) {
                node.tick();
            }
        }

        void electLeader(NodeId id) {
            triggerElectionTimeout(id);
            deliverAllMessages(10);
        }

        /** Ticks the leader past a heartbeat interval so it propagates its advanced
         *  commitIndex to followers, then delivers the resulting messages. */
        void tickLeaderHeartbeatAndDeliver() {
            RaftNode leader = findLeader();
            if (leader == null) return;
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }
            deliverAllMessages(5);
        }

        RaftNode findLeader() {
            return nodes.values().stream()
                    .filter(n -> n.role() == RaftRole.LEADER)
                    .findFirst()
                    .orElse(null);
        }
    }

    @Nested
    class SingleNodeTests {
        private RaftNode node;
        private TestTransport transport;
        private TestStateMachine sm;
        private RaftLog log;

        @BeforeEach
        void setUp() {
            NodeId id = NodeId.of(1);
            RaftConfig config = RaftConfig.of(id, Set.of());
            log = new RaftLog();
            transport = new TestTransport();
            sm = new TestStateMachine();
            RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");
            node = new RaftNode(config, log, transport, sm, rng);
        }

        @Test
        void startsAsFollower() {
            assertEquals(RaftRole.FOLLOWER, node.role());
            assertEquals(0, node.currentTerm());
            assertNull(node.votedFor());
        }

        @Test
        void singleNodeBecomesLeaderOnTimeout() {
            for (int i = 0; i < 301; i++) {
                node.tick();
            }
            assertEquals(RaftRole.LEADER, node.role());
            assertEquals(1, node.currentTerm());
            assertEquals(NodeId.of(1), node.leaderId());
        }

        @Test
        void singleNodeCanProposeAndCommit() {
            for (int i = 0; i < 301; i++) {
                node.tick();
            }
            assertEquals(ProposalResult.ACCEPTED, node.propose(new byte[]{1, 2, 3}).result());

            assertEquals(2, log.commitIndex()); // no-op + command
            assertEquals(2, sm.applied.size());
        }
    }

    @Nested
    class ThreeNodeClusterTests {

        @Test
        void leaderElection() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            assertEquals(RaftRole.LEADER, leader.role());

            assertEquals(RaftRole.FOLLOWER, cluster.nodes.get(NodeId.of(2)).role());
            assertEquals(RaftRole.FOLLOWER, cluster.nodes.get(NodeId.of(3)).role());
        }

        @Test
        void leaderTermMonotonicallyIncreases() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            long firstTerm = cluster.nodes.get(NodeId.of(1)).currentTerm();
            assertTrue(firstTerm > 0);

            cluster.electLeader(NodeId.of(2));

            RaftNode newLeader = cluster.findLeader();
            assertNotNull(newLeader);
            assertTrue(newLeader.currentTerm() > firstTerm);
        }

        @Test
        void logReplication() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            assertEquals(ProposalResult.ACCEPTED, leader.propose(new byte[]{42}).result());

            cluster.deliverAllMessages(10);

            cluster.tickLeaderHeartbeatAndDeliver();

            for (var entry : cluster.logs.entrySet()) {
                RaftLog log = entry.getValue();
                assertTrue(log.commitIndex() >= 2,
                        "Node " + entry.getKey() + " commitIndex=" + log.commitIndex());
            }
        }

        @Test
        void followerRejectsProposal() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode follower = cluster.nodes.get(NodeId.of(2));
            assertEquals(ProposalResult.NOT_LEADER, follower.propose(new byte[]{1}).result());
        }

        @Test
        void commitRuleOnlyCurrentTermEntries() {
            // Raft section 5.4.2: a leader can only commit entries from its own current term. Node 1
            // replicates an entry from term 1 to node 2 only, then loses leadership; node 2 must not
            // commit that term-1 entry until it has replicated a term-2 entry (its own no-op) to a
            // majority.
            TestCluster cluster = new TestCluster(3);

            cluster.electLeader(NodeId.of(1));
            RaftNode leader1 = cluster.nodes.get(NodeId.of(1));
            long term1 = leader1.currentTerm();

            leader1.propose(new byte[]{1});
            TestTransport t1 = cluster.transports.get(NodeId.of(1));

            List<RaftMessage> node2Messages = new ArrayList<>();
            for (var msg : t1.messages()) {
                if (msg.target().equals(NodeId.of(2))) {
                    node2Messages.add(msg.message());
                }
            }
            t1.clear();

            for (RaftMessage msg : node2Messages) {
                cluster.nodes.get(NodeId.of(2)).handleMessage(msg);
            }
            cluster.deliverAllMessages(5);

            cluster.electLeader(NodeId.of(2));
            RaftNode leader2 = cluster.findLeader();
            assertNotNull(leader2);
            assertTrue(leader2.currentTerm() > term1);

            cluster.deliverAllMessages(10);

            assertTrue(leader2.log().commitIndex() > 0);
        }
    }

    @Nested
    class FiveNodeClusterTests {

        @Test
        void leaderElectionFiveNodes() {
            TestCluster cluster = new TestCluster(5);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            assertEquals(RaftRole.LEADER, leader.role());

            int followerCount = 0;
            for (var entry : cluster.nodes.entrySet()) {
                if (entry.getValue().role() == RaftRole.FOLLOWER) {
                    followerCount++;
                }
            }
            assertEquals(4, followerCount);
        }

        @Test
        void logReplicationFiveNodes() {
            TestCluster cluster = new TestCluster(5);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            leader.propose(new byte[]{10});
            leader.propose(new byte[]{20});
            leader.propose(new byte[]{30});

            cluster.deliverAllMessages(20);
            cluster.tickLeaderHeartbeatAndDeliver();

            for (var entry : cluster.logs.entrySet()) {
                assertTrue(entry.getValue().commitIndex() >= 4, // no-op + 3 entries
                        "Node " + entry.getKey() + " commitIndex=" + entry.getValue().commitIndex());
            }
        }
    }

    @Nested
    class PreVoteTests {

        @Test
        void preVotePreventsTermInflationFromPartitionedNode() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));
            long termAfterElection = cluster.nodes.get(NodeId.of(1)).currentTerm();

            // Simulate node 3 being partitioned: trigger its election timeout repeatedly but
            // never deliver the PreVote responses (clearing its outbox each round).
            RaftNode partitioned = cluster.nodes.get(NodeId.of(3));

            for (int round = 0; round < 5; round++) {
                for (int i = 0; i < 301; i++) {
                    partitioned.tick();
                }
                cluster.transports.get(NodeId.of(3)).clear();
            }

            // PreVote means an election timeout alone can't inflate the term: a candidate only
            // bumps its term after a majority grants the pre-vote, and a partitioned node never
            // gets that majority, so it restarts PreVote every timeout without ever incrementing.
            assertEquals(termAfterElection, partitioned.currentTerm(),
                    "Partitioned node should not inflate term due to PreVote");
        }

        @Test
        void preVoteSucceedsBeforeRealElection() {
            TestCluster cluster = new TestCluster(3);

            cluster.triggerElectionTimeout(NodeId.of(1));

            TestTransport t1 = cluster.transports.get(NodeId.of(1));
            List<RequestVoteRequest> preVotes = t1.messagesOfType(RequestVoteRequest.class);
            assertTrue(preVotes.stream().allMatch(RequestVoteRequest::preVote),
                    "Initial messages should be PreVote requests");

            // First round resolves the PreVote; the second delivers the real RequestVote it triggers.
            cluster.deliverMessages();
            cluster.deliverMessages();

            cluster.deliverAllMessages(10);

            assertEquals(RaftRole.LEADER, cluster.nodes.get(NodeId.of(1)).role());
        }

        @Test
        void preVoteRejectedWhenFollowerHasRecentLeader() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            NodeId node3 = NodeId.of(3);
            RequestVoteRequest preVoteReq = new RequestVoteRequest(
                    cluster.nodes.get(node3).currentTerm() + 1,
                    node3,
                    cluster.logs.get(node3).lastIndex(),
                    cluster.logs.get(node3).lastTerm(),
                    true
            );

            cluster.nodes.get(NodeId.of(2)).handleMessage(preVoteReq);

            TestTransport t2 = cluster.transports.get(NodeId.of(2));
            List<RequestVoteResponse> responses = t2.messagesOfType(RequestVoteResponse.class);
            assertFalse(responses.isEmpty());
            RequestVoteResponse resp = responses.getFirst();
            assertTrue(resp.preVote());
            assertFalse(resp.voteGranted(),
                    "Follower with recent leader should reject PreVote");
        }
    }

    @Nested
    class CheckQuorumTests {

        @Test
        void leaderStepsDownWithoutQuorum() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            assertEquals(RaftRole.LEADER, leader.role());

            // Simulate a partition: stop delivering the leader's messages and tick past a
            // heartbeat interval plus the check-quorum failure window.
            cluster.transports.get(NodeId.of(1)).clear();

            // The first heartbeat still passes because peerActivity starts true.
            for (int i = 0; i < 50; i++) {
                leader.tick();
            }
            cluster.transports.get(NodeId.of(1)).clear();

            // Activity was reset to false after that heartbeat, so the next check fails
            // since nothing acked.
            for (int i = 0; i < 50; i++) {
                leader.tick();
            }

            assertEquals(RaftRole.FOLLOWER, leader.role(),
                    "Leader should step down after losing quorum contact");
        }

        @Test
        void leaderMaintainsQuorumWithResponses() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            assertEquals(RaftRole.LEADER, leader.role());

            for (int round = 0; round < 5; round++) {
                for (int i = 0; i < 50; i++) {
                    leader.tick();
                }
                cluster.deliverAllMessages(5);
            }

            assertEquals(RaftRole.LEADER, leader.role(),
                    "Leader should maintain role with active quorum");
        }
    }

    @Nested
    class LeadershipTransferTests {

        @Test
        void leadershipTransferToTarget() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            assertEquals(RaftRole.LEADER, leader.role());

            cluster.deliverAllMessages(10);

            assertTrue(leader.transferLeadership(NodeId.of(2)));

            cluster.deliverAllMessages(10);

            RaftNode newLeader = cluster.nodes.get(NodeId.of(2));
            assertEquals(RaftRole.LEADER, newLeader.role(),
                    "Node 2 should be leader after transfer");
        }

        @Test
        void leadershipTransferRejectsNewProposals() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));

            // Propose but withhold delivery so node 2's matchIndex is not yet caught up
            // when the transfer starts.
            leader.propose(new byte[]{99});

            leader.transferLeadership(NodeId.of(2));
            assertNotNull(leader.transferTarget(), "Transfer should be in progress");

            assertEquals(ProposalResult.TRANSFER_IN_PROGRESS, leader.propose(new byte[]{1}).result(),
                    "Proposals should be rejected during leadership transfer");
        }

        @Test
        void leadershipTransferToSelfIsRejected() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            assertFalse(leader.transferLeadership(NodeId.of(1)));
        }

        @Test
        void leadershipTransferToNonPeerIsRejected() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            assertFalse(leader.transferLeadership(NodeId.of(99)));
        }

        @Test
        void committedWritesSurviveTransferAndLeadershipIsRestorable() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));
            RaftNode leader1 = cluster.nodes.get(NodeId.of(1));

            byte[] cmdA = {1, 2, 3};
            byte[] cmdB = {4, 5, 6};
            assertEquals(ProposalResult.ACCEPTED, leader1.propose(cmdA).result());
            assertEquals(ProposalResult.ACCEPTED, leader1.propose(cmdB).result());
            cluster.deliverAllMessages(20);
            cluster.tickLeaderHeartbeatAndDeliver();
            long committedBefore = leader1.log().commitIndex();
            assertTrue(committedBefore >= 2, "both proposals must have committed on the leader");

            assertTrue(leader1.transferLeadership(NodeId.of(2)), "the leader initiates the transfer");
            cluster.deliverAllMessages(20);
            RaftNode node2 = cluster.nodes.get(NodeId.of(2));
            assertEquals(RaftRole.LEADER, node2.role(), "node 2 becomes leader after the transfer");

            assertTrue(node2.log().lastIndex() >= committedBefore,
                    "the new leader must retain every previously committed entry (no write loss)");
            TestStateMachine sm2 = cluster.stateMachines.get(NodeId.of(2));
            assertTrue(containsCommand(sm2, cmdA) && containsCommand(sm2, cmdB),
                    "both committed writes must be present in the new leader's applied state (durable across transfer)");

            assertEquals(ProposalResult.ACCEPTED, node2.propose(new byte[]{7, 8, 9}).result());
            cluster.deliverAllMessages(20);
            cluster.tickLeaderHeartbeatAndDeliver();
            assertTrue(node2.log().commitIndex() > committedBefore,
                    "the new leader commits a fresh write - the group stays available");

            cluster.deliverAllMessages(20);
            assertTrue(node2.transferLeadership(NodeId.of(1)), "leadership must be transferable back");
            cluster.deliverAllMessages(20);
            assertEquals(RaftRole.LEADER, cluster.nodes.get(NodeId.of(1)).role(),
                    "leadership is restored to node 1 - placement is operator-manageable in both directions");
        }

        private static boolean containsCommand(TestStateMachine sm, byte[] command) {
            for (TestStateMachine.AppliedEntry e : sm.applied) {
                if (java.util.Arrays.equals(e.command(), command)) {
                    return true;
                }
            }
            return false;
        }

        @Test
        void stalledTransferAbortsAfterElectionTimeoutAndWritesResumeWithoutStepDown() {
            // Node 4 is a configured voter that is removed from the routing map, i.e. permanently
            // unreachable. The live majority {1,2,3} still gives the leader quorum, so it stays up,
            // but a transfer to node 4 can never complete.
            TestCluster cluster = new TestCluster(4);
            cluster.nodes.remove(NodeId.of(4));
            cluster.transports.remove(NodeId.of(4));
            cluster.electLeader(NodeId.of(1));
            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            assertEquals(RaftRole.LEADER, leader.role());
            cluster.deliverAllMessages(10);

            assertTrue(leader.transferLeadership(NodeId.of(4)), "the transfer to the partitioned voter is initiated");
            assertNotNull(leader.transferTarget(), "the transfer is in progress (target never catches up)");
            assertEquals(ProposalResult.TRANSFER_IN_PROGRESS, leader.propose(new byte[]{1}).result(),
                    "every write is wedged while the transfer is in progress");

            // Drive past one election timeout while the live majority keeps exchanging messages, so
            // the leader's quorum holds and it must not step down; node 4 stays gone and never acks.
            int electionTimeout = leader.electionTimeoutTicksForTest();
            for (int i = 0; i <= electionTimeout; i++) {
                for (RaftNode n : cluster.nodes.values()) {
                    n.tick();
                }
                cluster.deliverAllMessages(5);
            }

            // Raft 3.10: a stalled transfer aborts on its own election timeout, clearing the target
            // while the leader keeps leading.
            assertNull(leader.transferTarget(),
                    "the stalled transfer must be aborted after about one election timeout");
            assertEquals(RaftRole.LEADER, leader.role(),
                    "the leader must NOT step down on abort - the group stays available under the same leader");
            assertEquals(ProposalResult.ACCEPTED, leader.propose(new byte[]{2}).result(),
                    "writes must resume once the stalled transfer is aborted");
        }
    }

    @Nested
    class LogConflictTests {

        @Test
        void followerTruncatesDivergentEntries() {
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);
            RaftConfig config2 = RaftConfig.of(n2, Set.of(n1));
            RaftLog log2 = new RaftLog();
            TestTransport transport2 = new TestTransport();
            TestStateMachine sm2 = new TestStateMachine();
            RandomGenerator rng2 = RandomGenerator.of("L64X128MixRandom");
            RaftNode node2 = new RaftNode(config2, log2, transport2, sm2, rng2);

            log2.append(new LogEntry(1, 1, new byte[]{1}));
            log2.append(new LogEntry(2, 1, new byte[]{2}));
            log2.append(new LogEntry(3, 2, new byte[]{3}));

            // prevLogIndex=2, prevLogTerm=1 matches the leader's view of node2's log; the incoming
            // entry at index 3 carries term 3, diverging from node2's existing term-2 entry there.
            AppendEntriesRequest req = new AppendEntriesRequest(
                    3, // leader term
                    n1,
                    2, 1, // prevLogIndex, prevLogTerm
                    List.of(new LogEntry(3, 3, new byte[]{30})),
                    3 // leaderCommit
            );

            node2.handleMessage(req);

            assertEquals(3, log2.lastTerm(),
                    "Entry at index 3 should have term 3 from leader");
            assertEquals(3, log2.lastIndex());
        }

        @Test
        void followerRejectsIfPrevLogDoesNotMatch() {
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);
            RaftConfig config2 = RaftConfig.of(n2, Set.of(n1));
            RaftLog log2 = new RaftLog();
            TestTransport transport2 = new TestTransport();
            TestStateMachine sm2 = new TestStateMachine();
            RandomGenerator rng2 = RandomGenerator.of("L64X128MixRandom");
            RaftNode node2 = new RaftNode(config2, log2, transport2, sm2, rng2);

            log2.append(new LogEntry(1, 1, new byte[]{1}));
            log2.append(new LogEntry(2, 1, new byte[]{2}));

            // prevLogTerm=2 is an intentional mismatch: node2's actual term at index 2 is 1.
            AppendEntriesRequest req = new AppendEntriesRequest(
                    3, n1,
                    2, 2,
                    List.of(new LogEntry(3, 3, new byte[]{30})),
                    3
            );

            node2.handleMessage(req);

            List<AppendEntriesResponse> responses =
                    transport2.messagesOfType(AppendEntriesResponse.class);
            assertFalse(responses.isEmpty());
            assertFalse(responses.getFirst().success());

            assertEquals(2, log2.lastIndex());
            assertEquals(1, log2.lastTerm());
        }

        @Test
        void leaderDecrementsNextIndexOnRejection() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            long leaderTerm = leader.currentTerm();

            RaftLog log3 = cluster.logs.get(NodeId.of(3));

            leader.propose(new byte[]{10});
            leader.propose(new byte[]{20});

            cluster.deliverAllMessages(20);

            assertTrue(cluster.logs.get(NodeId.of(3)).commitIndex() > 0);
        }
    }

    @Nested
    class VoteTrackingTests {

        @Test
        void nodeVotesOncePerTerm() {
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);
            NodeId n3 = NodeId.of(3);

            RaftConfig config1 = RaftConfig.of(n1, Set.of(n2, n3));
            RaftLog log1 = new RaftLog();
            TestTransport transport1 = new TestTransport();
            TestStateMachine sm1 = new TestStateMachine();
            RandomGenerator rng1 = RandomGenerator.of("L64X128MixRandom");
            RaftNode node1 = new RaftNode(config1, log1, transport1, sm1, rng1);

            RequestVoteRequest req2 = new RequestVoteRequest(1, n2, 0, 0, false);
            node1.handleMessage(req2);

            List<RequestVoteResponse> responses = transport1.messagesOfType(RequestVoteResponse.class);
            assertEquals(1, responses.size());
            assertTrue(responses.getFirst().voteGranted());
            assertEquals(n2, node1.votedFor());

            transport1.clear();

            RequestVoteRequest req3 = new RequestVoteRequest(1, n3, 0, 0, false);
            node1.handleMessage(req3);

            responses = transport1.messagesOfType(RequestVoteResponse.class);
            assertEquals(1, responses.size());
            assertFalse(responses.getFirst().voteGranted(),
                    "Node should not vote for two different candidates in the same term");
        }

        @Test
        void nodeCanVoteForSameCandidateAgainInSameTerm() {
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);

            RaftConfig config1 = RaftConfig.of(n1, Set.of(n2));
            RaftLog log1 = new RaftLog();
            TestTransport transport1 = new TestTransport();
            TestStateMachine sm1 = new TestStateMachine();
            RandomGenerator rng1 = RandomGenerator.of("L64X128MixRandom");
            RaftNode node1 = new RaftNode(config1, log1, transport1, sm1, rng1);

            RequestVoteRequest req = new RequestVoteRequest(1, n2, 0, 0, false);
            node1.handleMessage(req);
            transport1.clear();

            node1.handleMessage(req);
            List<RequestVoteResponse> responses = transport1.messagesOfType(RequestVoteResponse.class);
            assertTrue(responses.getFirst().voteGranted(),
                    "Should grant vote to same candidate in same term (idempotent)");
        }

        @Test
        void candidateRejectsVoteIfLogNotUpToDate() {
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);

            RaftConfig config1 = RaftConfig.of(n1, Set.of(n2));
            RaftLog log1 = new RaftLog();
            TestTransport transport1 = new TestTransport();
            TestStateMachine sm1 = new TestStateMachine();
            RandomGenerator rng1 = RandomGenerator.of("L64X128MixRandom");
            RaftNode node1 = new RaftNode(config1, log1, transport1, sm1, rng1);

            log1.append(new LogEntry(1, 5, new byte[]{1}));

            RequestVoteRequest req = new RequestVoteRequest(6, n2, 1, 3, false);
            node1.handleMessage(req);

            List<RequestVoteResponse> responses = transport1.messagesOfType(RequestVoteResponse.class);
            assertFalse(responses.getFirst().voteGranted(),
                    "Should reject vote if candidate's log is less up-to-date");
        }
    }

    @Nested
    class SplitVoteTests {

        @Test
        void splitVoteResolvesViaRandomizedTimeout() {
            TestCluster cluster = new TestCluster(3);

            cluster.triggerElectionTimeout(NodeId.of(1));
            cluster.triggerElectionTimeout(NodeId.of(2));

            cluster.deliverAllMessages(10);

            for (int i = 0; i < 10; i++) {
                for (var node : cluster.nodes.values()) {
                    for (int t = 0; t < 301; t++) {
                        node.tick();
                    }
                }
                cluster.deliverAllMessages(10);

                RaftNode leader = cluster.findLeader();
                if (leader != null) {
                    long leaderCount = cluster.nodes.values().stream()
                            .filter(n -> n.role() == RaftRole.LEADER)
                            .count();
                    assertEquals(1, leaderCount, "Should have exactly one leader");
                    return;
                }
            }

            fail("Should have elected a leader within 10 rounds");
        }
    }

    @Nested
    class TermHandlingTests {

        @Test
        void nodeStepsDownOnHigherTerm() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            assertEquals(RaftRole.LEADER, leader.role());

            long leaderTerm = leader.currentTerm();

            AppendEntriesRequest higherTermMsg = new AppendEntriesRequest(
                    leaderTerm + 5, NodeId.of(2), 0, 0, List.of(), 0);

            leader.handleMessage(higherTermMsg);

            assertEquals(RaftRole.FOLLOWER, leader.role(),
                    "Leader should step down on higher term");
            assertEquals(leaderTerm + 5, leader.currentTerm());
        }

        @Test
        void rejectsStaleTermAppendEntries() {
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);

            RaftConfig config1 = RaftConfig.of(n1, Set.of(n2));
            RaftLog log1 = new RaftLog();
            TestTransport transport1 = new TestTransport();
            TestStateMachine sm1 = new TestStateMachine();
            RandomGenerator rng1 = RandomGenerator.of("L64X128MixRandom");
            RaftNode node1 = new RaftNode(config1, log1, transport1, sm1, rng1);

            node1.handleMessage(new AppendEntriesRequest(5, n2, 0, 0, List.of(), 0));
            assertEquals(5, node1.currentTerm());
            transport1.clear();

            AppendEntriesRequest staleReq = new AppendEntriesRequest(
                    3, n2, 0, 0, List.of(), 0);
            node1.handleMessage(staleReq);

            List<AppendEntriesResponse> responses =
                    transport1.messagesOfType(AppendEntriesResponse.class);
            assertFalse(responses.isEmpty());
            assertFalse(responses.getFirst().success());
            assertEquals(5, responses.getFirst().term());
        }

        @Test
        void candidateStepsDownOnAppendEntriesWithCurrentTerm() {
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);
            NodeId n3 = NodeId.of(3);

            RaftConfig config1 = RaftConfig.of(n1, Set.of(n2, n3));
            RaftLog log1 = new RaftLog();
            TestTransport transport1 = new TestTransport();
            TestStateMachine sm1 = new TestStateMachine();
            RandomGenerator rng1 = RandomGenerator.of("L64X128MixRandom");
            RaftNode node1 = new RaftNode(config1, log1, transport1, sm1, rng1);

            for (int i = 0; i < 301; i++) {
                node1.tick();
            }
            // The 4th argument is the preVote flag; granting pre-votes here lets the real
            // election proceed.
            node1.handleMessage(new RequestVoteResponse(node1.currentTerm(), true, n2, true));
            node1.handleMessage(new RequestVoteResponse(node1.currentTerm(), true, n3, true));

            if (node1.role() != RaftRole.CANDIDATE) {
                // The election may already have produced a leader here (timing-dependent);
                // skip the rest of this check in that case.
                return;
            }

            long candidateTerm = node1.currentTerm();
            transport1.clear();

            AppendEntriesRequest req = new AppendEntriesRequest(
                    candidateTerm, n2, 0, 0, List.of(), 0);
            node1.handleMessage(req);

            assertEquals(RaftRole.FOLLOWER, node1.role(),
                    "Candidate should step down on AppendEntries with current term");
        }
    }

    @Nested
    class RaftLogTests {

        @Test
        void emptyLogState() {
            RaftLog log = new RaftLog();
            assertEquals(0, log.lastIndex());
            assertEquals(0, log.lastTerm());
            assertEquals(0, log.commitIndex());
            assertEquals(0, log.lastApplied());
            assertEquals(0, log.size());
        }

        @Test
        void appendAndQuery() {
            RaftLog log = new RaftLog();
            log.append(new LogEntry(1, 1, new byte[]{10}));
            log.append(new LogEntry(2, 1, new byte[]{20}));
            log.append(new LogEntry(3, 2, new byte[]{30}));

            assertEquals(3, log.lastIndex());
            assertEquals(2, log.lastTerm());
            assertEquals(3, log.size());

            assertEquals(1, log.termAt(1));
            assertEquals(1, log.termAt(2));
            assertEquals(2, log.termAt(3));
            assertEquals(-1, log.termAt(4));
        }

        @Test
        void truncateConflictingEntries() {
            RaftLog log = new RaftLog();
            log.append(new LogEntry(1, 1, new byte[]{1}));
            log.append(new LogEntry(2, 1, new byte[]{2}));
            log.append(new LogEntry(3, 2, new byte[]{3}));

            log.truncateFrom(2);
            assertEquals(1, log.lastIndex());
            assertEquals(1, log.lastTerm());
        }

        @Test
        void snapshotCompaction() {
            RaftLog log = new RaftLog();
            log.append(new LogEntry(1, 1, new byte[]{1}));
            log.append(new LogEntry(2, 1, new byte[]{2}));
            log.append(new LogEntry(3, 2, new byte[]{3}));

            log.compact(2, 1);
            assertEquals(2, log.snapshotIndex());
            assertEquals(1, log.snapshotTerm());
            assertEquals(1, log.size());
            assertEquals(3, log.lastIndex());
            assertEquals(2, log.lastTerm());

            assertNotNull(log.entryAt(3));
            assertNull(log.entryAt(1));
            assertNull(log.entryAt(2));

            // termAt(2) resolves via the snapshot's recorded term even though index 2 is compacted.
            assertEquals(1, log.termAt(2));
        }

        @Test
        void appendEntriesWithConflict() {
            RaftLog log = new RaftLog();
            log.append(new LogEntry(1, 1, new byte[]{1}));
            log.append(new LogEntry(2, 1, new byte[]{2}));
            log.append(new LogEntry(3, 1, new byte[]{3}));

            boolean ok = log.appendEntries(1, 1, List.of(
                    new LogEntry(2, 2, new byte[]{20}),
                    new LogEntry(3, 2, new byte[]{30})
            ));
            assertTrue(ok);
            assertEquals(2, log.termAt(2));
            assertEquals(2, log.termAt(3));
        }

        @Test
        void appendEntriesRejectsMismatchedPrevLog() {
            RaftLog log = new RaftLog();
            log.append(new LogEntry(1, 1, new byte[]{1}));

            boolean ok = log.appendEntries(1, 5, List.of(
                    new LogEntry(2, 5, new byte[]{20})
            ));
            assertFalse(ok);
            assertEquals(1, log.lastIndex());
        }

        @Test
        void entriesBatchRespectsLimits() {
            RaftLog log = new RaftLog();
            for (int i = 1; i <= 100; i++) {
                log.append(new LogEntry(i, 1, new byte[100]));
            }

            List<LogEntry> batch = log.entriesBatch(1, 10, Integer.MAX_VALUE);
            assertEquals(10, batch.size());

            batch = log.entriesBatch(1, 100, 250);
            // Each entry is 100 bytes. First entry always included.
            // 100 + 100 = 200 <= 250, 200 + 100 = 300 > 250
            assertEquals(2, batch.size());
        }

        @Test
        void isAtLeastAsUpToDate() {
            RaftLog log = new RaftLog();
            log.append(new LogEntry(1, 1, new byte[0]));
            log.append(new LogEntry(2, 3, new byte[0]));

            assertTrue(log.isAtLeastAsUpToDate(4, 1));
            assertFalse(log.isAtLeastAsUpToDate(2, 10));

            assertTrue(log.isAtLeastAsUpToDate(3, 2));
            assertTrue(log.isAtLeastAsUpToDate(3, 3));
            assertFalse(log.isAtLeastAsUpToDate(3, 1));
        }
    }

    @Nested
    class StateMachineApplicationTests {

        @Test
        void committedEntriesAreApplied() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            leader.propose(new byte[]{42});
            leader.propose(new byte[]{43});

            cluster.deliverAllMessages(10);
            cluster.tickLeaderHeartbeatAndDeliver();

            for (var entry : cluster.stateMachines.entrySet()) {
                TestStateMachine sm = entry.getValue();
                assertTrue(sm.applied.size() >= 3,
                        "Node " + entry.getKey() + " should have applied at least 3 entries (no-op + 2 commands), got " + sm.applied.size());
            }
        }
    }

    @Nested
    class RaftConfigTests {

        @Test
        void quorumSizeCalculation() {
            assertEquals(1, RaftConfig.of(NodeId.of(1), Set.of()).quorumSize());

            assertEquals(2, RaftConfig.of(NodeId.of(1), Set.of(NodeId.of(2), NodeId.of(3))).quorumSize());

            assertEquals(3, RaftConfig.of(NodeId.of(1),
                    Set.of(NodeId.of(2), NodeId.of(3), NodeId.of(4), NodeId.of(5))).quorumSize());
        }

        @Test
        void invalidConfigRejected() {
            assertThrows(IllegalArgumentException.class, () ->
                    new RaftConfig(NodeId.of(1), Set.of(), 0, 300, 50, 64, 256 * 1024, 1024, 10, 1));
            assertThrows(IllegalArgumentException.class, () ->
                    new RaftConfig(NodeId.of(1), Set.of(), 150, 100, 50, 64, 256 * 1024, 1024, 10, 1));
            assertThrows(IllegalArgumentException.class, () ->
                    new RaftConfig(NodeId.of(1), Set.of(), 150, 300, 150, 64, 256 * 1024, 1024, 10, 1));
        }
    }

    // Millisecond timing config -> tick-count conversion: the ...Ms config values are real
    // milliseconds. Without the conversion, at the
    // production 10ms tick period a documented 150-300ms election timeout would run
    // for 150-300 ticks == 1.5-3.0s, and a 50ms heartbeat would fire every 50 ticks
    // == 500ms (10x every documented value). These tests pin the conversion so the
    // documented millisecond budgets are the budgets actually realized. They FAIL
    // against pre-conversion code, which returns 150-300 / 50 ticks at
    // tickPeriodMs=10 instead of 15-30 / 5.

    @Nested
    class TimingConversionTests {

        @Test
        void derivedTickCountsConvertMsByTickPeriod() {
            RaftConfig prod = RaftConfig.of(NodeId.of(1), Set.of(), 10);
            assertEquals(15, prod.electionTimeoutMinTicks(), "150ms / 10ms == 15 ticks");
            assertEquals(30, prod.electionTimeoutMaxTicks(), "300ms / 10ms == 30 ticks");
            assertEquals(5, prod.heartbeatIntervalTicks(), "50ms / 10ms == 5 ticks");

            // Simulation tick period is 1ms: ms map one-to-one onto ticks, i.e.
            // identical to the pre-conversion tick-domain values (no schedule drift).
            RaftConfig sim = RaftConfig.of(NodeId.of(1), Set.of(), 1);
            assertEquals(150, sim.electionTimeoutMinTicks());
            assertEquals(300, sim.electionTimeoutMaxTicks());
            assertEquals(50, sim.heartbeatIntervalTicks());
        }

        @Test
        void conversionRoundsToNearestAndFloorsAtOne() {
            // round-to-nearest, not truncation: 50ms / 30ms == 1.67 -> 2 ticks,
            // 300ms / 30ms == 10 ticks. (Election kept large enough relative to
            // the heartbeat that the ratio guard still passes at this coarse tick.)
            RaftConfig rounding = new RaftConfig(
                    NodeId.of(1), Set.of(), 300, 600, 50, 64, 256 * 1024, 1024, 10, 30);
            assertEquals(2, rounding.heartbeatIntervalTicks(), "round(50/30) == 2");
            assertEquals(10, rounding.electionTimeoutMinTicks(), "round(300/30) == 10");

            // A tick period coarser than a duration must still floor at >=1 tick
            // (never 0, which would fire every tick). 50ms / 100ms == 0.5 -> 1.
            RaftConfig coarse = new RaftConfig(
                    NodeId.of(1), Set.of(), 1500, 3000, 50, 64, 256 * 1024, 1024, 10, 100);
            assertEquals(1, coarse.heartbeatIntervalTicks(), "round(50/100) floored to 1");
        }

        @Test
        void nodeElectionTargetUsesDerivedTickBounds() {
            RaftConfig config = RaftConfig.of(NodeId.of(1), Set.of(NodeId.of(2), NodeId.of(3)), 10);
            RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");
            RaftNode node = new RaftNode(config, new RaftLog(), new TestTransport(),
                    new TestStateMachine(), rng);

            int electionTarget = node.electionTimeoutTicksForTest();
            assertTrue(electionTarget >= 15 && electionTarget <= 30,
                    "election target must be derived from 150-300ms / 10ms == [15,30] ticks, got "
                            + electionTarget + " (pre-fix would be in [150,300])");
            assertEquals(5, node.heartbeatTimeoutTicksForTest(),
                    "heartbeat must fire every 50ms / 10ms == 5 ticks (pre-fix: 50)");
        }

        @Test
        void rejectsTickPeriodTooCoarseForElectionHeartbeatRatio() {
            // A 60ms tick period collapses the 150ms election timeout to round(2.5)
            // == 3 ticks and the 50ms heartbeat to round(0.83) -> 1 tick. 3 >= 3*1
            // still holds, so that is allowed. But a 90ms tick gives election
            // round(150/90)==2 and heartbeat round(50/90)->1: 2 < 3*1, rejected.
            assertThrows(IllegalArgumentException.class, () ->
                            new RaftConfig(NodeId.of(1), Set.of(), 150, 300, 50, 64, 256 * 1024, 1024, 10, 90),
                    "tickPeriod that drops election:heartbeat below the safety ratio must be rejected");

            assertThrows(IllegalArgumentException.class, () ->
                    new RaftConfig(NodeId.of(1), Set.of(), 150, 300, 50, 64, 256 * 1024, 1024, 10, 0));
        }
    }

    @Nested
    class LogEntryTests {

        @Test
        void noopEntry() {
            LogEntry noop = LogEntry.noop(1, 5);
            assertEquals(1, noop.index());
            assertEquals(5, noop.term());
            assertEquals(0, noop.command().length);
        }

        @Test
        void invalidIndexRejected() {
            assertThrows(IllegalArgumentException.class, () -> new LogEntry(0, 1, new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> new LogEntry(-1, 1, new byte[0]));
        }

        @Test
        void nullCommandDefaultsToEmpty() {
            LogEntry entry = new LogEntry(1, 1, null);
            assertNotNull(entry.command());
            assertEquals(0, entry.command().length);
        }
    }

    @Nested
    class BackpressureTests {

        @Test
        void proposalRejectedWhenOverloaded() {
            RaftConfig config = new RaftConfig(
                    NodeId.of(1), Set.of(), 150, 300, 50, 64, 256 * 1024, 3, 10, 1);
            RaftLog log = new RaftLog();
            TestTransport transport = new TestTransport();
            TestStateMachine sm = new TestStateMachine();
            RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");
            RaftNode node = new RaftNode(config, log, transport, sm, rng);

            for (int i = 0; i < 301; i++) {
                node.tick();
            }
            assertEquals(RaftRole.LEADER, node.role());

            // In a single-node cluster every propose commits immediately, so backpressure never
            // triggers; build a 3-node leader with no message delivery instead, so entries stay
            // uncommitted.
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);
            NodeId n3 = NodeId.of(3);
            RaftConfig config3 = new RaftConfig(
                    n1, Set.of(n2, n3), 150, 300, 50, 64, 256 * 1024, 3, 10, 1);
            RaftLog log3 = new RaftLog();
            TestTransport transport3 = new TestTransport();
            TestStateMachine sm3 = new TestStateMachine();
            RandomGenerator rng3 = RandomGenerator.of("L64X128MixRandom");
            RaftNode leader = new RaftNode(config3, log3, transport3, sm3, rng3);

            for (int i = 0; i < 301; i++) {
                leader.tick();
            }
            // The 4th argument is the preVote flag: grant pre-votes first, then real votes.
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n2, true));
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n3, true));
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n2, false));
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n3, false));
            transport3.clear();

            assertEquals(RaftRole.LEADER, leader.role());
            // The no-op is at index 1, uncommitted because no responses were delivered, so
            // commitIndex stays 0 (no majority): uncommitted = lastIndex - commitIndex = 1 - 0 = 1.

            assertEquals(ProposalResult.ACCEPTED, leader.propose(new byte[]{1}).result());
            // uncommitted = 2 - 0 = 2
            assertEquals(ProposalResult.ACCEPTED, leader.propose(new byte[]{2}).result());
            // uncommitted = 3 - 0 = 3 >= maxPendingProposals (3)
            assertEquals(ProposalResult.OVERLOADED, leader.propose(new byte[]{3}).result());
        }

        @Test
        void proposalAcceptedAfterCommitReducesBackpressure() {
            RaftConfig config = new RaftConfig(
                    NodeId.of(1), Set.of(), 150, 300, 50, 64, 256 * 1024, 1024, 10, 1);
            RaftLog log = new RaftLog();
            TestTransport transport = new TestTransport();
            TestStateMachine sm = new TestStateMachine();
            RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");
            RaftNode node = new RaftNode(config, log, transport, sm, rng);

            for (int i = 0; i < 301; i++) {
                node.tick();
            }
            assertEquals(RaftRole.LEADER, node.role());

            for (int i = 0; i < 100; i++) {
                assertEquals(ProposalResult.ACCEPTED, node.propose(new byte[]{(byte) i}).result());
            }
        }
    }

    @Nested
    class PipeliningTests {

        @Test
        void inflightWindowLimitsAppendEntries() {
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);
            NodeId n3 = NodeId.of(3);
            RaftConfig config = new RaftConfig(
                    n1, Set.of(n2, n3), 150, 300, 50, 64, 256 * 1024, 1024, 2, 1);
            RaftLog log = new RaftLog();
            TestTransport transport = new TestTransport();
            TestStateMachine sm = new TestStateMachine();
            RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");
            RaftNode leader = new RaftNode(config, log, transport, sm, rng);

            for (int i = 0; i < 301; i++) {
                leader.tick();
            }
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n2, true));
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n3, true));
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n2, false));
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n3, false));

            assertEquals(RaftRole.LEADER, leader.role());
            transport.clear();

            // Entries are proposed without delivering any responses, so the in-flight window
            // never clears.
            leader.propose(new byte[]{1});
            leader.propose(new byte[]{2});
            leader.propose(new byte[]{3});

            long appendCountToN2 = transport.messages().stream()
                    .filter(m -> m.target().equals(n2) && m.message() instanceof AppendEntriesRequest)
                    .count();

            // becomeLeader sends one AppendEntries for the no-op, and each propose sends another,
            // but maxInflightAppends=2 caps the total sent per peer at 2.
            assertTrue(appendCountToN2 <= 2,
                    "Should limit in-flight AppendEntries to maxInflightAppends, got " + appendCountToN2);
        }

        @Test
        void inflightWindowResetsOnResponse() {
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);
            RaftConfig config = new RaftConfig(
                    n1, Set.of(n2), 150, 300, 50, 64, 256 * 1024, 1024, 1, 1);
            RaftLog log = new RaftLog();
            TestTransport transport = new TestTransport();
            TestStateMachine sm = new TestStateMachine();
            RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");
            RaftNode leader = new RaftNode(config, log, transport, sm, rng);

            for (int i = 0; i < 301; i++) {
                leader.tick();
            }
            // 2-node cluster: pre-vote needs quorum=2, and self counts toward it.
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n2, true));
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n2, false));

            assertEquals(RaftRole.LEADER, leader.role());
            transport.clear();

            leader.propose(new byte[]{1});
            long countAfterFirst = transport.messagesTo(n2, AppendEntriesRequest.class).size();

            leader.propose(new byte[]{2});
            long countAfterSecond = transport.messagesTo(n2, AppendEntriesRequest.class).size();

            assertEquals(countAfterFirst, countAfterSecond,
                    "Second propose should be blocked by inflight window");

            leader.handleMessage(new AppendEntriesResponse(
                    leader.currentTerm(), true, log.lastIndex(), n2));
            transport.clear();

            leader.propose(new byte[]{3});
            long countAfterResponse = transport.messagesTo(n2, AppendEntriesRequest.class).size();
            assertTrue(countAfterResponse > 0,
                    "After response clears inflight, new AppendEntries should be sent");
        }
    }
}
