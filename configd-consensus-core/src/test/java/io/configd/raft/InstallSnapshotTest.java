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

class InstallSnapshotTest {

    static final class TestTransport implements RaftTransport {
        private final List<SentMessage> messages = new ArrayList<>();
        private java.util.function.BiConsumer<NodeId, RaftMessage> sendInterceptor;

        record SentMessage(NodeId target, RaftMessage message) {}

        @Override
        public void send(NodeId target, RaftMessage message) {
            messages.add(new SentMessage(target, message));
            if (sendInterceptor != null) {
                sendInterceptor.accept(target, message);
            }
        }

        /** Installs a hook consulted after each send captures its message; it may throw to model the
         *  production wire codec rejecting a frame - the IllegalArgumentException the outbound-drop
         *  tallies count. Null (the default) leaves send a plain capture. */
        void interceptSend(java.util.function.BiConsumer<NodeId, RaftMessage> interceptor) {
            this.sendInterceptor = interceptor;
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
        public long apply(long index, long term, byte[] command) {
            applied.add(new AppliedEntry(index, term, command));
            snapshotData = ("snap-" + index).getBytes();
            return StateMachine.NON_MUTATING;
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

            assertNotNull(sm2.restoredFrom);
            assertArrayEquals(snapData, sm2.restoredFrom);

            assertEquals(10, log2.snapshotIndex());
            assertEquals(1, log2.snapshotTerm());

            assertEquals(10, log2.commitIndex());
            assertEquals(10, log2.lastApplied());

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

            node2.handleMessage(new AppendEntriesRequest(5, n1, 0, 0, List.of(), 0));
            transport2.clear();

            InstallSnapshotRequest req = new InstallSnapshotRequest(
                    3, n1, 10, 1, 0, "data".getBytes(), true
            );
            node2.handleMessage(req);

            List<InstallSnapshotResponse> responses =
                    transport2.messagesOfType(InstallSnapshotResponse.class);
            assertEquals(1, responses.size());
            assertFalse(responses.getFirst().success());
            assertEquals(5, responses.getFirst().term());

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

            node2.handleMessage(new AppendEntriesRequest(1, n1, 0, 0, List.of(), 0));
            transport2.clear();

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

            node2.handleMessage(new InstallSnapshotRequest(
                    1, n1, 10, 1, 0, "first".getBytes(), true));
            transport2.clear();
            sm2.restoredFrom = null;

            node2.handleMessage(new InstallSnapshotRequest(
                    1, n1, 5, 1, 0, "older".getBytes(), true));

            assertNull(sm2.restoredFrom);
            assertEquals(10, log2.snapshotIndex());

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

            for (int i = 0; i < 301; i++) {
                node2.tick();
            }
            // The node may still be in pre-vote rather than candidate; grant via pre-vote
            // responses so it reaches candidate regardless of which phase it is in.
            node2.handleMessage(new RequestVoteResponse(node2.currentTerm(), true, n1, true));
            node2.handleMessage(new RequestVoteResponse(node2.currentTerm(), true, n3, true));
            transport2.clear();

            long candidateTerm = node2.currentTerm();
            InstallSnapshotRequest req = new InstallSnapshotRequest(
                    candidateTerm, n1, 10, 1, 0, "snap".getBytes(), true
            );
            node2.handleMessage(req);

            assertEquals(RaftRole.FOLLOWER, node2.role());
        }
    }

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

            for (int i = 0; i < 301; i++) {
                node.tick();
            }

            for (int i = 0; i < 10; i++) {
                node.propose(new byte[]{(byte) i});
            }

            assertTrue(log.lastApplied() > 0);
            int sizeBeforeSnapshot = log.size();

            assertTrue(node.triggerSnapshot());

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

