package io.configd.replication;

import io.configd.common.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HeartbeatCoalescer} — coalescing across groups,
 * draining, time windows, and reset.
 */
class HeartbeatCoalescerTest {

    private static final long WINDOW_NANOS = 1_000_000L; // 1ms
    private static final NodeId PEER_A = NodeId.of(2);
    private static final NodeId PEER_B = NodeId.of(3);

    private HeartbeatCoalescer coalescer;

    @BeforeEach
    void setUp() {
        coalescer = new HeartbeatCoalescer(WINDOW_NANOS);
    }

    // ========================================================================
    // Constructor validation
    // ========================================================================

    @Nested
    class ConstructorValidation {

        @Test
        void rejectsZeroWindow() {
            assertThrows(IllegalArgumentException.class,
                    () -> new HeartbeatCoalescer(0));
        }

        @Test
        void rejectsNegativeWindow() {
            assertThrows(IllegalArgumentException.class,
                    () -> new HeartbeatCoalescer(-1));
        }
    }

    // ========================================================================
    // Empty coalescer
    // ========================================================================

    @Nested
    class EmptyCoalescer {

        @Test
        void pendingPeersIsEmpty() {
            assertTrue(coalescer.pendingPeers().isEmpty());
        }

        @Test
        void drainAllReturnsEmptyMap() {
            assertTrue(coalescer.drainAll().isEmpty());
        }

        @Test
        void drainUnknownPeerReturnsEmptySet() {
            assertTrue(coalescer.drain(PEER_A).isEmpty());
        }

        @Test
        void shouldFlushReturnsFalseWhenEmpty() {
            assertFalse(coalescer.shouldFlush(Long.MAX_VALUE));
        }
    }

    // ========================================================================
    // Recording heartbeats
    // ========================================================================

    @Nested
    class RecordingHeartbeats {

        @Test
        void recordSingleHeartbeat() {
            coalescer.recordHeartbeat(PEER_A, 1);
            assertEquals(Set.of(PEER_A), coalescer.pendingPeers());
        }

        @Test
        void recordMultipleGroupsForSamePeer() {
            coalescer.recordHeartbeat(PEER_A, 1);
            coalescer.recordHeartbeat(PEER_A, 2);
            coalescer.recordHeartbeat(PEER_A, 3);

            Set<Integer> drained = coalescer.drain(PEER_A);
            assertEquals(Set.of(1, 2, 3), drained);
        }

        @Test
        void recordMultiplePeers() {
            coalescer.recordHeartbeat(PEER_A, 1);
            coalescer.recordHeartbeat(PEER_B, 1);

            assertEquals(Set.of(PEER_A, PEER_B), coalescer.pendingPeers());
        }

        @Test
        void duplicateRecordIsDeduplicated() {
            coalescer.recordHeartbeat(PEER_A, 1);
            coalescer.recordHeartbeat(PEER_A, 1); // duplicate

            Set<Integer> drained = coalescer.drain(PEER_A);
            assertEquals(Set.of(1), drained);
        }

        @Test
        void recordRejectsNullPeer() {
            assertThrows(NullPointerException.class,
                    () -> coalescer.recordHeartbeat(null, 1));
        }
    }

    // ========================================================================
    // Draining
    // ========================================================================

    @Nested
    class Draining {

        @Test
        void drainSinglePeerClearsThatPeer() {
            coalescer.recordHeartbeat(PEER_A, 1);
            coalescer.recordHeartbeat(PEER_B, 2);

            Set<Integer> drainedA = coalescer.drain(PEER_A);
            assertEquals(Set.of(1), drainedA);

            // PEER_B should still be pending
            assertEquals(Set.of(PEER_B), coalescer.pendingPeers());
        }

        @Test
        void drainReturnsUnmodifiableSet() {
            coalescer.recordHeartbeat(PEER_A, 1);
            Set<Integer> drained = coalescer.drain(PEER_A);
            assertThrows(UnsupportedOperationException.class,
                    () -> drained.add(99));
        }

        @Test
        void drainAllReturnsAllPeers() {
            coalescer.recordHeartbeat(PEER_A, 1);
            coalescer.recordHeartbeat(PEER_A, 2);
            coalescer.recordHeartbeat(PEER_B, 3);

            Map<NodeId, Set<Integer>> result = coalescer.drainAll();
            assertEquals(2, result.size());
            assertEquals(Set.of(1, 2), result.get(PEER_A));
            assertEquals(Set.of(3), result.get(PEER_B));

            // Everything should be cleared
            assertTrue(coalescer.pendingPeers().isEmpty());
        }

