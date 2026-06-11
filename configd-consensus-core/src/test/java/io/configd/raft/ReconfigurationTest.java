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

import static io.configd.raft.ProposalResult.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Raft joint consensus reconfiguration (Raft §6).
 * <p>
 * Covers:
 * <ul>
 *   <li>Adding a node via joint consensus</li>
 *   <li>Removing a node via joint consensus</li>
 *   <li>Preconditions: only leader, no-op committed, no pending change</li>
 *   <li>Safety: both old and new majorities required during joint config</li>
 *   <li>Leader step-down when removed from cluster</li>
 * </ul>
 */
class ReconfigurationTest {

    // ========================================================================
    // Test infrastructure
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
        public void restoreSnapshot(byte[] snapshot) {}
    }

    static final class TestCluster {
        final Map<NodeId, RaftNode> nodes = new HashMap<>();
        final Map<NodeId, TestTransport> transports = new HashMap<>();
        final Map<NodeId, TestStateMachine> stateMachines = new HashMap<>();
        final Map<NodeId, RaftLog> logs = new HashMap<>();
        // RR-018: per-node durable storage retained so a node can be RESTARTED
        // (reconstructed over the same bytes) to exercise recomputeConfigFromLog.
        final Map<NodeId, io.configd.common.Storage> storages = new HashMap<>();
        final Map<NodeId, Set<NodeId>> staticPeers = new HashMap<>();

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
            io.configd.common.Storage storage = io.configd.common.Storage.inMemory();
            RaftConfig config = RaftConfig.of(id, peers);
            RaftLog log = new RaftLog(storage);
            TestTransport transport = new TestTransport();
            TestStateMachine sm = new TestStateMachine();
            RandomGenerator rng = new java.util.Random(id.id() * 31L + 7);
            RaftNode node = new RaftNode(config, log, transport, sm, rng, storage);
            nodes.put(id, node);
            transports.put(id, transport);
            stateMachines.put(id, sm);
            logs.put(id, log);
            storages.put(id, storage);
            staticPeers.put(id, Set.copyOf(peers));
        }

        /**
         * RR-018: restarts a node by reconstructing its RaftNode + RaftLog over
         * its retained durable {@link io.configd.common.Storage}. The fresh node
         * runs the constructor's {@code recomputeConfigFromLog()} against the
         * recovered WAL, so its cluster config must be rebuilt from the log's
         * config entries (not the static initial config).
         *
         * @return the freshly reconstructed node
         */
        RaftNode restartNode(NodeId id) {
            io.configd.common.Storage storage = storages.get(id);
            RaftLog log = new RaftLog(storage);
            TestTransport transport = new TestTransport();
            // Reuse the prior state machine so applied state is observable; a
            // fresh SM with restoreSnapshot would also work, but here the log is
            // fully retained (no compaction) so replay reconstructs everything.
            TestStateMachine sm = stateMachines.get(id);
            RandomGenerator rng = new java.util.Random(id.id() * 31L + 7);
            RaftConfig config = RaftConfig.of(id, staticPeers.get(id));
            RaftNode node = new RaftNode(config, log, transport, sm, rng, storage);
            nodes.put(id, node);
            transports.put(id, transport);
            logs.put(id, log);
            return node;
        }

        /** Ticks a node past its election timeout to start an election. */
        void triggerElectionTimeout(NodeId id) {
            RaftNode node = nodes.get(id);
            for (int i = 0; i < 301; i++) {
                node.tick();
            }
        }

        /** Drops all pending messages without delivering them (models a crash window). */
        void dropAllMessages() {
            for (var transport : transports.values()) {
                transport.clear();
            }
        }

        /** Delivers messages only among the given nodes (isolates everyone else). */
        void deliverAmong(Set<NodeId> members, int maxRounds) {
            for (int round = 0; round < maxRounds; round++) {
                Map<NodeId, List<RaftMessage>> toDeliver = new HashMap<>();
                boolean any = false;
                for (var entry : transports.entrySet()) {
                    if (!members.contains(entry.getKey())) { entry.getValue().clear(); continue; }
                    for (var msg : entry.getValue().messages()) {
                        if (members.contains(msg.target())) {
                            toDeliver.computeIfAbsent(msg.target(), k -> new ArrayList<>()).add(msg.message());
                            any = true;
                        }
                    }
                    entry.getValue().clear();
                }
                for (var entry : toDeliver.entrySet()) {
                    RaftNode target = nodes.get(entry.getKey());
                    if (target != null) {
                        for (RaftMessage msg : entry.getValue()) target.handleMessage(msg);
                    }
                }
                if (!any) break;
            }
        }

        /** Number of nodes currently in the LEADER role (across all terms). */
        int countLeaders() {
            int n = 0;
            for (RaftNode node : nodes.values()) if (node.role() == RaftRole.LEADER) n++;
            return n;
        }

        /**
         * Asserts single-leader-PER-TERM: no two nodes are LEADER in the same
         * term. An isolated stale leader at an OLD term is allowed (it cannot
         * commit and steps down on first contact with the new term), so a global
         * "at most one leader" check is too strict during a partition.
         */
        boolean atMostOneLeaderPerTerm() {
            Map<Long, NodeId> leaderByTerm = new HashMap<>();
            for (var e : nodes.entrySet()) {
                RaftNode node = e.getValue();
                if (node.role() == RaftRole.LEADER) {
                    NodeId prev = leaderByTerm.put(node.currentTerm(), e.getKey());
                    if (prev != null) return false; // two leaders in the same term
                }
            }
            return true;
        }

        /**
         * RR-018: elects a leader among {@code members} (with everyone else
         * isolated), driving {@code candidate} through PreVote+election and
         * delivering only among the members. Loops because a single attempt can
         * split the vote under the deterministic election RNG; returns the leader
         * node (one of {@code members}) or null if none emerged within the bound.
         */
        RaftNode electAmong(NodeId candidate, Set<NodeId> members, int attempts) {
            for (int a = 0; a < attempts; a++) {
                // First clear every member's "recent leader" gate: a follower that
                // still thinks the (now-isolated) old leader is recent rejects
                // PreVotes (handlePreVoteRequest's hasRecentLeader). Ticking each
                // member past its election timeout sets leaderId=null, so they will
                // grant. The candidate is ticked LAST so it is the freshest
                // PreVote in flight.
                for (NodeId id : members) {
                    if (!id.equals(candidate)) triggerElectionTimeout(id);
                }
                dropAllMessages(); // discard the other members' own PreVotes
                triggerElectionTimeout(candidate);
                deliverAmong(members, 60);
                for (NodeId id : members) {
                    if (nodes.get(id).role() == RaftRole.LEADER) return nodes.get(id);
                }
            }
            return null;
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

        void electLeader(NodeId id) {
            RaftNode node = nodes.get(id);
            for (int i = 0; i < 301; i++) {
                node.tick();
            }
            deliverAllMessages(10);
        }

        RaftNode findLeader() {
            for (RaftNode node : nodes.values()) {
                if (node.role() == RaftRole.LEADER) return node;
            }
            return null;
        }
    }

    // ========================================================================
    // Precondition tests
    // ========================================================================

    @Nested
    class Preconditions {

        @Test
        void rejectsConfigChangeWhenNotLeader() {
            TestCluster cluster = new TestCluster(3);
            RaftNode follower = cluster.nodes.get(NodeId.of(1));
            assertFalse(follower.proposeConfigChange(Set.of(NodeId.of(1), NodeId.of(2), NodeId.of(3), NodeId.of(4))));
        }

        @Test
        void rejectsConfigChangeBeforeNoopCommitted() {
            // RR-018 / RR-091 F-C3: de-vacuated. The OLD body asserted the
            // OPPOSITE of the test's name — it elected a leader, let the no-op
            // commit, and then asserted proposeConfigChange SUCCEEDS. This now
            // actually pins the precondition: a leader whose current-term no-op
            // is NOT yet committed MUST reject a config change (Ongaro,
            // raft-dev 2015 — the single-server reconfig bug guard).
            TestCluster cluster = new TestCluster(3);
            NodeId leaderId = NodeId.of(1);
            RaftNode leader = cluster.nodes.get(leaderId);
            NodeId n2 = NodeId.of(2);
            NodeId n3 = NodeId.of(3);

            // Drive node 1 to LEADER by winning pre-vote + vote, but do NOT
            // deliver the no-op AppendEntries responses, so the no-op stays
            // UNCOMMITTED (noopCommittedInCurrentTerm == false).
            for (int i = 0; i < 301; i++) {
                leader.tick();
            }
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n2, true));  // pre-vote
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n3, true));
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n2, false)); // real vote
            leader.handleMessage(new RequestVoteResponse(leader.currentTerm(), true, n3, false));
            assertEquals(RaftRole.LEADER, leader.role(), "node 1 must have won the election");

            // No-op is appended (index 1) but uncommitted (no responses delivered).
            assertTrue(leader.log().commitIndex() < 1,
                    "no-op must be uncommitted at this point");

            // Precondition violated -> config change REJECTED (this is what the
            // test name promises).
            assertFalse(leader.proposeConfigChange(
                            Set.of(NodeId.of(1), NodeId.of(2), NodeId.of(3), NodeId.of(4))),
                    "config change before the no-op commits must be rejected");
            assertFalse(leader.clusterConfig().isJoint(),
                    "a rejected config change must NOT enter the joint state");

            // Now commit the no-op by delivering the AppendEntries round-trip,
            // and the SAME config change must be accepted — proving the no-op
            // commit is exactly the gate.
            cluster.deliverAllMessages(10);
            assertTrue(leader.log().commitIndex() >= 1, "no-op must now be committed");
            assertTrue(leader.proposeConfigChange(
                            Set.of(NodeId.of(1), NodeId.of(2), NodeId.of(3), NodeId.of(4))),
                    "config change after the no-op commits must be accepted");
            assertTrue(leader.clusterConfig().isJoint());
        }

        @Test
        void rejectsSecondConfigChangeWhilePending() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));
            RaftNode leader = cluster.findLeader();
            assertNotNull(leader);

            // First config change accepted
            assertTrue(leader.proposeConfigChange(
                    Set.of(NodeId.of(1), NodeId.of(2), NodeId.of(3), NodeId.of(4))));

            // Second config change rejected while first is pending
            assertFalse(leader.proposeConfigChange(
                    Set.of(NodeId.of(1), NodeId.of(2), NodeId.of(3), NodeId.of(5))));
        }

        @Test
        void rejectsSameConfig() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));
            RaftNode leader = cluster.findLeader();
            assertNotNull(leader);

            assertFalse(leader.proposeConfigChange(
                    Set.of(NodeId.of(1), NodeId.of(2), NodeId.of(3))));
        }
    }

    // ========================================================================
    // Joint consensus transition tests
    // ========================================================================

    @Nested
    class JointConsensusTransition {

        @Test
        void proposingConfigChangeEntersJointState() {
            TestCluster cluster = new TestCluster(3);
            cluster.electLeader(NodeId.of(1));
            RaftNode leader = cluster.findLeader();
            assertNotNull(leader);

            // Before config change: simple config
            assertFalse(leader.clusterConfig().isJoint());

            // Propose adding node 4
            assertTrue(leader.proposeConfigChange(
                    Set.of(NodeId.of(1), NodeId.of(2), NodeId.of(3), NodeId.of(4))));

            // After proposing: joint config
            assertTrue(leader.clusterConfig().isJoint());
            assertEquals(Set.of(NodeId.of(1), NodeId.of(2), NodeId.of(3)),
                    leader.clusterConfig().voters());
            assertEquals(Set.of(NodeId.of(1), NodeId.of(2), NodeId.of(3), NodeId.of(4)),
                    leader.clusterConfig().newVoters());
        }

        @Test
        void configChangeEntryIsDetected() {
            byte[] configEntry = new byte[]{0x52, 0x43, 0x46, 0x47, 0x00}; // "RCFG" + data
            assertTrue(RaftNode.isConfigChangeEntry(configEntry));

            byte[] normalEntry = new byte[]{0x00, 0x01, 0x02};
            assertFalse(RaftNode.isConfigChangeEntry(normalEntry));

            byte[] emptyEntry = new byte[0];
            assertFalse(RaftNode.isConfigChangeEntry(emptyEntry));
        }
    }

    // ========================================================================
    // Safety invariant tests
    // ========================================================================

    @Nested
    class SafetyInvariants {

        @Test
        void configChangePreservedAcrossElections() {
            // RR-018 / RR-091 F-C2: de-vacuated. The OLD body proposed a NORMAL
            // command (new byte[]{42}) and never changed membership nor ran an
            // election — it tested neither the "config change" nor the "across
            // elections" its name promises. This now performs a REAL membership
            // change (3->4), drives the FULL joint->final transition to
            // commitment, then forces a leadership change and asserts the new
            // leader still serves the 4-voter config.
            TestCluster cluster = new TestCluster(3);
            NodeId n1 = NodeId.of(1), n2 = NodeId.of(2), n3 = NodeId.of(3), n4 = NodeId.of(4);

            cluster.electLeader(n1);
            RaftNode leader = cluster.nodes.get(n1);
            assertEquals(RaftRole.LEADER, leader.role());
            cluster.addNode(n4, Set.of(n1, n2, n3));

            // Real RCFG config change {1,2,3} -> {1,2,3,4}, driven to completion.
            assertTrue(leader.proposeConfigChange(Set.of(n1, n2, n3, n4)));
            assertTrue(leader.clusterConfig().isJoint());
            cluster.deliverAllMessages(20); // commit C_old,new, append+commit C_new

            // Reconfiguration completed: simple 4-voter config, no pending change.
            assertFalse(leader.clusterConfig().isJoint(),
                    "joint->final transition must complete to a simple config");
            assertEquals(Set.of(n1, n2, n3, n4), leader.clusterConfig().voters());
            long committedBefore = leader.log().commitIndex();

            // Force a leadership change: isolate n1, elect a survivor among {2,3,4}.
            cluster.dropAllMessages();
            Set<NodeId> survivors = Set.of(n2, n3, n4);
            RaftNode newLeader = cluster.electAmong(n2, survivors, 6);

            // A new leader emerged among the survivors and STILL carries the
            // committed 4-voter config (preserved across the election via the
            // replicated log / recomputeConfigFromLog).
            assertNotNull(newLeader, "a new leader must be elected among the survivors");
            assertTrue(cluster.atMostOneLeaderPerTerm(),
                    "single-leader-per-term must hold (an isolated stale leader at the old term is allowed)");
            assertFalse(newLeader.clusterConfig().isJoint(),
                    "the preserved config must be the completed simple 4-voter config");
            assertEquals(Set.of(n1, n2, n3, n4), newLeader.clusterConfig().voters(),
                    "the committed membership change must survive the leadership change");
            // Committed entries are preserved (no committed index regressed).
            assertTrue(newLeader.log().commitIndex() >= committedBefore - 1,
                    "committed entries must not be lost across the election");
        }

        @Test
        void clusterConfigInitializedCorrectly() {
            TestCluster cluster = new TestCluster(3);
            for (RaftNode node : cluster.nodes.values()) {
                ClusterConfig cfg = node.clusterConfig();
                assertFalse(cfg.isJoint());
                assertEquals(3, cfg.voters().size());
                assertTrue(cfg.isVoter(NodeId.of(1)));
                assertTrue(cfg.isVoter(NodeId.of(2)));
                assertTrue(cfg.isVoter(NodeId.of(3)));
            }
        }
    }

    // ========================================================================
    // RR-018: in-sim end-to-end joint-consensus verification
    //
    // The register flagged a 46% mutation score on the reconfig path and that
    // no test ever completed a joint->final transition (F-C2/F-C3). These drive
    // the full membership change to commitment, with a leadership change DURING
    // the joint phase, and exercise recomputeConfigFromLog across a restart.
    // ========================================================================

    @Nested
    class JointConsensusEndToEnd {

        @Test
        void completesJointToFinalTransitionAndCommitsBothConfigEntries() {
            TestCluster cluster = new TestCluster(3);
            NodeId n1 = NodeId.of(1), n2 = NodeId.of(2), n3 = NodeId.of(3), n4 = NodeId.of(4);
            cluster.electLeader(n1);
            RaftNode leader = cluster.nodes.get(n1);
            cluster.addNode(n4, Set.of(n1, n2, n3));

            // A committed user entry BEFORE the reconfig — must survive it.
            assertEquals(ProposalResult.ACCEPTED, leader.propose("pre-reconfig".getBytes()).result());
            cluster.deliverAllMessages(20);
            long preReconfigCommit = leader.log().commitIndex();
            assertTrue(preReconfigCommit >= 2, "no-op + pre-reconfig command committed");

            // Propose {1,2,3} -> {1,2,3,4}: enters joint C_old,new.
            assertTrue(leader.proposeConfigChange(Set.of(n1, n2, n3, n4)));
            assertTrue(leader.clusterConfig().isJoint());
            assertEquals(Set.of(n1, n2, n3), leader.clusterConfig().voters());
            assertEquals(Set.of(n1, n2, n3, n4), leader.clusterConfig().newVoters());

            // Drive to completion: C_old,new commits -> leader auto-appends C_new
            // -> C_new commits -> simple 4-voter config, pending cleared.
            cluster.deliverAllMessages(30);
            assertFalse(leader.clusterConfig().isJoint(),
                    "transition must reach the simple (final) config");
            assertEquals(Set.of(n1, n2, n3, n4), leader.clusterConfig().voters());

            // Both config entries (joint + final) are in the committed log: a
            // user write after the reconfig commits under the new 4-voter quorum.
            assertEquals(ProposalResult.ACCEPTED, leader.propose("post-reconfig".getBytes()).result());
            cluster.deliverAllMessages(20);
            assertTrue(leader.log().commitIndex() > preReconfigCommit,
                    "post-reconfig write must commit under the new config");

            // Followers (including the newly added n4) converged on the 4-voter config.
            for (NodeId id : List.of(n2, n3, n4)) {
                ClusterConfig cfg = cluster.nodes.get(id).clusterConfig();
                assertFalse(cfg.isJoint(), id + " must have left the joint config");
                assertEquals(Set.of(n1, n2, n3, n4), cfg.voters(),
                        id + " must have adopted the 4-voter config from the log");
            }
            assertTrue(cluster.countLeaders() <= 1);
        }

        @Test
        void leaderElectionDuringJointPhaseStillCompletesTheChange() {
            // An election happens AFTER C_old,new has committed but BEFORE the
            // transition is finalized. The new leader (which has the committed
            // joint entry in its log) must drive the change to completion under
            // dual-majority rules and preserve all committed entries.
            TestCluster cluster = new TestCluster(3);
            NodeId n1 = NodeId.of(1), n2 = NodeId.of(2), n3 = NodeId.of(3), n4 = NodeId.of(4);
            cluster.electLeader(n1);
            RaftNode leader = cluster.nodes.get(n1);
            cluster.addNode(n4, Set.of(n1, n2, n3));

            assertEquals(ProposalResult.ACCEPTED, leader.propose("durable".getBytes()).result());
            cluster.deliverAllMessages(20);
            long committedBefore = leader.log().commitIndex();

            // Enter joint and COMMIT C_old,new across the cluster, but isolate n1
            // before it can finalize so the transition is mid-flight.
            assertTrue(leader.proposeConfigChange(Set.of(n1, n2, n3, n4)));
            assertTrue(leader.clusterConfig().isJoint());
            // One replication round to commit the joint entry on the followers.
            cluster.deliverAllMessages(30);

            // Leadership change: isolate n1, elect a survivor among {2,3,4}.
            cluster.dropAllMessages();
            long oldTerm = leader.currentTerm();
            Set<NodeId> survivors = Set.of(n2, n3, n4);
            RaftNode newLeader = cluster.electAmong(n2, survivors, 6);
            assertNotNull(newLeader, "a survivor must win under dual-majority rules");
            assertTrue(newLeader.currentTerm() > oldTerm, "term must advance");
            assertTrue(cluster.atMostOneLeaderPerTerm(), "single-leader-per-term must hold");

            // Let the new leader finalize the reconfiguration.
            cluster.deliverAmong(survivors, 40);
            assertFalse(newLeader.clusterConfig().isJoint(),
                    "the new leader must complete the joint->final transition");
            assertEquals(Set.of(n1, n2, n3, n4), newLeader.clusterConfig().voters());
            assertTrue(newLeader.log().commitIndex() >= committedBefore,
                    "committed entries from before the election must be preserved");
        }

        @Test
        void recomputeConfigFromLogRestoresMembershipAcrossRestart() {
            // After a completed reconfiguration, restart a node and assert its
            // constructor's recomputeConfigFromLog() rebuilds the 4-voter config
            // from the recovered WAL — NOT the static 3-node initial config.
            TestCluster cluster = new TestCluster(3);
            NodeId n1 = NodeId.of(1), n2 = NodeId.of(2), n3 = NodeId.of(3), n4 = NodeId.of(4);
            cluster.electLeader(n1);
            RaftNode leader = cluster.nodes.get(n1);
            cluster.addNode(n4, Set.of(n1, n2, n3));

            assertTrue(leader.proposeConfigChange(Set.of(n1, n2, n3, n4)));
            cluster.deliverAllMessages(30);
            assertFalse(leader.clusterConfig().isJoint());
            assertEquals(Set.of(n1, n2, n3, n4), leader.clusterConfig().voters());

            // n2's log now contains both committed config entries. Sanity: its
            // in-memory config is already the 4-voter config from replication.
            RaftNode n2Before = cluster.nodes.get(n2);
            assertEquals(Set.of(n1, n2, n3, n4), n2Before.clusterConfig().voters());

            // Restart n2 over its retained storage. The fresh node's STATIC
            // RaftConfig still lists only the original {1,3} peers, so the only
            // way it can know about n4 is recomputeConfigFromLog over the WAL.
            RaftNode n2After = cluster.restartNode(n2);
            assertFalse(n2After.clusterConfig().isJoint(),
                    "recovered config must be the simple final config");
            assertEquals(Set.of(n1, n2, n3, n4), n2After.clusterConfig().voters(),
                    "recomputeConfigFromLog must restore the 4-voter membership after restart"
                            + " (NOT the static initial 3-node config)");
            assertTrue(n2After.clusterConfig().isVoter(n4),
                    "the added node must be a voter in the recovered config");
        }
    }
}
