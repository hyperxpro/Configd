package io.configd.replication;

import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.raft.AppendEntriesRequest;
import io.configd.raft.AppendEntriesResponse;
import io.configd.raft.ProposalResult;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.raft.RaftTransport;
import io.configd.raft.StateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MultiRaftDriver} — multi-group management,
 * tick propagation, and message routing.
 */
class MultiRaftDriverTest {

    // ========================================================================
    // Test infrastructure
    // ========================================================================

    static final class TestTransport implements RaftTransport {
        private final List<SentMessage> messages = new ArrayList<>();

        record SentMessage(NodeId target, io.configd.raft.RaftMessage message) {}

        @Override
        public void send(NodeId target, io.configd.raft.RaftMessage message) {
            messages.add(new SentMessage(target, message));
        }

        List<SentMessage> messages() { return messages; }
        void clear() { messages.clear(); }
    }

    static final class TestStateMachine implements StateMachine {
        @Override public void apply(long index, long term, byte[] command) {}
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) {}
    }

    static final class TestClock implements Clock {
        private long millis = 0;
        private long nanos = 0;

        @Override public long currentTimeMillis() { return millis; }
        @Override public long nanoTime() { return nanos; }

        void advanceMillis(long ms) { millis += ms; nanos += ms * 1_000_000; }
    }

    private static final NodeId LOCAL = NodeId.of(1);
    private TestClock clock;
    private MultiRaftDriver driver;

    /** Creates a single-node RaftNode (no peers) that becomes leader immediately. */
    private RaftNode createSingleNodeRaft(NodeId id) {
        RaftConfig config = RaftConfig.of(id, Set.of());
        RaftLog log = new RaftLog();
        TestTransport transport = new TestTransport();
        TestStateMachine sm = new TestStateMachine();
        RandomGenerator rng = new java.util.Random(42);
        return new RaftNode(config, log, transport, sm, rng);
    }

    /** Creates a RaftNode with the given peers. */
    private RaftNode createRaftWithPeers(NodeId id, Set<NodeId> peers) {
        RaftConfig config = RaftConfig.of(id, peers);
        RaftLog log = new RaftLog();
        TestTransport transport = new TestTransport();
        TestStateMachine sm = new TestStateMachine();
        RandomGenerator rng = new java.util.Random(id.id() * 31L + 7);
        return new RaftNode(config, log, transport, sm, rng);
    }

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        driver = new MultiRaftDriver(LOCAL, clock);
    }

    // ========================================================================
    // Group management tests
    // ========================================================================

    @Nested
    class GroupManagement {

        @Test
        void emptyDriverHasNoGroups() {
            assertEquals(0, driver.groupCount());
            assertTrue(driver.groupIds().isEmpty());
        }

        @Test
        void addGroupRegistersSuccessfully() {
            RaftNode node = createSingleNodeRaft(LOCAL);
            driver.addGroup(1, node);

            assertEquals(1, driver.groupCount());
            assertTrue(driver.groupIds().contains(1));
            assertSame(node, driver.getGroup(1));
        }

        @Test
        void addMultipleGroups() {
            driver.addGroup(1, createSingleNodeRaft(LOCAL));
            driver.addGroup(2, createSingleNodeRaft(LOCAL));
            driver.addGroup(3, createSingleNodeRaft(LOCAL));

            assertEquals(3, driver.groupCount());
            assertEquals(Set.of(1, 2, 3), driver.groupIds());
        }

        @Test
        void addDuplicateGroupThrows() {
            driver.addGroup(1, createSingleNodeRaft(LOCAL));
            assertThrows(IllegalArgumentException.class,
                    () -> driver.addGroup(1, createSingleNodeRaft(LOCAL)));
        }

        @Test
        void addGroupWithNullNodeThrows() {
            assertThrows(NullPointerException.class,
                    () -> driver.addGroup(1, null));
        }

        @Test
        void removeGroupSucceeds() {
            driver.addGroup(1, createSingleNodeRaft(LOCAL));
            driver.removeGroup(1);

            assertEquals(0, driver.groupCount());
            assertNull(driver.getGroup(1));
        }

        @Test
        void removeNonexistentGroupThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> driver.removeGroup(99));
        }

        @Test
        void getGroupReturnsNullForUnknownId() {
            assertNull(driver.getGroup(42));
        }

        @Test
        void groupIdsReturnsUnmodifiableView() {
            driver.addGroup(1, createSingleNodeRaft(LOCAL));
            Set<Integer> ids = driver.groupIds();
            assertThrows(UnsupportedOperationException.class,
                    () -> ids.add(99));
        }
    }

    // ========================================================================
    // Tick propagation tests
    // ========================================================================

    @Nested
    class TickPropagation {

        @Test
        void tickAdvancesAllGroups() {
            // Single-node clusters become leader on first election timeout
            RaftNode node1 = createSingleNodeRaft(LOCAL);
            RaftNode node2 = createSingleNodeRaft(LOCAL);

            driver.addGroup(1, node1);
            driver.addGroup(2, node2);

            // Both start as FOLLOWER
            assertEquals(RaftRole.FOLLOWER, node1.role());
            assertEquals(RaftRole.FOLLOWER, node2.role());

            // Tick enough to trigger election timeout (max 300 ticks)
            for (int i = 0; i < 301; i++) {
                driver.tick();
            }

            // Single-node clusters should become leader
            assertEquals(RaftRole.LEADER, node1.role());
            assertEquals(RaftRole.LEADER, node2.role());
        }

        @Test
        void tickOnEmptyDriverDoesNotFail() {
            // Should be a no-op, not throw
            assertDoesNotThrow(() -> driver.tick());
        }

        @Test
        void tickAfterGroupRemovalSkipsRemovedGroup() {
            RaftNode node1 = createSingleNodeRaft(LOCAL);
            RaftNode node2 = createSingleNodeRaft(LOCAL);

            driver.addGroup(1, node1);
            driver.addGroup(2, node2);

            driver.removeGroup(1);

            // Tick should only advance group 2
            for (int i = 0; i < 301; i++) {
                driver.tick();
            }

            // node1 was not ticked after removal — still FOLLOWER
            assertEquals(RaftRole.FOLLOWER, node1.role());
            assertEquals(RaftRole.LEADER, node2.role());
        }
    }

    // ========================================================================
    // Message routing tests
    // ========================================================================

    @Nested
    class MessageRouting {

        @Test
        void routeMessageToExistingGroup() {
            RaftNode node = createRaftWithPeers(LOCAL, Set.of(NodeId.of(2)));
            driver.addGroup(1, node);

            // Send a valid AppendEntries from a "leader" with higher term
            AppendEntriesRequest req = new AppendEntriesRequest(
                    5, NodeId.of(2), 0, 0, List.of(), 0);

            driver.routeMessage(1, req);

            // The node should have processed it (leader ID set)
            assertEquals(NodeId.of(2), node.leaderId());
        }

        @Test
        void routeMessageToNonexistentGroupIsDropped() {
            // Should not throw — silently dropped
            AppendEntriesRequest req = new AppendEntriesRequest(
                    1, NodeId.of(2), 0, 0, List.of(), 0);

            assertDoesNotThrow(() -> driver.routeMessage(99, req));
        }

        @Test
        void routeMessageToCorrectGroupOnly() {
            RaftNode node1 = createRaftWithPeers(LOCAL, Set.of(NodeId.of(2)));
            RaftNode node2 = createRaftWithPeers(LOCAL, Set.of(NodeId.of(2)));

            driver.addGroup(1, node1);
            driver.addGroup(2, node2);

            // Route a message only to group 1
            AppendEntriesRequest req = new AppendEntriesRequest(
                    5, NodeId.of(2), 0, 0, List.of(), 0);
            driver.routeMessage(1, req);

            // Only group 1's node should see it
            assertEquals(NodeId.of(2), node1.leaderId());
            assertNull(node2.leaderId());
        }
    }

    // ========================================================================
    // Propose tests
    // ========================================================================

    @Nested
    class Propose {

        @Test
        void proposeToLeaderAccepted() {
            RaftNode node = createSingleNodeRaft(LOCAL);
            driver.addGroup(1, node);

            // Make it a leader (single-node cluster)
            for (int i = 0; i < 301; i++) {
                driver.tick();
            }
            assertEquals(RaftRole.LEADER, node.role());

            ProposalResult result = driver.propose(1, "test-command".getBytes());
            assertEquals(ProposalResult.ACCEPTED, result);
        }

        @Test
        void proposeToFollowerRejected() {
            RaftNode node = createRaftWithPeers(LOCAL, Set.of(NodeId.of(2)));
            driver.addGroup(1, node);

            // Node is a follower (multi-node cluster, no election triggered)
            assertEquals(RaftRole.FOLLOWER, node.role());

            ProposalResult result = driver.propose(1, "test-command".getBytes());
            assertEquals(ProposalResult.NOT_LEADER, result);
        }

        @Test
        void proposeToNonexistentGroupReturnsNotLeader() {
            ProposalResult result = driver.propose(99, "test-command".getBytes());
            assertEquals(ProposalResult.NOT_LEADER, result);
        }
    }

    // ========================================================================
    // Constructor and accessor tests
    // ========================================================================

    @Nested
    class ConstructorAndAccessors {

        @Test
        void constructorRejectsNullLocalNode() {
            assertThrows(NullPointerException.class,
                    () -> new MultiRaftDriver(null, clock));
        }

        @Test
        void constructorRejectsNullClock() {
            assertThrows(NullPointerException.class,
                    () -> new MultiRaftDriver(LOCAL, null));
        }

        @Test
        void localNodeAccessor() {
            assertEquals(LOCAL, driver.localNode());
        }

        @Test
        void clockAccessor() {
            assertSame(clock, driver.clock());
        }
    }
}
