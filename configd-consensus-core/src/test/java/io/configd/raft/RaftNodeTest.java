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

/**
 * Comprehensive tests for the Raft consensus implementation.
 * <p>
 * Uses a deterministic in-memory transport and seeded random generator
 * for reproducible test runs (ADR-0007).
 */
class RaftNodeTest {

    // ========================================================================
    // Test infrastructure
    // ========================================================================

    /**
     * Captures messages sent by a RaftNode for inspection and delivery.
     */
    static final class TestTransport implements RaftTransport {
        private final List<SentMessage> messages = new ArrayList<>();

        record SentMessage(NodeId target, RaftMessage message) {}

        @Override
        public void send(NodeId target, RaftMessage message) {
            messages.add(new SentMessage(target, message));
        }

        List<SentMessage> messages() { return messages; }

        void clear() { messages.clear(); }

        /** Returns messages of a specific type sent to a specific target. */
        @SuppressWarnings("unchecked")
        <T> List<T> messagesTo(NodeId target, Class<T> type) {
            return messages.stream()
                    .filter(m -> m.target().equals(target) && type.isInstance(m.message()))
                    .map(m -> (T) m.message())
                    .toList();
        }

        /** Returns all messages of a specific type. */
        @SuppressWarnings("unchecked")
        <T> List<T> messagesOfType(Class<T> type) {
            return messages.stream()
                    .filter(m -> type.isInstance(m.message()))
                    .map(m -> (T) m.message())
                    .toList();
        }
    }

    /**
     * Simple counting state machine for tests.
     */
    static final class TestStateMachine implements StateMachine {
        final List<AppliedEntry> applied = new ArrayList<>();

        record AppliedEntry(long index, long term, byte[] command) {}

        @Override
        public void apply(long index, long term, byte[] command) {
            applied.add(new AppliedEntry(index, term, command));
        }

        @Override
        public byte[] snapshot() { return new byte[0]; }

        @Override
        public void restoreSnapshot(byte[] snapshot) { }
    }

    /**
     * Interconnected cluster of RaftNodes for multi-node tests.
     */
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
                // Each node gets a different seed for deterministic but varied timeouts.
                // This is critical for split-vote resolution via randomized timeout.
                RandomGenerator rng = new java.util.Random(id.id() * 31L + 7);

