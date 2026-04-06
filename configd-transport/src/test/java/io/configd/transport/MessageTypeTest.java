package io.configd.transport;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MessageTypeTest {

    // ── Unique non-zero codes ───────────────────────────────────────────

    @Nested
    class CodeUniqueness {

        @Test
        void allCodesAreNonZero() {
            for (MessageType type : MessageType.values()) {
                assertNotEquals(0, type.code(),
                        type.name() + " must have a non-zero code");
            }
        }

        @Test
        void allCodesAreUnique() {
            Set<Integer> seen = new HashSet<>();
            for (MessageType type : MessageType.values()) {
                assertTrue(seen.add(type.code()),
                        "Duplicate code " + type.code() + " for " + type.name());
            }
        }
    }

    // ── fromCode round-trip ─────────────────────────────────────────────

    @Nested
    class FromCodeRoundTrip {

        @Test
        void roundTripForEveryMessageType() {
            for (MessageType type : MessageType.values()) {
                assertEquals(type, MessageType.fromCode(type.code()),
                        "fromCode round-trip failed for " + type.name());
            }
        }

        @Test
        void allValuesAreCoveredByFromCode() {
            Set<MessageType> recovered = new HashSet<>();
            for (MessageType type : MessageType.values()) {
                recovered.add(MessageType.fromCode(type.code()));
            }
            assertEquals(Set.of(MessageType.values()), recovered);
        }
    }

    // ── fromCode with invalid codes ─────────────────────────────────────

    @Nested
    class FromCodeInvalid {

        @Test
        void zeroCodeThrows() {
            assertThrows(IllegalArgumentException.class, () -> MessageType.fromCode(0));
        }

        @Test
        void negativeCodeThrows() {
            assertThrows(IllegalArgumentException.class, () -> MessageType.fromCode(-1));
        }

        @Test
        void outOfRangeCodeThrows() {
            assertThrows(IllegalArgumentException.class, () -> MessageType.fromCode(0xFF));
        }

        @Test
        void justAboveTableSizeThrows() {
            // BY_CODE array is size 0x11 = 17, so code 0x11 is out of bounds
            assertThrows(IllegalArgumentException.class, () -> MessageType.fromCode(0x11));
        }
    }

    // ── Specific code assignments (ADR-0010) ────────────────────────────

    @Nested
    class SpecificCodeAssignments {

        @Test
        void appendEntriesIs0x01() {
            assertEquals(0x01, MessageType.APPEND_ENTRIES.code());
        }

        @Test
        void appendEntriesResponseIs0x02() {
            assertEquals(0x02, MessageType.APPEND_ENTRIES_RESPONSE.code());
        }

        @Test
        void requestVoteIs0x03() {
            assertEquals(0x03, MessageType.REQUEST_VOTE.code());
        }

        @Test
        void requestVoteResponseIs0x04() {
            assertEquals(0x04, MessageType.REQUEST_VOTE_RESPONSE.code());
        }

        @Test
        void preVoteIs0x05() {
            assertEquals(0x05, MessageType.PRE_VOTE.code());
        }

        @Test
        void preVoteResponseIs0x06() {
            assertEquals(0x06, MessageType.PRE_VOTE_RESPONSE.code());
        }

        @Test
        void installSnapshotIs0x07() {
            assertEquals(0x07, MessageType.INSTALL_SNAPSHOT.code());
        }

        @Test
        void plumtreeEagerPushIs0x08() {
            assertEquals(0x08, MessageType.PLUMTREE_EAGER_PUSH.code());
        }

        @Test
        void plumtreeIhaveIs0x09() {
            assertEquals(0x09, MessageType.PLUMTREE_IHAVE.code());
        }

        @Test
        void plumtreePruneIs0x0A() {
            assertEquals(0x0A, MessageType.PLUMTREE_PRUNE.code());
        }

        @Test
        void plumtreeGraftIs0x0B() {
            assertEquals(0x0B, MessageType.PLUMTREE_GRAFT.code());
        }

        @Test
        void hyparviewJoinIs0x0C() {
            assertEquals(0x0C, MessageType.HYPARVIEW_JOIN.code());
        }

        @Test
        void hyparviewShuffleIs0x0D() {
            assertEquals(0x0D, MessageType.HYPARVIEW_SHUFFLE.code());
        }

        @Test
        void heartbeatIs0x0E() {
            assertEquals(0x0E, MessageType.HEARTBEAT.code());
        }

        @Test
        void installSnapshotResponseIs0x0F() {
            assertEquals(0x0F, MessageType.INSTALL_SNAPSHOT_RESPONSE.code());
        }

        @Test
        void timeoutNowIs0x10() {
            assertEquals(0x10, MessageType.TIMEOUT_NOW.code());
        }
    }
}
