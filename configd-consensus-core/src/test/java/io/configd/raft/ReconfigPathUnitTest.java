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

class ReconfigPathUnitTest {

    private static final NodeId N1 = NodeId.of(1);
    private static final NodeId N2 = NodeId.of(2);
    private static final NodeId N3 = NodeId.of(3);
    private static final NodeId N4 = NodeId.of(4);

    static final class CapturingTransport implements RaftTransport {
        final List<RaftMessage> sent = new ArrayList<>();
        @Override public void send(NodeId target, RaftMessage message) { sent.add(message); }
    }

    static final class CountingStateMachine implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    static final class RoutingCluster {
        final Map<NodeId, RaftNode> nodes = new HashMap<>();
        final Map<NodeId, RoutingTransport> transports = new HashMap<>();

        final class RoutingTransport implements RaftTransport {
            final List<Sent> sent = new ArrayList<>();
            record Sent(NodeId target, RaftMessage message) {}
            @Override public void send(NodeId target, RaftMessage message) { sent.add(new Sent(target, message)); }
        }

        RoutingCluster(int size) {
            List<NodeId> all = new ArrayList<>();
            for (int i = 1; i <= size; i++) all.add(NodeId.of(i));
            for (NodeId id : all) {
                Set<NodeId> peers = new HashSet<>(all);
                peers.remove(id);
                build(id, peers);
            }
        }

        void addNode(NodeId id, Set<NodeId> peers) { build(id, peers); }

        private void build(NodeId id, Set<NodeId> peers) {
            RoutingTransport t = new RoutingTransport();
            RaftConfig config = RaftConfig.of(id, peers);
            RaftNode node = new RaftNode(config, new RaftLog(), t, new CountingStateMachine(),
                    new java.util.Random(id.id() * 31L + 7));
            nodes.put(id, node);
            transports.put(id, t);
        }

        void electLeader(NodeId id) {
            RaftNode node = nodes.get(id);
            for (int i = 0; i < 301; i++) node.tick();
            deliverAll(10);
        }

        void deliverAll(int rounds) {
            for (int r = 0; r < rounds; r++) {
                Map<NodeId, List<RaftMessage>> box = new HashMap<>();
                boolean any = false;
                for (var e : transports.entrySet()) {
                    for (var s : e.getValue().sent) {
                        box.computeIfAbsent(s.target(), k -> new ArrayList<>()).add(s.message());
                        any = true;
                    }
                    e.getValue().sent.clear();
                }
                if (!any) break;
                for (var e : box.entrySet()) {
                    RaftNode target = nodes.get(e.getKey());
                    if (target != null) for (RaftMessage m : e.getValue()) target.handleMessage(m);
                }
            }
        }

        RaftNode findLeader() {
            return nodes.values().stream().filter(n -> n.role() == RaftRole.LEADER).findFirst().orElse(null);
        }
    }


    @Nested
    class Codec {

        @Test
        void recognizesRcfgMagicAndRejectsOthers() {
            byte[] notConfig = new byte[]{1, 2, 3, 4, 5};
            byte[] tooShort = new byte[]{0x52, 0x43, 0x46};
            assertFalse(RaftNode.isConfigChangeEntry(notConfig));
            assertFalse(RaftNode.isConfigChangeEntry(tooShort));
            assertFalse(RaftNode.isConfigChangeEntry(null));
            byte[] exactMagic = new byte[]{0x52, 0x43, 0x46, 0x47};
            assertTrue(RaftNode.isConfigChangeEntry(exactMagic));
            byte[] wrongMagic = new byte[]{0x52, 0x43, 0x46, 0x48};
            assertFalse(RaftNode.isConfigChangeEntry(wrongMagic));
        }