            assertFalse(node.triggerSnapshot());
        }
    }

    @Nested
    class LaggingFollowerIntegrationTests {

        @Test
        void leaderSendsSnapshotToLaggingFollower() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));
            NodeId laggingNode = NodeId.of(3);

            for (int i = 0; i < 5; i++) {
                leader.propose(new byte[]{(byte) i});
            }

            Set<NodeId> activeNodes = Set.of(NodeId.of(1), NodeId.of(2));
            for (int round = 0; round < 10; round++) {
                cluster.deliverMessagesTo(activeNodes);
            }

            for (int i = 0; i < 51; i++) {
                leader.tick();
            }
            for (int round = 0; round < 5; round++) {
                cluster.deliverMessagesTo(activeNodes);
            }

            assertTrue(leader.log().commitIndex() >= 5);

            assertTrue(leader.triggerSnapshot());
            assertTrue(leader.log().snapshotIndex() > 0);

            cluster.transports.values().forEach(TestTransport::clear);

            for (int i = 0; i < 51; i++) {
                leader.tick();
            }

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

            cluster.deliverAllMessages(10);

            RaftLog log3 = cluster.logs.get(laggingNode);
            assertTrue(log3.snapshotIndex() > 0,
                    "Lagging follower should have applied the snapshot");

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

            for (int i = 0; i < 3; i++) {
                leader.propose(new byte[]{(byte) i});
            }
            Set<NodeId> activeNodes = Set.of(NodeId.of(1), NodeId.of(2));
            for (int round = 0; round < 10; round++) {
                cluster.deliverMessagesTo(activeNodes);
            }

            for (int i = 0; i < 51; i++) {
                leader.tick();
            }
            for (int round = 0; round < 5; round++) {
                cluster.deliverMessagesTo(activeNodes);
            }

            assertTrue(leader.triggerSnapshot());

            leader.propose(new byte[]{99});
            for (int round = 0; round < 5; round++) {
                cluster.deliverMessagesTo(activeNodes);
            }

            cluster.deliverAllMessages(20);
            cluster.tickLeaderHeartbeatAndDeliver();

            RaftLog log3 = cluster.logs.get(laggingNode);
            assertTrue(log3.snapshotIndex() > 0 || log3.lastIndex() > 0,
                    "Node 3 should have state from snapshot or subsequent entries");
        }
    }

    @Nested
    class InstallSnapshotResponseHandlingTests {

        @Test
        void leaderUpdatesIndicesOnSuccessfulSnapshotResponse() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));

            RaftNode leader = cluster.nodes.get(NodeId.of(1));

            for (int i = 0; i < 3; i++) {
                leader.propose(new byte[]{(byte) i});
            }
            cluster.deliverAllMessages(10);

            leader.triggerSnapshot();
            long snapIndex = leader.log().snapshotIndex();
            assertTrue(snapIndex > 0);

            cluster.transports.get(NodeId.of(1)).clear();
            leader.handleMessage(new InstallSnapshotResponse(
                    leader.currentTerm(), true, NodeId.of(3), snapIndex));

            for (int i = 0; i < 51; i++) {
                leader.tick();
            }

            TestTransport leaderTransport = cluster.transports.get(NodeId.of(1));
            List<AppendEntriesRequest> appendReqs =
                    leaderTransport.messagesTo(NodeId.of(3), AppendEntriesRequest.class);
            List<InstallSnapshotRequest> snapReqs =
                    leaderTransport.messagesTo(NodeId.of(3), InstallSnapshotRequest.class);

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

            leader.handleMessage(new InstallSnapshotResponse(
                    leaderTerm - 1, true, NodeId.of(3), 0L));

            assertEquals(RaftRole.LEADER, leader.role());
            assertEquals(leaderTerm, leader.currentTerm());
        }
    }

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

            for (int i = 0; i < 301; i++) {
                node.tick();
            }
            assertEquals(RaftRole.LEADER, node.role());

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

            cluster.deliverAllMessages(10);
            cluster.tickLeaderHeartbeatAndDeliver();

            long readId = leader.readIndex();
            assertTrue(readId >= 0);

            cluster.tickLeaderHeartbeatAndDeliver();

            assertTrue(leader.isReadReady(readId),
                    "Read should be ready after heartbeat confirms quorum");
        }
    }
}
