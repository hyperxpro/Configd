package io.configd.raft;

import io.configd.common.InMemoryStorage;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DurableRaftState} — persistence of currentTerm and votedFor.
 */
class DurableRaftStateTest {

    @Nested
    class InitialState {
        @Test
        void freshStorageStartsAtTermZeroNoVote() {
            var state = new DurableRaftState(Storage.inMemory());
            assertEquals(0, state.currentTerm());
            assertNull(state.votedFor());
        }
    }

    @Nested
    class TermPersistence {
        @Test
        void termSurvivesRestart() {
            Storage storage = Storage.inMemory();
            var state1 = new DurableRaftState(storage);
            state1.setTerm(5);

            // Simulate restart: create new DurableRaftState with same storage
            var state2 = new DurableRaftState(storage);
            assertEquals(5, state2.currentTerm());
        }

        @Test
        void termAdvancementClearsVote() {
            var state = new DurableRaftState(Storage.inMemory());
            state.setTermAndVote(3, NodeId.of(1));
            assertEquals(NodeId.of(1), state.votedFor());

            state.setTerm(4);
            assertNull(state.votedFor(), "Vote must be cleared on term change");
        }

        @Test
        void termCannotDecrease() {
            var state = new DurableRaftState(Storage.inMemory());
            state.setTerm(5);
            assertThrows(IllegalArgumentException.class, () -> state.setTerm(3));
        }
    }

    @Nested
    class VotePersistence {
        @Test
        void voteSurvivesRestart() {
            Storage storage = Storage.inMemory();
            var state1 = new DurableRaftState(storage);
            state1.setTerm(3);
            state1.vote(NodeId.of(2));

            var state2 = new DurableRaftState(storage);
            assertEquals(3, state2.currentTerm());
            assertEquals(NodeId.of(2), state2.votedFor());
        }

        @Test
        void cannotVoteForDifferentCandidateInSameTerm() {
            var state = new DurableRaftState(Storage.inMemory());
            state.setTerm(1);
            state.vote(NodeId.of(1));
            assertThrows(IllegalStateException.class, () -> state.vote(NodeId.of(2)));
        }

        @Test
        void canVoteForSameCandidateAgain() {
            var state = new DurableRaftState(Storage.inMemory());
            state.setTerm(1);
            state.vote(NodeId.of(1));
            assertDoesNotThrow(() -> state.vote(NodeId.of(1)));
        }
    }

    @Nested
    class AtomicTermAndVote {
        @Test
        void setTermAndVotePersistsBothAtomically() {
            Storage storage = Storage.inMemory();
            var state1 = new DurableRaftState(storage);
            state1.setTermAndVote(7, NodeId.of(3));

            var state2 = new DurableRaftState(storage);
            assertEquals(7, state2.currentTerm());
            assertEquals(NodeId.of(3), state2.votedFor());
        }
    }
}
