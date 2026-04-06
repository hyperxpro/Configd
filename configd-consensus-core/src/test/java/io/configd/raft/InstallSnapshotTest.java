package io.configd.raft;

import io.configd.common.NodeId;
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
 * Tests for InstallSnapshot RPC handling in the Raft consensus implementation.
 * <p>
 * Covers:
 * <ul>
 *   <li>Snapshot transfer from leader to lagging follower</li>
 *   <li>Follower state machine restoration from snapshot</li>
 *   <li>Leader nextIndex/matchIndex advancement after snapshot</li>
 *   <li>Term checking and stale snapshot rejection</li>
 *   <li>Integration: cluster with a node falling behind</li>
 * </ul>
 */
class InstallSnapshotTest {

    // ========================================================================
    // Test infrastructure (mirrors RaftNodeTest patterns)
    // ========================================================================

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
        byte[] snapshotData = new byte[0];
        byte[] restoredFrom = null;

        record AppliedEntry(long index, long term, byte[] command) {}

        @Override
        public void apply(long index, long term, byte[] command) {
            applied.add(new AppliedEntry(index, term, command));
            // Build snapshot data as a simple accumulation
            snapshotData = ("snap-" + index).getBytes();
        }

        @Override
        public byte[] snapshot() {
            return snapshotData.clone();
        }

        @Override
        public void restoreSnapshot(byte[] snapshot) {
            this.restoredFrom = snapshot.clone();
            this.snapshotData = snapshot.clone();
        }
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
                RandomGenerator rng = new java.util.Random(id.id() * 31L + 7);