        @Test
        void deserializeRejectsTruncatedEntry() {
            // magic + isJoint(0) + oldCount(2) but no voter ids -> BufferUnderflow,
            // mapped to IllegalArgumentException.
            byte[] truncated = new byte[]{0x52, 0x43, 0x46, 0x47, 0, 0, 0, 0, 2};
            assertThrows(IllegalArgumentException.class,
                    () -> RaftNode.deserializeConfigChange(truncated));
        }

        @Test
        void deserializeRejectsAbsurdVoterCount() {
            // magic + isJoint(0) + oldCount = 1000 (> 255) must throw: the voter
            // count is validated against a sane upper bound.
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(9);
            buf.put(new byte[]{0x52, 0x43, 0x46, 0x47});
            buf.put((byte) 0);
            buf.putInt(1000);
            assertThrows(IllegalArgumentException.class,
                    () -> RaftNode.deserializeConfigChange(buf.array()));
        }
    }


    @Nested
    class ProposePreconditions {

        @Test
        void nonLeaderCannotPropose() {
            RaftConfig config = RaftConfig.of(N1, Set.of(N2, N3));
            RaftNode follower = new RaftNode(config, new RaftLog(), new CapturingTransport(),
                    new CountingStateMachine(), new java.util.Random(1));
            assertFalse(follower.proposeConfigChange(Set.of(N1, N2)));
        }

        @Test
        void rejectsSameVoterSet() {
            // Single-node leader: no-op commits instantly, so the no-op precondition
            // is satisfied. Proposing the SAME voter set must still be rejected.
            RaftConfig config = RaftConfig.of(N1, Set.of());
            RaftNode node = new RaftNode(config, new RaftLog(), new CapturingTransport(),
                    new CountingStateMachine(), new java.util.Random(42));
            for (int i = 0; i < 301; i++) node.tick();
            assertEquals(RaftRole.LEADER, node.role());
            assertFalse(node.proposeConfigChange(Set.of(N1)));
        }
    }


    @Nested
    class Lifecycle {

        @Test
        void enteringJointThenReachingFinalSimpleConfig() {
            RoutingCluster cluster = new RoutingCluster(3);
            cluster.electLeader(N1);
            RaftNode leader = cluster.nodes.get(N1);
            assertEquals(RaftRole.LEADER, leader.role());
            cluster.addNode(N4, Set.of(N1, N2, N3));

            cluster.deliverAll(20);
            assertTrue(leader.log().commitIndex() >= 1, "no-op must commit");

            assertTrue(leader.proposeConfigChange(Set.of(N1, N2, N3, N4)));
            // proposeConfigChange enters the joint configuration immediately, in-memory.
            assertTrue(leader.clusterConfig().isJoint());
            assertEquals(Set.of(N1, N2, N3), leader.clusterConfig().voters());
            assertEquals(Set.of(N1, N2, N3, N4), leader.clusterConfig().newVoters());

            cluster.deliverAll(40);
            assertFalse(leader.clusterConfig().isJoint(), "must reach final simple config");
            assertEquals(Set.of(N1, N2, N3, N4), leader.clusterConfig().voters());
            // A subsequent distinct change is accepted -> configChangePending cleared.
            assertTrue(leader.proposeConfigChange(Set.of(N1, N2, N3)));
        }

        @Test
        void leaderRemovingItselfStepsDownWhenFinalConfigCommits() {
            RoutingCluster cluster = new RoutingCluster(3);
            cluster.electLeader(N1);
            RaftNode leader = cluster.nodes.get(N1);
            assertEquals(RaftRole.LEADER, leader.role());
            cluster.deliverAll(20);
            assertTrue(leader.log().commitIndex() >= 1);

            assertTrue(leader.proposeConfigChange(Set.of(N2, N3)));
            assertTrue(leader.clusterConfig().isJoint());
            cluster.deliverAll(60);
            assertFalse(leader.clusterConfig().isVoter(N1),
                    "the removed node must no longer be a voter in the final config");
            assertEquals(RaftRole.FOLLOWER, leader.role(),
                    "a leader removed from the config must step down");
        }
    }

}
