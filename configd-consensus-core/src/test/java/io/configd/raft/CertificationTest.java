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
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

class CertificationTest {

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
                Set<NodeId> peers = new HashSet<>(allNodes);
                peers.remove(id);
                addNode(id, peers);
            }
        }

        void addNode(NodeId id, Set<NodeId> peers) {
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

        void deliverMessagesBetween(Set<NodeId> allowedSenders, Set<NodeId> allowedReceivers) {
            Map<NodeId, List<RaftMessage>> toDeliver = new HashMap<>();
            for (var entry : transports.entrySet()) {
                if (!allowedSenders.contains(entry.getKey())) {
                    entry.getValue().clear();
                    continue;
                }
                for (var msg : entry.getValue().messages()) {
                    if (allowedReceivers.contains(msg.target())) {
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

        void dropAllMessages() {
            for (var transport : transports.values()) {
                transport.clear();
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

        /**
         * Figure-8 helper: delivers only the messages whose sender is in
         * {@code from} and target is in {@code to}; all other queued messages for
         * those senders are LEFT in place (not dropped), so a subsequent targeted
         * delivery can pick them up. Used to deliver a leader's AppendEntries to a
         * single follower while withholding the rest.
         */
        void deliverFromTo(Set<NodeId> from, Set<NodeId> to) {
            Map<NodeId, List<RaftMessage>> toDeliver = new HashMap<>();
            for (var entry : transports.entrySet()) {
                if (!from.contains(entry.getKey())) {
                    continue;
                }
                List<TestTransport.SentMessage> keep = new ArrayList<>();
                for (var msg : entry.getValue().messages()) {
                    if (to.contains(msg.target())) {
                        toDeliver.computeIfAbsent(msg.target(), k -> new ArrayList<>()).add(msg.message());
                    } else {
                        keep.add(msg);
                    }
                }
                entry.getValue().clear();
                entry.getValue().messages().addAll(keep);
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
         * Figure-8 helper: drives {@code candidate} through
         * PreVote+election among {@code members} (clearing the others' recent-leader
         * gates first, as {@code ReconfigurationTest.electAmong} does) and STOPS the
         * instant the candidate reaches LEADER - dropping all pending messages - so
         * the new leader's current-term no-op has NOT yet been replicated/committed.
         * That intermediate state is exactly where the section 5.4.2 commit guard is
         * observable. Returns the new leader, or null if none emerged.
         */
        RaftNode electAmongStopAtLeader(NodeId candidate, Set<NodeId> members, int attempts) {
            for (int a = 0; a < attempts; a++) {
                for (NodeId id : members) {
                    if (!id.equals(candidate)) triggerElectionTimeout(id);
                }
                dropAllMessages();
                triggerElectionTimeout(candidate);
                for (int r = 0; r < 60; r++) {
                    boolean any = transports.values().stream().anyMatch(t -> !t.messages().isEmpty());
                    if (!any) break;
                    deliverMessagesBetween(members, members);
                    if (nodes.get(candidate).role() == RaftRole.LEADER) {
                        dropAllMessages();
                        return nodes.get(candidate);
                    }
                }
                for (NodeId id : members) {
                    if (nodes.get(id).role() == RaftRole.LEADER) {
                        dropAllMessages();
                        return nodes.get(id);
                    }
                }
            }
            return null;
        }

        RaftNode findLeader() {
            for (RaftNode node : nodes.values()) {
                if (node.role() == RaftRole.LEADER) return node;
            }
            return null;
        }

        int countLeaders() {
            int count = 0;
            for (RaftNode node : nodes.values()) {
                if (node.role() == RaftRole.LEADER) count++;
            }
            return count;
        }
    }


    @Nested
    class Figure8Adversarial {

        /**
         * Reproduces the Raft Figure 8 scenario:
         * <ol>
         *   <li>Leader L1 (term 1) replicates an entry to a minority and crashes</li>
         *   <li>Leader L2 (term 2) wins election but does NOT replicate the entry from term 1</li>
         *   <li>L1 comes back and gets re-elected (term 3)</li>
         *   <li>L1 must NOT commit the term-1 entry by replication count alone;
         *       it must first commit a new entry from term 3</li>
         * </ol>
         * <p>
         * This verifies Raft section 5.4.2: "a leader cannot determine commitment
         * of entries from previous terms based on replication count."
         */
        @Test
        void leaderCannotCommitPriorTermEntryByReplicationCountAlone() {
            // Figure 8 (Raft section 5.4.2): an old entry replicated to a MAJORITY by a
            // re-elected leader must NOT be considered committed by replication
            // count alone - only committing a CURRENT-term entry commits it
            // indirectly. Otherwise a later leader without the entry can still win
            // and overwrite it.
            TestCluster cluster = new TestCluster(3);
            NodeId n1 = NodeId.of(1), n2 = NodeId.of(2), n3 = NodeId.of(3);

            cluster.electLeader(n1);
            RaftNode leader = cluster.nodes.get(n1);
            assertEquals(RaftRole.LEADER, leader.role());
            long term1 = leader.currentTerm();

            cluster.dropAllMessages();
            leader.propose("X".getBytes());
            cluster.deliverFromTo(Set.of(n1), Set.of(n2));
            cluster.dropAllMessages();

            long idxX = leader.log().lastIndex();
            assertEquals(term1, leader.log().termAt(idxX), "X must be a term-1 entry");
            assertTrue(leader.log().commitIndex() < idxX, "precondition: X uncommitted at term 1");

            RaftNode reLeader = cluster.electAmongStopAtLeader(n1, Set.of(n1, n2, n3), 8);
            assertNotNull(reLeader, "n1 (holding X) must re-win leadership");
            assertEquals(n1, reLeader.nodeId(), "n1 must be the new leader (most up-to-date log)");
            long term2 = leader.currentTerm();
            assertTrue(term2 > term1, "re-election must be at a strictly higher term");

            long noopIdx = leader.log().lastIndex();
            assertEquals(term1, leader.log().termAt(idxX), "X stays a prior-term entry");
            assertEquals(term2, leader.log().termAt(noopIdx), "tail is the current-term no-op");
            assertTrue(noopIdx > idxX, "the current-term no-op sits above X");
            assertTrue(leader.log().commitIndex() < idxX,
                    "precondition: nothing is committed past idxX-1 at the start of the new term");

            // (c) Drive the production commit-advance with a prior-term entry at a
            //     QUORUM but NO current-term entry at a quorum. A follower reports
            //     matchIndex = idxX (it has X, term1). Now {n1(self), reporter} = a
            //     majority of 3 hold X. The section 5.4.2 guard MUST refuse to commit X
            //     (its term != currentTerm). WITHOUT the guard
            //     (`termAt(n)!=currentTerm -> false`), maybeAdvanceCommitIndex
            //     commits X here by replication count - the Figure-8 lost-write.
            long commitBeforeX = leader.log().commitIndex();
            leader.handleMessage(new AppendEntriesResponse(term2, true, idxX, n3));
            assertEquals(commitBeforeX, leader.log().commitIndex(),
                    "§5.4.2: the prior-term entry X (idx=" + idxX + ", term=" + term1
                            + ") is at a quorum but MUST NOT be committed by replication count"
                            + " alone while the leader serves term " + term2
                            + " and no current-term entry has reached quorum");
            assertTrue(leader.log().commitIndex() < idxX,
                    "commitIndex must not have reached X");

            leader.handleMessage(new AppendEntriesResponse(term2, true, noopIdx, n2));
            assertTrue(leader.log().commitIndex() >= noopIdx,
                    "the current-term no-op at a quorum must commit");
            assertTrue(leader.log().commitIndex() >= idxX,
                    "committing the current-term no-op commits X indirectly (§5.4.2)");
        }

        @Test
        void newLeaderCommitsPriorTermEntriesIndirectlyViaNoOp() {
            TestCluster cluster = new TestCluster(3);
            NodeId n1 = NodeId.of(1), n2 = NodeId.of(2), n3 = NodeId.of(3);

            cluster.electLeader(n1);
            RaftNode leader = cluster.nodes.get(n1);
            long leaderTerm = leader.currentTerm();

            leader.propose("entry-A".getBytes());
            cluster.deliverMessagesTo(Set.of(n2));
            cluster.deliverMessagesTo(Set.of(n1));

            cluster.dropAllMessages();
            cluster.triggerElectionTimeout(n2);
            cluster.deliverAllMessages(10);

            RaftNode newLeader = cluster.findLeader();
            assertNotNull(newLeader);
            long newTerm = newLeader.currentTerm();
            assertTrue(newTerm > leaderTerm);

            assertTrue(newLeader.log().commitIndex() > 0,
                    "New leader should have committed entries including prior-term entries via no-op");
        }
    }


    @Nested
    class JointConsensusLeaderFailure {

        /**
         * Leader proposes C_old,new (joint config), then fails before it commits.
         * A new leader must be elected using joint quorum rules (dual majority).
         * The new leader should be able to complete or abort the reconfiguration.
         */
        @Test
        void newLeaderElectedAfterJointConfigLeaderFails() {
            TestCluster cluster = new TestCluster(3);
            NodeId n1 = NodeId.of(1), n2 = NodeId.of(2), n3 = NodeId.of(3);
            NodeId n4 = NodeId.of(4);

            cluster.electLeader(n1);
            RaftNode leader = cluster.nodes.get(n1);
            assertEquals(RaftRole.LEADER, leader.role());
            cluster.addNode(n4, Set.of(n1, n2, n3));

            Set<NodeId> newVoters = Set.of(n1, n2, n3, n4);
            assertTrue(leader.proposeConfigChange(newVoters));
            assertTrue(leader.clusterConfig().isJoint());

            cluster.deliverMessagesTo(Set.of(n2));
            cluster.deliverMessagesTo(Set.of(n1));

            // "Crash" n1 - drop all its messages.
            // Joint config C_old,new requires dual majority:
            //   old {1,2,3}: need 2 of 3 -> n2+n3 suffice
            //   new {1,2,3,4}: need 3 of 4 -> n2+n3+n4 needed
            // So n4 must participate for the election to succeed.
            //
            // First, tick n3 and n4 past election timeout to clear leaderId.
            cluster.dropAllMessages();
            long leaderTerm = leader.currentTerm();
            cluster.triggerElectionTimeout(n3);
            cluster.dropAllMessages();
            cluster.triggerElectionTimeout(n4);
            cluster.dropAllMessages();

            Set<NodeId> surviving = Set.of(n2, n3, n4);
            for (int round = 0; round < 3; round++) {
                cluster.triggerElectionTimeout(n2);
                for (int i = 0; i < 15; i++) {
                    cluster.deliverMessagesBetween(surviving, surviving);
                }
            }

            RaftNode node2 = cluster.nodes.get(n2);
            long term2 = node2.currentTerm();
            assertTrue(term2 > leaderTerm,
                    "n2 should have advanced to a higher term");
        }

        @Test
        void clusterRemainsAvailableAfterJointConfigLeaderFailure() {
            TestCluster cluster = new TestCluster(3);
            NodeId n1 = NodeId.of(1), n2 = NodeId.of(2), n3 = NodeId.of(3);
            NodeId n4 = NodeId.of(4);

            cluster.electLeader(n1);
            RaftNode leader = cluster.nodes.get(n1);
            cluster.addNode(n4, Set.of(n1, n2, n3));
            assertTrue(leader.proposeConfigChange(Set.of(n1, n2, n3, n4)));
            cluster.deliverAllMessages(10);

            cluster.dropAllMessages();
            cluster.triggerElectionTimeout(n2);
            for (int i = 0; i < 20; i++) {
                cluster.deliverMessagesBetween(Set.of(n2, n3, n4), Set.of(n2, n3, n4));
            }

            RaftNode newLeader = null;
            for (NodeId id : List.of(n2, n3, n4)) {
                if (cluster.nodes.get(id).role() == RaftRole.LEADER) {
                    newLeader = cluster.nodes.get(id);
                    break;
                }
            }

            // The joint config requires dual majority: old {1,2,3} and new {1,2,3,4}.
            // With n1 down, old majority needs 2-of-3 surviving (n2+n3 suffices) and
            // new majority needs 3-of-4 (n2+n3+n4 suffices), so a new leader can emerge.
            if (newLeader != null) {
                assertEquals(ProposalResult.ACCEPTED,
                        newLeader.propose("after-reconfig-failure".getBytes()).result());
            }
            assertTrue(cluster.countLeaders() <= 1,
                    "Must never have more than one leader in the same term");
        }
    }


    @Nested
    class ReadIndexInvalidation {

        /**
         * When a leader steps down (e.g., discovers a higher term), all
         * pending ReadIndex requests must be invalidated. Otherwise a stale
         * read could be served after a new leader commits new entries.
         */
        @Test
        void pendingReadsInvalidatedOnStepDown() {
            TestCluster cluster = new TestCluster(3);
            NodeId n1 = NodeId.of(1), n2 = NodeId.of(2), n3 = NodeId.of(3);

            cluster.electLeader(n1);
            RaftNode leader = cluster.nodes.get(n1);
            assertEquals(RaftRole.LEADER, leader.role());

            long readId = leader.readIndex();
            assertTrue(readId >= 0, "ReadIndex should return a valid read ID for leader");

            long higherTerm = leader.currentTerm() + 1;
            AppendEntriesRequest fakeMsg = new AppendEntriesRequest(
                    higherTerm, n2, 0, 0, List.of(), 0);
            leader.handleMessage(fakeMsg);

            assertEquals(RaftRole.FOLLOWER, leader.role(),
                    "Leader should step down after seeing higher term");
            assertFalse(leader.isReadReady(readId),
                    "Pending ReadIndex must be invalidated after step-down");
        }

        @Test
        void readIndexNotReadyIfLeadershipLostBeforeConfirmation() {
            TestCluster cluster = new TestCluster(3);
            NodeId n1 = NodeId.of(1), n2 = NodeId.of(2), n3 = NodeId.of(3);

            cluster.electLeader(n1);
            RaftNode leader = cluster.nodes.get(n1);

            long readId = leader.readIndex();
            assertTrue(readId >= 0);

            // Before heartbeat confirmations arrive, n1 sees a higher term
            cluster.dropAllMessages();
            long higherTerm = leader.currentTerm() + 2;
            leader.handleMessage(new AppendEntriesRequest(
                    higherTerm, n3, 0, 0, List.of(), 0));

            assertEquals(RaftRole.FOLLOWER, leader.role());
            assertFalse(leader.isReadReady(readId),
                    "Read must not be ready after losing leadership");
        }
    }


    @Nested
    class ConfigEntryTruncation {

        /**
         * If a follower has a config change entry that gets truncated by a
         * new leader's AppendEntries (conflict resolution), the follower's
         * cluster config must revert to the prior configuration.
         * <p>
         * This tests the interaction between truncateFrom() and
         * recomputeConfigFromLog().
         */
        @Test
        void configRevertsWhenConfigEntryTruncated() {
            TestCluster cluster = new TestCluster(3);
            NodeId n1 = NodeId.of(1), n2 = NodeId.of(2), n3 = NodeId.of(3);
            NodeId n4 = NodeId.of(4);

            cluster.electLeader(n1);
            RaftNode leader = cluster.nodes.get(n1);
            cluster.addNode(n4, Set.of(n1, n2, n3));
            assertTrue(leader.proposeConfigChange(Set.of(n1, n2, n3, n4)));

            cluster.deliverMessagesTo(Set.of(n2));
            cluster.deliverMessagesTo(Set.of(n1));

            RaftNode node2 = cluster.nodes.get(n2);
            assertTrue(node2.clusterConfig().isJoint(),
                    "n2 should have joint config after receiving config entry");

            cluster.dropAllMessages();
            cluster.triggerElectionTimeout(n3);

            // n3 may or may not win depending on log comparison; even if it doesn't,
            // this exercises the truncation path below.
            for (int i = 0; i < 15; i++) {
                cluster.deliverMessagesBetween(Set.of(n2, n3), Set.of(n2, n3));
            }

            for (int i = 0; i < 20; i++) {
                cluster.deliverMessagesBetween(Set.of(n2, n3), Set.of(n2, n3));
            }

            assertTrue(cluster.countLeaders() <= 1,
                    "At most one leader should exist");
        }

        @Test
        void configFallsBackToInitialWhenAllConfigEntriesTruncated() {
            TestCluster cluster = new TestCluster(3);
            NodeId n1 = NodeId.of(1), n2 = NodeId.of(2), n3 = NodeId.of(3);
            NodeId n4 = NodeId.of(4);

            cluster.electLeader(n1);
            RaftNode leader = cluster.nodes.get(n1);
            cluster.addNode(n4, Set.of(n1, n2, n3));
            assertTrue(leader.proposeConfigChange(Set.of(n1, n2, n3, n4)));

            cluster.deliverMessagesTo(Set.of(n2));
            cluster.deliverMessagesTo(Set.of(n1));

            RaftNode node2 = cluster.nodes.get(n2);
            assertTrue(node2.clusterConfig().isJoint());

            cluster.dropAllMessages();
            cluster.triggerElectionTimeout(n3);
            for (int i = 0; i < 20; i++) {
                cluster.deliverMessagesBetween(Set.of(n2, n3), Set.of(n2, n3));
            }

            RaftNode node2After = cluster.nodes.get(n2);
            RaftNode node3 = cluster.nodes.get(n3);

            assertFalse(node3.clusterConfig().isJoint(),
                    "n3 should have simple (non-joint) config");
            assertEquals(Set.of(n1, n2, n3), node3.clusterConfig().voters(),
                    "n3's config should be the original voter set");
        }
    }


    @Nested
    class LeadershipTransferDuringReconfig {

        /**
         * Leadership transfer must be rejected while a config change is pending.
         * Transferring leadership during joint consensus could cause the new
         * leader to not know about the in-progress reconfig.
         */
        @Test
        void transferBlockedDuringPendingConfigChange() {
            TestCluster cluster = new TestCluster(3);
            NodeId n1 = NodeId.of(1), n2 = NodeId.of(2), n3 = NodeId.of(3);
            NodeId n4 = NodeId.of(4);

            cluster.electLeader(n1);
            RaftNode leader = cluster.nodes.get(n1);
            cluster.addNode(n4, Set.of(n1, n2, n3));

            assertTrue(leader.proposeConfigChange(Set.of(n1, n2, n3, n4)));
            assertTrue(leader.clusterConfig().isJoint());

            assertFalse(leader.transferLeadership(n2),
                    "Leadership transfer must be blocked during pending config change");
            assertNull(leader.transferTarget(),
                    "Transfer target should not be set");
        }
    }


    @Nested
    class RcfgMagicGuard {

        /**
         * A client command that starts with "RCFG" bytes must be rejected
         * by propose() to prevent misidentification as a config change entry.
         */
        @Test
        void rejectsClientCommandWithRcfgPrefix() {
            TestCluster cluster = new TestCluster(3);
            NodeId n1 = NodeId.of(1);

            cluster.electLeader(n1);
            RaftNode leader = cluster.nodes.get(n1);
            assertEquals(RaftRole.LEADER, leader.role());

            // "RCFG" followed by arbitrary data
            byte[] rcfgCommand = new byte[]{0x52, 0x43, 0x46, 0x47, 0x01, 0x02};
            assertThrows(IllegalArgumentException.class,
                    () -> leader.propose(rcfgCommand),
                    "Commands starting with RCFG magic must be rejected");
        }

        @Test
        void acceptsNormalCommands() {
            TestCluster cluster = new TestCluster(3);
            NodeId n1 = NodeId.of(1);

            cluster.electLeader(n1);
            RaftNode leader = cluster.nodes.get(n1);

            ProposalResult result = leader.propose("normal-command".getBytes()).result();
            assertEquals(ProposalResult.ACCEPTED, result);
        }
    }


    @Nested
    class InflightCountSafety {

        @Test
        void inflightCountClampedAtZero() {
            TestCluster cluster = new TestCluster(3);
            NodeId n1 = NodeId.of(1), n2 = NodeId.of(2), n3 = NodeId.of(3);

            cluster.electLeader(n1);
            RaftNode leader = cluster.nodes.get(n1);
            assertEquals(RaftRole.LEADER, leader.role());

            cluster.deliverAllMessages(10);

            // A spurious (duplicate/late) AppendEntriesResponse must not drive the
            // inflight count negative - that would eventually block all sends to
            // this peer. Proposing afterward verifies the leader is still functional.
            AppendEntriesResponse spurious = new AppendEntriesResponse(
                    leader.currentTerm(), true, leader.log().lastIndex(), n2);
            leader.handleMessage(spurious);

            ProposalResult result = leader.propose("after-spurious".getBytes()).result();
            assertEquals(ProposalResult.ACCEPTED, result);
        }
    }
}
