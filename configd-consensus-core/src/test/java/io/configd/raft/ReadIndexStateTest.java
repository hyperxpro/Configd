package io.configd.raft;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ReadIndex protocol state machine.
 * <p>
 * Verifies the linearizable read protocol:
 * <ol>
 *   <li>Record commit index at read time</li>
 *   <li>Confirm leadership via heartbeat quorum</li>
 *   <li>Wait for state machine to apply up to the recorded index</li>
 * </ol>
 */
class ReadIndexStateTest {

    private ReadIndexState state;

    @BeforeEach
    void setUp() {
        state = new ReadIndexState();
    }

    // ========================================================================
    // Basic lifecycle tests
    // ========================================================================

    @Nested
    class BasicLifecycleTests {

        @Test
        void newStateHasNoPendingReads() {
            assertEquals(0, state.pendingCount());
        }

        @Test
        void startReadCreatesAPendingRead() {
            long readId = state.startRead(5);
            assertEquals(0, readId);
            assertEquals(1, state.pendingCount());
            assertEquals(5, state.readIndex(readId));
        }

        @Test
        void readIdsAreMonotonicallyIncreasing() {
            long id0 = state.startRead(1);
            long id1 = state.startRead(2);
            long id2 = state.startRead(3);

            assertEquals(0, id0);
            assertEquals(1, id1);
            assertEquals(2, id2);
            assertEquals(3, state.pendingCount());
        }

        @Test
        void completeRemovesPendingRead() {
            long readId = state.startRead(5);
            assertEquals(1, state.pendingCount());

            state.complete(readId);
            assertEquals(0, state.pendingCount());
            assertEquals(-1, state.readIndex(readId));
        }

        @Test
        void clearRemovesAllPendingReads() {
            state.startRead(1);
            state.startRead(2);
            state.startRead(3);
            assertEquals(3, state.pendingCount());

            state.clear();
            assertEquals(0, state.pendingCount());
        }
    }

    // ========================================================================
    // Readiness tests
    // ========================================================================

    @Nested
    class ReadinessTests {

        @Test
        void readNotReadyBeforeLeadershipConfirmation() {
            long readId = state.startRead(5);
            // Even if lastApplied >= readIndex, not ready without leadership confirmation
            assertFalse(state.isReady(readId, 10));
        }

        @Test
        void readNotReadyWhenLeadershipConfirmedButNotApplied() {
            long readId = state.startRead(10);
            state.confirmLeadership(readId, 3, 2); // quorum met
            // lastApplied (5) < readIndex (10) — not ready
            assertFalse(state.isReady(readId, 5));
        }

        @Test
        void readReadyWhenLeadershipConfirmedAndApplied() {
            long readId = state.startRead(5);
            state.confirmLeadership(readId, 3, 2); // quorum met
            // lastApplied (5) >= readIndex (5) — ready
            assertTrue(state.isReady(readId, 5));
        }

        @Test
        void readReadyWhenLastAppliedExceedsReadIndex() {
            long readId = state.startRead(5);
            state.confirmLeadership(readId, 2, 2); // exactly quorum
            assertTrue(state.isReady(readId, 100));
        }

        @Test
        void readNotReadyIfQuorumNotMet() {
            long readId = state.startRead(5);
            state.confirmLeadership(readId, 1, 2); // below quorum
            assertFalse(state.isReady(readId, 100));
        }

        @Test
        void unknownReadIdIsNotReady() {
            assertFalse(state.isReady(999, 100));
        }

        @Test
        void completedReadIsNotReady() {
            long readId = state.startRead(5);
            state.confirmLeadership(readId, 3, 2);
            state.complete(readId);
            assertFalse(state.isReady(readId, 100));
        }
    }

    // ========================================================================
    // confirmAll tests
    // ========================================================================

    @Nested
    class ConfirmAllTests {

        @Test
        void confirmAllConfirmsAllPendingReads() {
            long id0 = state.startRead(3);
            long id1 = state.startRead(5);
            long id2 = state.startRead(7);

            state.confirmAll(3, 2); // quorum = 2, ack = 3

            // All reads should be confirmed; readiness depends on lastApplied
            assertTrue(state.isReady(id0, 3));
            assertTrue(state.isReady(id1, 5));
            assertFalse(state.isReady(id2, 5)); // lastApplied 5 < readIndex 7
            assertTrue(state.isReady(id2, 7));
        }

        @Test
        void confirmAllDoesNothingBelowQuorum() {
            long readId = state.startRead(3);

            state.confirmAll(1, 2); // below quorum

            assertFalse(state.isReady(readId, 100));
        }

        @Test
        void confirmAllDoesNotReConfirmAlreadyConfirmed() {
            long readId = state.startRead(3);
            state.confirmLeadership(readId, 3, 2); // already confirmed

            // Should not break anything
            state.confirmAll(3, 2);

            assertTrue(state.isReady(readId, 3));
        }

        @Test
        void confirmAllOnEmptyStateIsNoOp() {
            // Should not throw
            state.confirmAll(3, 2);
            assertEquals(0, state.pendingCount());
        }
    }

    // ========================================================================
    // ReadIndex value tests
    // ========================================================================

    @Nested
    class ReadIndexValueTests {

        @Test
        void readIndexReturnsCommitIndexAtStartTime() {
            long readId = state.startRead(42);
            assertEquals(42, state.readIndex(readId));
        }

        @Test
        void readIndexReturnsMinusOneForUnknownId() {
            assertEquals(-1, state.readIndex(999));
        }

        @Test
        void readWithZeroCommitIndex() {
            long readId = state.startRead(0);
            assertEquals(0, state.readIndex(readId));
            // With leadership confirmed, should be ready since lastApplied 0 >= readIndex 0
            state.confirmLeadership(readId, 2, 2);
            assertTrue(state.isReady(readId, 0));
        }
    }

    // ========================================================================
    // Multiple concurrent reads
    // ========================================================================

    @Nested
    class ConcurrentReadTests {

        @Test
        void multipleReadsWithDifferentCommitIndices() {
            long id0 = state.startRead(5);
            long id1 = state.startRead(10);
            long id2 = state.startRead(15);

            // Confirm all via heartbeat
            state.confirmAll(3, 2);

            // Check each at different applied levels
            assertTrue(state.isReady(id0, 5));
            assertFalse(state.isReady(id1, 5));
            assertFalse(state.isReady(id2, 5));

            assertTrue(state.isReady(id1, 10));
            assertFalse(state.isReady(id2, 10));

            assertTrue(state.isReady(id2, 15));
        }

        @Test
        void completingOneReadDoesNotAffectOthers() {
            long id0 = state.startRead(5);
            long id1 = state.startRead(10);

            state.confirmAll(3, 2);
            state.complete(id0);

            assertEquals(1, state.pendingCount());
            assertFalse(state.isReady(id0, 100)); // completed
            assertTrue(state.isReady(id1, 10));    // still active
        }
    }
}
