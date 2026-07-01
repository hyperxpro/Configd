package io.configd.raft;

import io.configd.common.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the payload-carrying {@link HeartbeatCoalescer}: the tick window, latest-wins per
 * (peer, group), per-peer coalescing across groups (the flat-in-N property at the data-structure level),
 * and the drain.
 */
class HeartbeatCoalescerTest {

    private static final NodeId PEER_A = NodeId.of(2);
    private static final NodeId PEER_B = NodeId.of(3);
    private static final NodeId LEADER = NodeId.of(1);

    private HeartbeatCoalescer coalescer;

    @BeforeEach
    void setUp() {
        coalescer = new HeartbeatCoalescer();
    }

    /** An empty AppendEntries (a heartbeat) with the given commit index (to tell records apart). */
    private static AppendEntriesRequest heartbeat(long leaderCommit) {
        return new AppendEntriesRequest(1L, LEADER, 0L, 0L, List.of(), leaderCommit);
    }

    @Nested
    class TickWindow {

        @Test
        void recordOutsideWindowIsRejected() {
            // No beginTick() - not collecting - record refused so the caller sends immediately.
            assertFalse(coalescer.isCollecting());
            assertFalse(coalescer.recordIfCollecting(PEER_A, 1, heartbeat(0)));
            assertTrue(coalescer.pendingPeers().isEmpty());
        }

        @Test
        void recordInsideWindowIsBuffered() {
            coalescer.beginTick();
            assertTrue(coalescer.isCollecting());
            assertTrue(coalescer.recordIfCollecting(PEER_A, 1, heartbeat(0)));
            assertEquals(Set.of(PEER_A), coalescer.pendingPeers());
        }

        @Test
        void drainClosesTheWindow() {
            coalescer.beginTick();
            coalescer.recordIfCollecting(PEER_A, 1, heartbeat(0));
            coalescer.drainAndEndTick();
            assertFalse(coalescer.isCollecting());
            // After drain, a further record without a new beginTick is refused.
            assertFalse(coalescer.recordIfCollecting(PEER_A, 1, heartbeat(0)));
        }

        @Test
        void nullArgsRejected() {
            coalescer.beginTick();
            assertThrows(NullPointerException.class, () -> coalescer.recordIfCollecting(null, 1, heartbeat(0)));
            assertThrows(NullPointerException.class, () -> coalescer.recordIfCollecting(PEER_A, 1, null));
        }
    }

    @Nested
    class PayloadAndDrain {

        @Test
        void drainCarriesThePayloadPerGroup() {
            coalescer.beginTick();
            AppendEntriesRequest g1 = heartbeat(10);
            AppendEntriesRequest g2 = heartbeat(20);
            coalescer.recordIfCollecting(PEER_A, 1, g1);
            coalescer.recordIfCollecting(PEER_A, 2, g2);

            Map<NodeId, Map<Integer, AppendEntriesRequest>> drained = coalescer.drainAndEndTick();
            assertEquals(Set.of(PEER_A), drained.keySet());
            assertSame(g1, drained.get(PEER_A).get(1));
            assertSame(g2, drained.get(PEER_A).get(2));
        }

        @Test
        void latestRecordPerGroupWins() {
            coalescer.beginTick();
            AppendEntriesRequest stale = heartbeat(10);
            AppendEntriesRequest fresh = heartbeat(99);
            coalescer.recordIfCollecting(PEER_A, 1, stale);
            coalescer.recordIfCollecting(PEER_A, 1, fresh); // same (peer, group) - overwrite

            Map<NodeId, Map<Integer, AppendEntriesRequest>> drained = coalescer.drainAndEndTick();
            assertEquals(1, drained.get(PEER_A).size());
            assertSame(fresh, drained.get(PEER_A).get(1));
        }

        @Test
        void drainEmptyReturnsEmptyMap() {
            coalescer.beginTick();
            assertTrue(coalescer.drainAndEndTick().isEmpty());
        }

        @Test
        void drainResultIsUnmodifiable() {
            coalescer.beginTick();
            coalescer.recordIfCollecting(PEER_A, 1, heartbeat(0));
            Map<NodeId, Map<Integer, AppendEntriesRequest>> drained = coalescer.drainAndEndTick();
            assertThrows(UnsupportedOperationException.class, () -> drained.put(PEER_B, Map.of()));
            assertThrows(UnsupportedOperationException.class, () -> drained.get(PEER_A).put(9, heartbeat(0)));
        }

        @Test
        void drainClearsBuffer() {
            coalescer.beginTick();
            coalescer.recordIfCollecting(PEER_A, 1, heartbeat(0));
            coalescer.drainAndEndTick();
            assertTrue(coalescer.pendingPeers().isEmpty());
            coalescer.beginTick();
            assertTrue(coalescer.drainAndEndTick().isEmpty());
        }
    }

    @Nested
    class CoalescingAcrossGroups {

        @Test
        void manyGroupsCoalesceToOneEntryPerPeer() {
            // The flat-in-N property at the data-structure level: 100 groups each heartbeating two peers
            // produce exactly TWO drain entries (one per peer), not 200 - each carrying all 100 groups.
            coalescer.beginTick();
            for (int groupId = 0; groupId < 100; groupId++) {
                coalescer.recordIfCollecting(PEER_A, groupId, heartbeat(groupId));
                coalescer.recordIfCollecting(PEER_B, groupId, heartbeat(groupId));
            }
            Map<NodeId, Map<Integer, AppendEntriesRequest>> drained = coalescer.drainAndEndTick();
            assertEquals(2, drained.size());
            assertEquals(100, drained.get(PEER_A).size());
            assertEquals(100, drained.get(PEER_B).size());
        }

        @Test
        void drainSinglePeerSingleGroupIsTheNormalCase() {
            // The N=1 production case: one group -> one peer -> one entry with one group (sent as a plain
            // AppendEntries, wire unchanged).
            coalescer.beginTick();
            coalescer.recordIfCollecting(PEER_A, 0, heartbeat(7));
            Map<NodeId, Map<Integer, AppendEntriesRequest>> drained = coalescer.drainAndEndTick();
            assertEquals(1, drained.size());
            assertEquals(1, drained.get(PEER_A).size());
        }
    }

    @Nested
    class Reset {

        @Test
        void resetClearsBufferAndWindow() {
            coalescer.beginTick();
            coalescer.recordIfCollecting(PEER_A, 1, heartbeat(0));
            coalescer.reset();
            assertFalse(coalescer.isCollecting());
            assertTrue(coalescer.pendingPeers().isEmpty());
        }
    }
}