        @Test
        void drainAllReturnsUnmodifiableMap() {
            coalescer.recordHeartbeat(PEER_A, 1);
            Map<NodeId, Set<Integer>> result = coalescer.drainAll();
            assertThrows(UnsupportedOperationException.class,
                    () -> result.put(PEER_B, Set.of(99)));
        }

        @Test
        void drainAllInnerSetsAreUnmodifiable() {
            coalescer.recordHeartbeat(PEER_A, 1);
            Map<NodeId, Set<Integer>> result = coalescer.drainAll();
            assertThrows(UnsupportedOperationException.class,
                    () -> result.get(PEER_A).add(99));
        }

        @Test
        void drainPeerTwiceReturnsEmptySecondTime() {
            coalescer.recordHeartbeat(PEER_A, 1);
            coalescer.drain(PEER_A);
            assertTrue(coalescer.drain(PEER_A).isEmpty());
        }
    }

    // ========================================================================
    // Time window
    // ========================================================================

    @Nested
    class TimeWindow {

        @Test
        void shouldFlushAfterWindowExpires() {
            coalescer.recordHeartbeat(PEER_A, 1);

            // Initialize window
            assertFalse(coalescer.shouldFlush(0L));
            // Still within window
            assertFalse(coalescer.shouldFlush(WINDOW_NANOS - 1));
            // Window expired
            assertTrue(coalescer.shouldFlush(WINDOW_NANOS));
        }

        @Test
        void windowResetsAfterDrainAll() {
            coalescer.recordHeartbeat(PEER_A, 1);
            coalescer.shouldFlush(0L); // initialize window
            coalescer.drainAll();

            // New intent after drain
            coalescer.recordHeartbeat(PEER_A, 2);
            // Window should restart from new base time
            assertFalse(coalescer.shouldFlush(WINDOW_NANOS - 1));
            coalescer.shouldFlush(1_000_000_000L); // initialize new window
            assertTrue(coalescer.shouldFlush(1_000_000_000L + WINDOW_NANOS));
        }

        @Test
        void shouldFlushReturnsFalseAfterReset() {
            coalescer.recordHeartbeat(PEER_A, 1);
            coalescer.shouldFlush(0L);
            coalescer.reset();

            assertFalse(coalescer.shouldFlush(WINDOW_NANOS * 100));
        }
    }

    // ========================================================================
    // Reset
    // ========================================================================

    @Nested
    class ResetTests {

        @Test
        void resetClearsAllPendingIntents() {
            coalescer.recordHeartbeat(PEER_A, 1);
            coalescer.recordHeartbeat(PEER_B, 2);
            coalescer.shouldFlush(0L);

            coalescer.reset();

            assertTrue(coalescer.pendingPeers().isEmpty());
            assertTrue(coalescer.drainAll().isEmpty());
        }
    }

    // ========================================================================
    // Coalescing across groups
    // ========================================================================

    @Nested
    class CoalescingAcrossGroups {

        @Test
        void multipleGroupsCoalescePerPeer() {
            // Simulate 100 Raft groups each needing heartbeats to PEER_A and PEER_B
            for (int groupId = 0; groupId < 100; groupId++) {
                coalescer.recordHeartbeat(PEER_A, groupId);
                coalescer.recordHeartbeat(PEER_B, groupId);
            }

            // Should produce exactly 2 drain entries (one per peer),
            // not 200 individual messages
            Map<NodeId, Set<Integer>> result = coalescer.drainAll();
            assertEquals(2, result.size());
            assertEquals(100, result.get(PEER_A).size());
            assertEquals(100, result.get(PEER_B).size());
        }
    }

    // ========================================================================
    // PendingPeers view
    // ========================================================================

    @Nested
    class PendingPeersView {

        @Test
        void pendingPeersIsUnmodifiable() {
            coalescer.recordHeartbeat(PEER_A, 1);
            Set<NodeId> peers = coalescer.pendingPeers();
            assertThrows(UnsupportedOperationException.class,
                    () -> peers.add(PEER_B));
        }
    }
}