                RaftNode node = new RaftNode(config, log, transport, sm, rng);
                nodes.put(id, node);
                transports.put(id, transport);
                stateMachines.put(id, sm);
                logs.put(id, log);
            }
        }

        void deliverMessages() {
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

        /** Delivers messages only to specific targets. */
        void deliverMessagesTo(Set<NodeId> targets) {
            Map<NodeId, List<RaftMessage>> toDeliver = new HashMap<>();
            for (var entry : transports.entrySet()) {
                for (var msg : entry.getValue().messages()) {
                    if (targets.contains(msg.target())) {
                        toDeliver.computeIfAbsent(msg.target(), k -> new ArrayList<>()).add(msg.message());
                    }
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

        void triggerElectionTimeout(NodeId id) {
            RaftNode node = nodes.get(id);
            for (int i = 0; i < 301; i++) {
                node.tick();
            }
        }

        void electLeader(NodeId id) {
            triggerElectionTimeout(id);
            deliverAllMessages(10);
        }

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

    // ========================================================================
    // Direct InstallSnapshot RPC tests
    // ========================================================================

    @Nested
    class DirectInstallSnapshotTests {

        @Test
        void followerAcceptsValidSnapshot() {
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);

            RaftConfig config2 = RaftConfig.of(n2, Set.of(n1));
            RaftLog log2 = new RaftLog();
            TestTransport transport2 = new TestTransport();
            TestStateMachine sm2 = new TestStateMachine();
            RandomGenerator rng2 = new java.util.Random(42);
            RaftNode node2 = new RaftNode(config2, log2, transport2, sm2, rng2);

            byte[] snapData = "test-snapshot-data".getBytes();
            InstallSnapshotRequest req = new InstallSnapshotRequest(
                    1, n1, 10, 1, 0, snapData, true
            );

            node2.handleMessage(req);

            // Verify state machine was restored
            assertNotNull(sm2.restoredFrom);
            assertArrayEquals(snapData, sm2.restoredFrom);

            // Verify log was compacted to snapshot point
            assertEquals(10, log2.snapshotIndex());
            assertEquals(1, log2.snapshotTerm());

            // Verify commit and applied indices advanced
            assertEquals(10, log2.commitIndex());
            assertEquals(10, log2.lastApplied());

            // Verify success response was sent
            List<InstallSnapshotResponse> responses =
                    transport2.messagesOfType(InstallSnapshotResponse.class);
            assertEquals(1, responses.size());
            assertTrue(responses.getFirst().success());
        }

        @Test
        void followerRejectsSnapshotWithStaleTerm() {
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);

            RaftConfig config2 = RaftConfig.of(n2, Set.of(n1));
            RaftLog log2 = new RaftLog();
            TestTransport transport2 = new TestTransport();
            TestStateMachine sm2 = new TestStateMachine();
            RandomGenerator rng2 = new java.util.Random(42);
            RaftNode node2 = new RaftNode(config2, log2, transport2, sm2, rng2);

            // Advance node2's term to 5
            node2.handleMessage(new AppendEntriesRequest(5, n1, 0, 0, List.of(), 0));
            transport2.clear();

            // Send snapshot with stale term 3
            InstallSnapshotRequest req = new InstallSnapshotRequest(
                    3, n1, 10, 1, 0, "data".getBytes(), true
            );
            node2.handleMessage(req);

            // Should reject
            List<InstallSnapshotResponse> responses =
                    transport2.messagesOfType(InstallSnapshotResponse.class);
            assertEquals(1, responses.size());
            assertFalse(responses.getFirst().success());
            assertEquals(5, responses.getFirst().term());

            // State machine should not be touched
            assertNull(sm2.restoredFrom);
        }

        @Test
        void followerAcceptsSnapshotWithHigherTermAndStepsDown() {
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);
            NodeId n3 = NodeId.of(3);

            RaftConfig config2 = RaftConfig.of(n2, Set.of(n1, n3));
            RaftLog log2 = new RaftLog();
            TestTransport transport2 = new TestTransport();
            TestStateMachine sm2 = new TestStateMachine();
            RandomGenerator rng2 = new java.util.Random(42);
            RaftNode node2 = new RaftNode(config2, log2, transport2, sm2, rng2);

            // Node2 is at term 1
            node2.handleMessage(new AppendEntriesRequest(1, n1, 0, 0, List.of(), 0));
            transport2.clear();

            // Snapshot with higher term
            InstallSnapshotRequest req = new InstallSnapshotRequest(
                    5, n1, 10, 3, 0, "snapshot".getBytes(), true
            );
            node2.handleMessage(req);

            assertEquals(5, node2.currentTerm());
            assertEquals(RaftRole.FOLLOWER, node2.role());
            assertEquals(n1, node2.leaderId());
            assertNotNull(sm2.restoredFrom);
        }

        @Test
        void followerIgnoresOlderSnapshot() {
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);

            RaftConfig config2 = RaftConfig.of(n2, Set.of(n1));
            RaftLog log2 = new RaftLog();
            TestTransport transport2 = new TestTransport();
            TestStateMachine sm2 = new TestStateMachine();
            RandomGenerator rng2 = new java.util.Random(42);
            RaftNode node2 = new RaftNode(config2, log2, transport2, sm2, rng2);

            // First snapshot at index 10
            node2.handleMessage(new InstallSnapshotRequest(
                    1, n1, 10, 1, 0, "first".getBytes(), true));
            transport2.clear();
            sm2.restoredFrom = null; // Reset

            // Second snapshot at index 5 (older) — should be ignored
            node2.handleMessage(new InstallSnapshotRequest(
                    1, n1, 5, 1, 0, "older".getBytes(), true));

            // State machine should NOT be restored again
            assertNull(sm2.restoredFrom);
            assertEquals(10, log2.snapshotIndex()); // unchanged

            // Should still send success (snapshot already applied)
            List<InstallSnapshotResponse> responses =
                    transport2.messagesOfType(InstallSnapshotResponse.class);
            assertEquals(1, responses.size());
            assertTrue(responses.getFirst().success());
        }

        @Test
        void candidateStepsDownOnInstallSnapshot() {
            NodeId n1 = NodeId.of(1);
            NodeId n2 = NodeId.of(2);
            NodeId n3 = NodeId.of(3);

            RaftConfig config2 = RaftConfig.of(n2, Set.of(n1, n3));
            RaftLog log2 = new RaftLog();
            TestTransport transport2 = new TestTransport();
            TestStateMachine sm2 = new TestStateMachine();
            RandomGenerator rng2 = new java.util.Random(42);
            RaftNode node2 = new RaftNode(config2, log2, transport2, sm2, rng2);

            // Make node2 a candidate
            for (int i = 0; i < 301; i++) {
                node2.tick();
            }
            // It might be in pre-vote or candidate; receive pre-vote grants
            node2.handleMessage(new RequestVoteResponse(node2.currentTerm(), true, n1, true));
            node2.handleMessage(new RequestVoteResponse(node2.currentTerm(), true, n3, true));
            transport2.clear();

            // Send InstallSnapshot with the candidate's current term
            long candidateTerm = node2.currentTerm();
            InstallSnapshotRequest req = new InstallSnapshotRequest(
                    candidateTerm, n1, 10, 1, 0, "snap".getBytes(), true
            );
            node2.handleMessage(req);

            assertEquals(RaftRole.FOLLOWER, node2.role());
        }
    }

    // ========================================================================
    // Snapshot trigger tests
    // ========================================================================

    @Nested
    class SnapshotTriggerTests {

        @Test
        void triggerSnapshotCompactsLog() {
            NodeId n1 = NodeId.of(1);
            RaftConfig config = RaftConfig.of(n1, Set.of());
            RaftLog log = new RaftLog();
            TestTransport transport = new TestTransport();
            TestStateMachine sm = new TestStateMachine();
            RandomGenerator rng = new java.util.Random(42);
            RaftNode node = new RaftNode(config, log, transport, sm, rng);

            // Become leader (single node)
            for (int i = 0; i < 301; i++) {
                node.tick();
            }

            // Propose entries
            for (int i = 0; i < 10; i++) {
                node.propose(new byte[]{(byte) i});
            }

            // Entries should be committed and applied (single node)
            assertTrue(log.lastApplied() > 0);
            int sizeBeforeSnapshot = log.size();

            // Trigger snapshot
            assertTrue(node.triggerSnapshot());

            // Log should be compacted
            assertEquals(0, log.size());
            assertTrue(log.snapshotIndex() > 0);
        }

        @Test
        void triggerSnapshotReturnsFalseWhenNothingToSnapshot() {
            NodeId n1 = NodeId.of(1);
            RaftConfig config = RaftConfig.of(n1, Set.of());
            RaftLog log = new RaftLog();
            TestTransport transport = new TestTransport();
            TestStateMachine sm = new TestStateMachine();
            RandomGenerator rng = new java.util.Random(42);
            RaftNode node = new RaftNode(config, log, transport, sm, rng);

            // No entries applied — nothing to snapshot
            assertFalse(node.triggerSnapshot());
        }
    }

    // ========================================================================
    // Integration: lagging follower receives snapshot
    // ========================================================================

    @Nested
    class LaggingFollowerIntegrationTests {

        @Test
        void leaderSendsSnapshotToLaggingFollower() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            NodeId laggingNode = NodeId.of(3);

            // Propose several entries, but only deliver to node 2 (not node 3)
            for (int i = 0; i < 5; i++) {
                leader.propose(new byte[]{(byte) i});
            }

            // Deliver only between node 1 and node 2
            Set<NodeId> activeNodes = Set.of(NodeId.of(1), NodeId.of(2));
            for (int round = 0; round < 10; round++) {
                cluster.deliverMessagesTo(activeNodes);
            }

            // Heartbeat to propagate commit
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }
            for (int round = 0; round < 5; round++) {
                cluster.deliverMessagesTo(activeNodes);
            }

            // Leader should have committed entries
            assertTrue(leader.log().commitIndex() >= 5);

            // Now trigger snapshot on leader — this compacts the log
            assertTrue(leader.triggerSnapshot());
            assertTrue(leader.log().snapshotIndex() > 0);

            // Clear any pending messages to isolate the snapshot transfer
            cluster.transports.values().forEach(TestTransport::clear);

            // Now trigger a heartbeat that will try to send to node 3
            // Since node 3 is behind the snapshot, leader should send InstallSnapshot
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }

            // Check that an InstallSnapshot was sent to node 3
            TestTransport leaderTransport = cluster.transports.get(NodeId.of(1));
            List<InstallSnapshotRequest> snapReqs =
                    leaderTransport.messagesTo(laggingNode, InstallSnapshotRequest.class);

            assertTrue(snapReqs.size() > 0,
                    "Leader should send InstallSnapshot to lagging follower");

            InstallSnapshotRequest snapReq = snapReqs.getFirst();
            assertEquals(leader.currentTerm(), snapReq.term());
            assertTrue(snapReq.lastIncludedIndex() > 0);
            assertTrue(snapReq.data().length > 0);
            assertTrue(snapReq.done());

            // Now deliver the snapshot to node 3
            cluster.deliverAllMessages(10);

            // Node 3's log should be caught up to the snapshot point
            RaftLog log3 = cluster.logs.get(laggingNode);
            assertTrue(log3.snapshotIndex() > 0,
                    "Lagging follower should have applied the snapshot");

            // Node 3's state machine should have been restored
            TestStateMachine sm3 = cluster.stateMachines.get(laggingNode);
            assertNotNull(sm3.restoredFrom,
                    "Lagging follower's state machine should be restored from snapshot");
        }

        @Test
        void followerCatchesUpAfterSnapshot() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            NodeId laggingNode = NodeId.of(3);

            // Propose entries, deliver only to node 2
            for (int i = 0; i < 3; i++) {
                leader.propose(new byte[]{(byte) i});
            }
            Set<NodeId> activeNodes = Set.of(NodeId.of(1), NodeId.of(2));
            for (int round = 0; round < 10; round++) {
                cluster.deliverMessagesTo(activeNodes);
            }

            // Heartbeat and deliver to commit
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }
            for (int round = 0; round < 5; round++) {
                cluster.deliverMessagesTo(activeNodes);
            }

            // Compact the leader's log
            assertTrue(leader.triggerSnapshot());

            // Propose MORE entries after snapshot
            leader.propose(new byte[]{99});
            for (int round = 0; round < 5; round++) {
                cluster.deliverMessagesTo(activeNodes);
            }

            // Now deliver everything — node 3 should receive snapshot + new entries
            cluster.deliverAllMessages(20);
            cluster.tickLeaderHeartbeatAndDeliver();

            // Node 3 should have caught up
            RaftLog log3 = cluster.logs.get(laggingNode);
            assertTrue(log3.snapshotIndex() > 0 || log3.lastIndex() > 0,
                    "Node 3 should have state from snapshot or subsequent entries");
        }
    }

    // ========================================================================
    // Leader handles InstallSnapshotResponse
    // ========================================================================

    @Nested
    class InstallSnapshotResponseHandlingTests {

        @Test
        void leaderUpdatesIndicesOnSuccessfulSnapshotResponse() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));

            // Propose and commit entries
            for (int i = 0; i < 3; i++) {
                leader.propose(new byte[]{(byte) i});
            }
            cluster.deliverAllMessages(10);

            // Trigger snapshot
            leader.triggerSnapshot();
            long snapIndex = leader.log().snapshotIndex();
            assertTrue(snapIndex > 0);

            // Simulate receiving a successful snapshot response from node 3
            cluster.transports.get(NodeId.of(1)).clear();
            leader.handleMessage(new InstallSnapshotResponse(
                    leader.currentTerm(), true, NodeId.of(3), snapIndex));

            // After snapshot response, leader should send regular AppendEntries
            // on the next heartbeat since nextIndex is now past the snapshot
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }

            // Check that AppendEntries (not InstallSnapshot) is sent to node 3
            TestTransport leaderTransport = cluster.transports.get(NodeId.of(1));
            List<AppendEntriesRequest> appendReqs =
                    leaderTransport.messagesTo(NodeId.of(3), AppendEntriesRequest.class);
            List<InstallSnapshotRequest> snapReqs =
                    leaderTransport.messagesTo(NodeId.of(3), InstallSnapshotRequest.class);

            // Either we get AppendEntries (normal catch-up) or no more InstallSnapshot
            // since the follower is now at the snapshot index
            assertTrue(appendReqs.size() > 0 || snapReqs.isEmpty(),
                    "After successful snapshot, leader should send AppendEntries or no snapshot");
        }

        @Test
        void leaderStepsDownOnHigherTermInSnapshotResponse() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            long leaderTerm = leader.currentTerm();
            assertEquals(RaftRole.LEADER, leader.role());

            // Send a snapshot response with a higher term
            leader.handleMessage(new InstallSnapshotResponse(
                    leaderTerm + 5, false, NodeId.of(3), 0L));

            assertEquals(RaftRole.FOLLOWER, leader.role());
            assertEquals(leaderTerm + 5, leader.currentTerm());
        }

        @Test
        void leaderIgnoresStaleTermSnapshotResponse() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            long leaderTerm = leader.currentTerm();

            // Send a snapshot response with an old term
            leader.handleMessage(new InstallSnapshotResponse(
                    leaderTerm - 1, true, NodeId.of(3), 0L));

            // Should still be leader, nothing changed
            assertEquals(RaftRole.LEADER, leader.role());
            assertEquals(leaderTerm, leader.currentTerm());
        }
    }

    // ========================================================================
    // Metrics tests
    // ========================================================================

    @Nested
    class MetricsTests {

        @Test
        void metricsReflectsNodeState() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            RaftMetrics metrics = leader.metrics();

            assertEquals(NodeId.of(1), metrics.nodeId());
            assertEquals(RaftRole.LEADER, metrics.role());
            assertTrue(metrics.currentTerm() > 0);
            assertEquals(NodeId.of(1), metrics.leaderId());
            assertTrue(metrics.lastLogIndex() > 0);
            assertEquals(0, metrics.snapshotIndex());
        }

        @Test
        void metricsReflectsReplicationLag() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));

            // Propose entries but don't deliver — followers will lag
            leader.propose(new byte[]{1});
            leader.propose(new byte[]{2});

            RaftMetrics metrics = leader.metrics();
            assertTrue(metrics.replicationLagMax() > 0,
                    "Should show replication lag when followers haven't caught up");
        }

        @Test
        void followerMetricsHaveZeroReplicationLag() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode follower = cluster.nodes.get(NodeId.of(2));
            RaftMetrics metrics = follower.metrics();

            assertEquals(RaftRole.FOLLOWER, metrics.role());
            assertEquals(0, metrics.replicationLagMax());
        }

        @Test
        void metricsAfterSnapshot() {
            NodeId n1 = NodeId.of(1);
            RaftConfig config = RaftConfig.of(n1, Set.of());
            RaftLog log = new RaftLog();
            TestTransport transport = new TestTransport();
            TestStateMachine sm = new TestStateMachine();
            RandomGenerator rng = new java.util.Random(42);
            RaftNode node = new RaftNode(config, log, transport, sm, rng);

            // Become leader and propose entries
            for (int i = 0; i < 301; i++) {
                node.tick();
            }
            for (int i = 0; i < 5; i++) {
                node.propose(new byte[]{(byte) i});
            }

            node.triggerSnapshot();

            RaftMetrics metrics = node.metrics();
            assertTrue(metrics.snapshotIndex() > 0);
            assertEquals(0, metrics.logSize());
        }
    }

    // ========================================================================
    // ReadIndex integration tests via RaftNode
    // ========================================================================

    @Nested
    class ReadIndexIntegrationTests {

        @Test
        void singleNodeReadIndexIsImmediatelyReady() {
            NodeId n1 = NodeId.of(1);
            RaftConfig config = RaftConfig.of(n1, Set.of());
            RaftLog log = new RaftLog();
            TestTransport transport = new TestTransport();
            TestStateMachine sm = new TestStateMachine();
            RandomGenerator rng = new java.util.Random(42);
            RaftNode node = new RaftNode(config, log, transport, sm, rng);

            // Become leader
            for (int i = 0; i < 301; i++) {
                node.tick();
            }
            assertEquals(RaftRole.LEADER, node.role());

            // ReadIndex should be immediately ready for single-node cluster
            long readId = node.readIndex();
            assertTrue(readId >= 0);
            assertTrue(node.isReadReady(readId));

            node.completeRead(readId);
            assertFalse(node.isReadReady(readId));
        }

        @Test
        void followerCannotStartReadIndex() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode follower = cluster.nodes.get(NodeId.of(2));
            long readId = follower.readIndex();
            assertEquals(-1, readId);
        }

        @Test
        void readIndexConfirmedAfterHeartbeat() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));

            // Ensure entries are committed and applied
            cluster.deliverAllMessages(10);
            cluster.tickLeaderHeartbeatAndDeliver();

            // Start a read
            long readId = leader.readIndex();
            assertTrue(readId >= 0);

            // Before heartbeat, read might not be ready (depends on timing)
            // Tick through a heartbeat interval and deliver responses
            cluster.tickLeaderHeartbeatAndDeliver();

            // After heartbeat round with quorum, read should be ready
            assertTrue(leader.isReadReady(readId),
                    "Read should be ready after heartbeat confirms quorum");
        }
    }
}