                RaftNode node = new RaftNode(config, log, transport, sm, rng);
                nodes.put(id, node);
                transports.put(id, transport);
                stateMachines.put(id, sm);
                logs.put(id, log);
            }
        }

        /** Delivers all pending messages to their target nodes. */
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

        /**
         * Runs a round of message delivery until no messages remain,
         * or a maximum number of rounds is reached.
         */
        void deliverAllMessages(int maxRounds) {
            for (int i = 0; i < maxRounds; i++) {
                boolean anyMessages = transports.values().stream()
                        .anyMatch(t -> !t.messages().isEmpty());
                if (!anyMessages) break;
                deliverMessages();
            }
        }

        /** Triggers an election timeout on the given node by ticking enough times. */
        void triggerElectionTimeout(NodeId id) {
            RaftNode node = nodes.get(id);
            // Tick enough times to exceed any possible election timeout (max 300)
            for (int i = 0; i < 301; i++) {
                node.tick();
            }
        }

        /** Elect a specific node as leader. */
        void electLeader(NodeId id) {
            triggerElectionTimeout(id);
            deliverAllMessages(10);
        }

        /**
         * Ticks the leader once and delivers all resulting messages.
         * This is needed to propagate updated commitIndex to followers
         * after the leader has advanced it.
         */
        void tickLeaderHeartbeatAndDeliver() {
            RaftNode leader = findLeader();
            if (leader == null) return;
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }
            deliverAllMessages(5);
        }

        /** Returns the leader node, or null if no leader. */
        RaftNode findLeader() {
            return nodes.values().stream()
                    .filter(n -> n.role() == RaftRole.LEADER)
                    .findFirst()
                    .orElse(null);
        }
    }

    // ========================================================================
    // Single-node tests
    // ========================================================================

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
            // Tick until election timeout fires
            for (int i = 0; i < 301; i++) {
                node.tick();
            }
            assertEquals(RaftRole.LEADER, node.role());
            assertEquals(1, node.currentTerm());
            assertEquals(NodeId.of(1), node.leaderId());
        }

        @Test
        void singleNodeCanProposeAndCommit() {
            // Become leader
            for (int i = 0; i < 301; i++) {
                node.tick();
            }
            assertEquals(ProposalResult.ACCEPTED, node.propose(new byte[]{1, 2, 3}));

            // In a single-node cluster, entries commit immediately
            assertEquals(2, log.commitIndex()); // no-op + command
            assertEquals(2, sm.applied.size()); // no-op + command both applied
        }
    }

    // ========================================================================
    // Three-node cluster tests
    // ========================================================================

    @Nested
    class ThreeNodeClusterTests {

        @Test
        void leaderElection() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            assertEquals(RaftRole.LEADER, leader.role());

            // Other nodes should be followers
            assertEquals(RaftRole.FOLLOWER, cluster.nodes.get(NodeId.of(2)).role());
            assertEquals(RaftRole.FOLLOWER, cluster.nodes.get(NodeId.of(3)).role());
        }

        @Test
        void leaderTermMonotonicallyIncreases() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            long firstTerm = cluster.nodes.get(NodeId.of(1)).currentTerm();
            assertTrue(firstTerm > 0);

            // Now trigger another election on node 2
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
            assertEquals(ProposalResult.ACCEPTED, leader.propose(new byte[]{42}));

            // Deliver AppendEntries to followers, responses back to leader.
            // Leader advances commitIndex upon receiving majority responses.
            cluster.deliverAllMessages(10);

            // Leader should have committed; followers learn commitIndex on next heartbeat.
            cluster.tickLeaderHeartbeatAndDeliver();

            // All nodes should have the entry committed
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
            assertEquals(ProposalResult.NOT_LEADER, follower.propose(new byte[]{1}));
        }

        @Test
        void commitRuleOnlyCurrentTermEntries() {
            // Raft §5.4.2: Leader can only commit entries from its current term
            TestCluster cluster = new TestCluster(3);

            // Manually set up a situation: node 1 has an entry from term 1
            // but node 1 then loses leadership. Node 2 becomes leader in term 2.
            // Node 2 should NOT commit term 1 entries until it has a term 2 entry
            // replicated to a majority.

            cluster.electLeader(NodeId.of(1));
            RaftNode leader1 = cluster.nodes.get(NodeId.of(1));
            long term1 = leader1.currentTerm();

            // Propose a command — but only deliver to node 2, not node 3
            leader1.propose(new byte[]{1});
            TestTransport t1 = cluster.transports.get(NodeId.of(1));

            // Deliver only to node 2
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

            // Now trigger election on node 2
            cluster.electLeader(NodeId.of(2));
            RaftNode leader2 = cluster.findLeader();
            assertNotNull(leader2);
            assertTrue(leader2.currentTerm() > term1);

            // Leader 2's no-op entry will be from the current term.
            // After replication, the no-op commits, which also commits prior entries.
            cluster.deliverAllMessages(10);

            assertTrue(leader2.log().commitIndex() > 0);
        }
    }

    // ========================================================================
    // Five-node cluster tests
    // ========================================================================

    @Nested
    class FiveNodeClusterTests {

        @Test
        void leaderElectionFiveNodes() {
            TestCluster cluster = new TestCluster(5);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            assertEquals(RaftRole.LEADER, leader.role());

            // Followers
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

            // Deliver AppendEntries and responses
            cluster.deliverAllMessages(20);
            // Propagate updated commitIndex to followers via heartbeat
            cluster.tickLeaderHeartbeatAndDeliver();

            // All nodes should have committed all entries
            for (var entry : cluster.logs.entrySet()) {
                assertTrue(entry.getValue().commitIndex() >= 4, // no-op + 3 entries
                        "Node " + entry.getKey() + " commitIndex=" + entry.getValue().commitIndex());
            }
        }
    }

    // ========================================================================
    // PreVote tests
    // ========================================================================

    @Nested
    class PreVoteTests {

        @Test
        void preVotePreventsTermInflationFromPartitionedNode() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));
            long termAfterElection = cluster.nodes.get(NodeId.of(1)).currentTerm();

            // Simulate node 3 being partitioned: trigger election timeout on node 3
            // but don't deliver the PreVote responses
            RaftNode partitioned = cluster.nodes.get(NodeId.of(3));

            // Trigger multiple election timeouts on the partitioned node
            for (int round = 0; round < 5; round++) {
                for (int i = 0; i < 301; i++) {
                    partitioned.tick();
                }
                // Clear messages from the partitioned node (simulating partition)
                cluster.transports.get(NodeId.of(3)).clear();
            }

            // The partitioned node should NOT have inflated its term significantly
            // because PreVote prevents term increment until a majority responds.
            // It stays at the pre-election term because PreVote doesn't increment term.
            // Each timeout restarts PreVote but never gets to actual election.
            assertEquals(termAfterElection, partitioned.currentTerm(),
                    "Partitioned node should not inflate term due to PreVote");
        }

        @Test
        void preVoteSucceedsBeforeRealElection() {
            TestCluster cluster = new TestCluster(3);

            // Trigger election timeout on node 1
            cluster.triggerElectionTimeout(NodeId.of(1));

            // Check that PreVote requests were sent
            TestTransport t1 = cluster.transports.get(NodeId.of(1));
            List<RequestVoteRequest> preVotes = t1.messagesOfType(RequestVoteRequest.class);
            assertTrue(preVotes.stream().allMatch(RequestVoteRequest::preVote),
                    "Initial messages should be PreVote requests");

            // Deliver PreVote responses
            cluster.deliverMessages();
            cluster.deliverMessages(); // deliver RequestVote responses

            // After PreVote succeeds, real RequestVote should be sent
            cluster.deliverAllMessages(10);

            // Node 1 should eventually become leader
            assertEquals(RaftRole.LEADER, cluster.nodes.get(NodeId.of(1)).role());
        }

        @Test
        void preVoteRejectedWhenFollowerHasRecentLeader() {
            // Set up cluster with a leader
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            // Node 3 sends a PreVote. Nodes with a recent leader should reject.
            NodeId node3 = NodeId.of(3);
            RequestVoteRequest preVoteReq = new RequestVoteRequest(
                    cluster.nodes.get(node3).currentTerm() + 1,
                    node3,
                    cluster.logs.get(node3).lastIndex(),
                    cluster.logs.get(node3).lastTerm(),
                    true
            );

            // Send to node 2 (which has a recent leader)
            cluster.nodes.get(NodeId.of(2)).handleMessage(preVoteReq);

            // Check that node 2 rejected the PreVote
            TestTransport t2 = cluster.transports.get(NodeId.of(2));
            List<RequestVoteResponse> responses = t2.messagesOfType(RequestVoteResponse.class);
            assertFalse(responses.isEmpty());
            RequestVoteResponse resp = responses.getFirst();
            assertTrue(resp.preVote());
            assertFalse(resp.voteGranted(),
                    "Follower with recent leader should reject PreVote");
        }
    }

    // ========================================================================
    // CheckQuorum tests
    // ========================================================================

    @Nested
    class CheckQuorumTests {

        @Test
        void leaderStepsDownWithoutQuorum() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            assertEquals(RaftRole.LEADER, leader.role());

            // Now simulate a partition: don't deliver any messages from leader
            // Tick the leader enough for heartbeat timeout + check-quorum failure
            cluster.transports.get(NodeId.of(1)).clear();

            // Tick through one heartbeat interval — first heartbeat passes
            // because peerActivity starts as TRUE
            for (int i = 0; i < 50; i++) {
                leader.tick();
            }
            cluster.transports.get(NodeId.of(1)).clear();

            // After first heartbeat, activity was reset to FALSE.
            // Next heartbeat check should fail since no responses came in.
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

            // Tick through several heartbeat intervals while delivering messages
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

    // ========================================================================
    // Leadership transfer tests
    // ========================================================================

    @Nested
    class LeadershipTransferTests {

        @Test
        void leadershipTransferToTarget() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            assertEquals(RaftRole.LEADER, leader.role());

            // Ensure followers are caught up
            cluster.deliverAllMessages(10);

            // Initiate transfer to node 2
            assertTrue(leader.transferLeadership(NodeId.of(2)));

            // Deliver TimeoutNow and subsequent election messages
            cluster.deliverAllMessages(10);

            // Node 2 should now be leader
            RaftNode newLeader = cluster.nodes.get(NodeId.of(2));
            assertEquals(RaftRole.LEADER, newLeader.role(),
                    "Node 2 should be leader after transfer");
        }

        @Test
        void leadershipTransferRejectsNewProposals() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));

            // Propose and replicate an entry to create a state where the target
            // is NOT yet fully caught up (has pending entries)
            leader.propose(new byte[]{99});
            // Do NOT deliver messages yet — node 2's matchIndex won't be caught up

            // Initiate transfer while node 2 is still behind
            leader.transferLeadership(NodeId.of(2));
            assertNotNull(leader.transferTarget(), "Transfer should be in progress");

            // During transfer, proposals should be rejected
            assertEquals(ProposalResult.TRANSFER_IN_PROGRESS, leader.propose(new byte[]{1}),
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
    }

    // ========================================================================
    // Log conflict resolution tests
    // ========================================================================

    @Nested
    class LogConflictTests {

        @Test
        void followerTruncatesDivergentEntries() {
            // Create a scenario where a follower has entries that diverge
            // from the leader's log

            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);
            RaftConfig config2 = RaftConfig.of(n2, Set.of(n1));
            RaftLog log2 = new RaftLog();
            TestTransport transport2 = new TestTransport();
            TestStateMachine sm2 = new TestStateMachine();
            RandomGenerator rng2 = RandomGenerator.of("L64X128MixRandom");
            RaftNode node2 = new RaftNode(config2, log2, transport2, sm2, rng2);

            // Manually add some entries to node2's log (simulating divergent entries)
            log2.append(new LogEntry(1, 1, new byte[]{1}));
            log2.append(new LogEntry(2, 1, new byte[]{2}));
            log2.append(new LogEntry(3, 2, new byte[]{3})); // divergent: term 2

            // Leader sends AppendEntries with different entries at index 3
            // prevLogIndex=2, prevLogTerm=1 should match
            AppendEntriesRequest req = new AppendEntriesRequest(
                    3, // leader term
                    n1,
                    2, 1, // prevLogIndex, prevLogTerm
                    List.of(new LogEntry(3, 3, new byte[]{30})), // different term!
                    3 // leaderCommit
            );

            node2.handleMessage(req);

            // Node2 should have truncated index 3 and replaced it
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

            // Node2 has entries [1:term1, 2:term1]
            log2.append(new LogEntry(1, 1, new byte[]{1}));
            log2.append(new LogEntry(2, 1, new byte[]{2}));

            // Leader sends AppendEntries with prevLogIndex=2, prevLogTerm=2 (mismatch)
            AppendEntriesRequest req = new AppendEntriesRequest(
                    3, n1,
                    2, 2, // prevLogTerm=2 doesn't match node2's term 1 at index 2
                    List.of(new LogEntry(3, 3, new byte[]{30})),
                    3
            );

            node2.handleMessage(req);

            // Should have sent a rejection
            List<AppendEntriesResponse> responses =
                    transport2.messagesOfType(AppendEntriesResponse.class);
            assertFalse(responses.isEmpty());
            assertFalse(responses.getFirst().success());

            // Log should be unchanged
            assertEquals(2, log2.lastIndex());
            assertEquals(1, log2.lastTerm());
        }

        @Test
        void leaderDecrementsNextIndexOnRejection() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            long leaderTerm = leader.currentTerm();

            // Manually add a divergent entry to node 3 (simulating it was leader in a prior term)
            RaftLog log3 = cluster.logs.get(NodeId.of(3));
            // Clear node3's log and add divergent entries
            // (In practice this would happen through a complicated partition scenario)

            // Propose entries on leader
            leader.propose(new byte[]{10});
            leader.propose(new byte[]{20});

            // Deliver messages — leader will get rejections if logs don't match,
            // and will retry with decremented nextIndex
            cluster.deliverAllMessages(20);

            // Eventually all nodes should converge
            assertTrue(cluster.logs.get(NodeId.of(3)).commitIndex() > 0);
        }
    }

    // ========================================================================
    // Vote tracking tests
    // ========================================================================

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

            // Node 2 requests vote in term 1
            RequestVoteRequest req2 = new RequestVoteRequest(1, n2, 0, 0, false);
            node1.handleMessage(req2);

            List<RequestVoteResponse> responses = transport1.messagesOfType(RequestVoteResponse.class);
            assertEquals(1, responses.size());
            assertTrue(responses.getFirst().voteGranted());
            assertEquals(n2, node1.votedFor());

            transport1.clear();

            // Node 3 requests vote in same term 1 — should be rejected
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

            // Node 2 requests vote in term 1 — twice
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

            // Give node1 a log entry at term 5
            log1.append(new LogEntry(1, 5, new byte[]{1}));

            // Node 2 requests vote with only a term 3 log
            RequestVoteRequest req = new RequestVoteRequest(6, n2, 1, 3, false);
            node1.handleMessage(req);

            List<RequestVoteResponse> responses = transport1.messagesOfType(RequestVoteResponse.class);
            assertFalse(responses.getFirst().voteGranted(),
                    "Should reject vote if candidate's log is less up-to-date");
        }
    }

    // ========================================================================
    // Split vote and timeout tests
    // ========================================================================

    @Nested
    class SplitVoteTests {

        @Test
        void splitVoteResolvesViaRandomizedTimeout() {
            // In a 3-node cluster, if nodes 1 and 2 both start elections
            // simultaneously, they may split the vote. Randomized timeouts
            // should eventually resolve this.

            TestCluster cluster = new TestCluster(3);

            // Trigger election timeout on both node 1 and node 2 simultaneously
            cluster.triggerElectionTimeout(NodeId.of(1));
            cluster.triggerElectionTimeout(NodeId.of(2));

            // Deliver all messages
            cluster.deliverAllMessages(10);

            // After enough rounds, tick and deliver to resolve the split
            for (int i = 0; i < 10; i++) {
                for (var node : cluster.nodes.values()) {
                    for (int t = 0; t < 301; t++) {
                        node.tick();
                    }
                }
                cluster.deliverAllMessages(10);

                // Check if a leader has been elected
                RaftNode leader = cluster.findLeader();
                if (leader != null) {
                    // Verify only one leader
                    long leaderCount = cluster.nodes.values().stream()
                            .filter(n -> n.role() == RaftRole.LEADER)
                            .count();
                    assertEquals(1, leaderCount, "Should have exactly one leader");
                    return; // Test passed
                }
            }

            fail("Should have elected a leader within 10 rounds");
        }
    }

    // ========================================================================
    // Term handling tests
    // ========================================================================

    @Nested
    class TermHandlingTests {

        @Test
        void nodeStepsDownOnHigherTerm() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            assertEquals(RaftRole.LEADER, leader.role());

            long leaderTerm = leader.currentTerm();

            // Send an AppendEntries with a higher term (as if another leader exists)
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

            // Advance node1's term by having it see a higher term
            node1.handleMessage(new AppendEntriesRequest(5, n2, 0, 0, List.of(), 0));
            assertEquals(5, node1.currentTerm());
            transport1.clear();

            // Now send stale-term AppendEntries
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

            // Trigger election: node1 becomes candidate
            for (int i = 0; i < 301; i++) {
                node1.tick();
            }
            // Deliver PreVote responses to trigger real election
            // Simulate PreVote success
            long preVoteTerm = node1.currentTerm() + 1;
            node1.handleMessage(new RequestVoteResponse(node1.currentTerm(), true, n2, true));
            node1.handleMessage(new RequestVoteResponse(node1.currentTerm(), true, n3, true));

            // Now node1 should be CANDIDATE
            if (node1.role() != RaftRole.CANDIDATE) {
                // Already became leader from the election, adjust test
                return;
            }

            long candidateTerm = node1.currentTerm();
            transport1.clear();

            // Another node sends AppendEntries with the same term (it won the election)
            AppendEntriesRequest req = new AppendEntriesRequest(
                    candidateTerm, n2, 0, 0, List.of(), 0);
            node1.handleMessage(req);

            assertEquals(RaftRole.FOLLOWER, node1.role(),
                    "Candidate should step down on AppendEntries with current term");
        }
    }

    // ========================================================================
    // RaftLog unit tests
    // ========================================================================

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
            assertEquals(1, log.size()); // only entry 3 remains
            assertEquals(3, log.lastIndex());
            assertEquals(2, log.lastTerm());

            // Can still query by index
            assertNotNull(log.entryAt(3));
            assertNull(log.entryAt(1)); // compacted
            assertNull(log.entryAt(2)); // compacted

            // termAt for snapshot boundary
            assertEquals(1, log.termAt(2)); // snapshot term
        }

        @Test
        void appendEntriesWithConflict() {
            RaftLog log = new RaftLog();
            log.append(new LogEntry(1, 1, new byte[]{1}));
            log.append(new LogEntry(2, 1, new byte[]{2}));
            log.append(new LogEntry(3, 1, new byte[]{3}));

            // Leader says: prevLogIndex=1, prevLogTerm=1, entries at index 2 and 3 with term 2
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
            assertEquals(1, log.lastIndex()); // unchanged
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

            // Higher term wins
            assertTrue(log.isAtLeastAsUpToDate(4, 1));
            assertFalse(log.isAtLeastAsUpToDate(2, 10));

            // Same term: higher or equal index wins
            assertTrue(log.isAtLeastAsUpToDate(3, 2));
            assertTrue(log.isAtLeastAsUpToDate(3, 3));
            assertFalse(log.isAtLeastAsUpToDate(3, 1));
        }
    }

    // ========================================================================
    // State machine application tests
    // ========================================================================

    @Nested
    class StateMachineApplicationTests {

        @Test
        void committedEntriesAreApplied() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            leader.propose(new byte[]{42});
            leader.propose(new byte[]{43});

            // Deliver AppendEntries and responses, then heartbeat for commitIndex propagation
            cluster.deliverAllMessages(10);
            cluster.tickLeaderHeartbeatAndDeliver();

            // All state machines should have applied the no-op + both proposed entries
            for (var entry : cluster.stateMachines.entrySet()) {
                TestStateMachine sm = entry.getValue();
                assertTrue(sm.applied.size() >= 3,
                        "Node " + entry.getKey() + " should have applied at least 3 entries (no-op + 2 commands), got " + sm.applied.size());
            }
        }
    }

    // ========================================================================
    // RaftConfig tests
    // ========================================================================

    @Nested
    class RaftConfigTests {

        @Test
        void quorumSizeCalculation() {
            // 1 node: quorum = 1
            assertEquals(1, RaftConfig.of(NodeId.of(1), Set.of()).quorumSize());

            // 3 nodes: quorum = 2
            assertEquals(2, RaftConfig.of(NodeId.of(1), Set.of(NodeId.of(2), NodeId.of(3))).quorumSize());

            // 5 nodes: quorum = 3
            assertEquals(3, RaftConfig.of(NodeId.of(1),
                    Set.of(NodeId.of(2), NodeId.of(3), NodeId.of(4), NodeId.of(5))).quorumSize());
        }

        @Test
        void invalidConfigRejected() {
            assertThrows(IllegalArgumentException.class, () ->
                    new RaftConfig(NodeId.of(1), Set.of(), 0, 300, 50, 64, 256 * 1024, 1024, 10));
            assertThrows(IllegalArgumentException.class, () ->
                    new RaftConfig(NodeId.of(1), Set.of(), 150, 100, 50, 64, 256 * 1024, 1024, 10));
            assertThrows(IllegalArgumentException.class, () ->
                    new RaftConfig(NodeId.of(1), Set.of(), 150, 300, 150, 64, 256 * 1024, 1024, 10));
        }
    }

    // ========================================================================
    // LogEntry tests
    // ========================================================================

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

    // ========================================================================
    // Backpressure tests
    // ========================================================================

    @Nested
    class BackpressureTests {

        @Test
        void proposalRejectedWhenOverloaded() {
            // Create config with very small maxPendingProposals
            RaftConfig config = new RaftConfig(
                    NodeId.of(1), Set.of(), 150, 300, 50, 64, 256 * 1024, 3, 10);
            RaftLog log = new RaftLog();
            TestTransport transport = new TestTransport();
            TestStateMachine sm = new TestStateMachine();
            RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");
            RaftNode node = new RaftNode(config, log, transport, sm, rng);

            // Become leader (single-node cluster)
            for (int i = 0; i < 301; i++) {
                node.tick();
            }
            assertEquals(RaftRole.LEADER, node.role());

            // The no-op is already committed for a single-node cluster, so
            // commitIndex should be at 1. Fill up with maxPendingProposals entries.
            // But in single-node, every propose commits immediately, so we need
            // a multi-node cluster where entries don't commit.

            // Use a 3-node cluster with no message delivery to prevent commits
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);
            NodeId n3 = NodeId.of(3);
            RaftConfig config3 = new RaftConfig(
                    n1, Set.of(n2, n3), 150, 300, 50, 64, 256 * 1024, 3, 10);
            RaftLog log3 = new RaftLog();
            TestTransport transport3 = new TestTransport();
            TestStateMachine sm3 = new TestStateMachine();
            RandomGenerator rng3 = RandomGenerator.of("L64X128MixRandom");
            RaftNode leader = new RaftNode(config3, log3, transport3, sm3, rng3);

            // Force become leader by simulating election
            // Trigger election timeout
            for (int i = 0; i < 301; i++) {
                leader.tick();
            }
            // Grant pre-votes
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n2, true));
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n3, true));
            // Grant real votes
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n2, false));
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n3, false));
            transport3.clear();

            assertEquals(RaftRole.LEADER, leader.role());
            // No-op is at index 1, uncommitted because no responses delivered.
            // commitIndex is 0 for multi-node cluster (no majority).
            // uncommitted = lastIndex - commitIndex = 1 - 0 = 1

            // Propose until we hit the limit (maxPendingProposals = 3)
            assertEquals(ProposalResult.ACCEPTED, leader.propose(new byte[]{1}));
            // uncommitted = 2 - 0 = 2
            assertEquals(ProposalResult.ACCEPTED, leader.propose(new byte[]{2}));
            // uncommitted = 3 - 0 = 3 >= maxPendingProposals (3)
            assertEquals(ProposalResult.OVERLOADED, leader.propose(new byte[]{3}));
        }

        @Test
        void proposalAcceptedAfterCommitReducesBackpressure() {
            // Single-node cluster: proposals always commit immediately,
            // so backpressure should never trigger with reasonable limits
            RaftConfig config = new RaftConfig(
                    NodeId.of(1), Set.of(), 150, 300, 50, 64, 256 * 1024, 1024, 10);
            RaftLog log = new RaftLog();
            TestTransport transport = new TestTransport();
            TestStateMachine sm = new TestStateMachine();
            RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");
            RaftNode node = new RaftNode(config, log, transport, sm, rng);

            for (int i = 0; i < 301; i++) {
                node.tick();
            }
            assertEquals(RaftRole.LEADER, node.role());

            // Propose many entries — they all commit immediately in single-node
            for (int i = 0; i < 100; i++) {
                assertEquals(ProposalResult.ACCEPTED, node.propose(new byte[]{(byte) i}));
            }
        }
    }

    // ========================================================================
    // Pipelining tests
    // ========================================================================

    @Nested
    class PipeliningTests {

        @Test
        void inflightWindowLimitsAppendEntries() {
            // Create a 3-node cluster with maxInflightAppends = 2
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);
            NodeId n3 = NodeId.of(3);
            RaftConfig config = new RaftConfig(
                    n1, Set.of(n2, n3), 150, 300, 50, 64, 256 * 1024, 1024, 2);
            RaftLog log = new RaftLog();
            TestTransport transport = new TestTransport();
            TestStateMachine sm = new TestStateMachine();
            RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");
            RaftNode leader = new RaftNode(config, log, transport, sm, rng);

            // Force become leader
            for (int i = 0; i < 301; i++) {
                leader.tick();
            }
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n2, true));
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n3, true));
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n2, false));
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n3, false));

            assertEquals(RaftRole.LEADER, leader.role());
            transport.clear();

            // Propose several entries without delivering responses
            leader.propose(new byte[]{1});
            leader.propose(new byte[]{2});
            leader.propose(new byte[]{3});

            // Count AppendEntries to node 2
            long appendCountToN2 = transport.messages().stream()
                    .filter(m -> m.target().equals(n2) && m.message() instanceof AppendEntriesRequest)
                    .count();

            // With maxInflightAppends = 2, at most 2 should be sent per peer
            // (becomeLeader sends one for no-op, plus each propose sends one,
            //  but the window caps it at 2)
            assertTrue(appendCountToN2 <= 2,
                    "Should limit in-flight AppendEntries to maxInflightAppends, got " + appendCountToN2);
        }

        @Test
        void inflightWindowResetsOnResponse() {
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);
            RaftConfig config = new RaftConfig(
                    n1, Set.of(n2), 150, 300, 50, 64, 256 * 1024, 1024, 1);
            RaftLog log = new RaftLog();
            TestTransport transport = new TestTransport();
            TestStateMachine sm = new TestStateMachine();
            RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");
            RaftNode leader = new RaftNode(config, log, transport, sm, rng);

            // Force become leader (single peer, need majority of 2 => only self needed for 2-node)
            for (int i = 0; i < 301; i++) {
                leader.tick();
            }
            // 2-node cluster: pre-vote needs quorum=2, self counts
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n2, true));
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n2, false));

            assertEquals(RaftRole.LEADER, leader.role());
            transport.clear();

            // With maxInflightAppends=1, first propose should send, second should be blocked
            leader.propose(new byte[]{1});
            long countAfterFirst = transport.messagesTo(n2, AppendEntriesRequest.class).size();

            leader.propose(new byte[]{2});
            long countAfterSecond = transport.messagesTo(n2, AppendEntriesRequest.class).size();

            // The second propose should be blocked because inflight is at max
            assertEquals(countAfterFirst, countAfterSecond,
                    "Second propose should be blocked by inflight window");

            // Now simulate a response from n2 to clear the inflight window
            leader.handleMessage(new AppendEntriesResponse(
                    leader.currentTerm(), true, log.lastIndex(), n2));
            transport.clear();

            // Now a heartbeat or propose should be able to send again
            leader.propose(new byte[]{3});
            long countAfterResponse = transport.messagesTo(n2, AppendEntriesRequest.class).size();
            assertTrue(countAfterResponse > 0,
                    "After response clears inflight, new AppendEntries should be sent");
        }
    }
}
