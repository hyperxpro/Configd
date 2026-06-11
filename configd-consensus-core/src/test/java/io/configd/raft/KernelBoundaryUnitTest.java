package io.configd.raft;

import io.configd.common.NodeId;
import io.configd.common.Storage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Discriminating boundary/guard tests for the small safety-kernel classes
 * ({@link ClusterConfig}, {@link ReadIndexState}, {@link DurableRaftState}).
 * <p>
 * S2/mutation-gap (RR-085): these classes already had behavioral tests, but a
 * handful of survivors persisted because the existing tests exercised the
 * happy path without pinning the exact comparison/equality boundary the mutant
 * flips. Each test here is the minimal example that fails iff the named mutant
 * is applied: an equality at the boundary, a {@code <} vs {@code <=}, a removed
 * guard, or a degenerate {@code return 0}/{@code return ""}. Pure, in-process,
 * deterministic.
 */
class KernelBoundaryUnitTest {

    private static final NodeId N1 = NodeId.of(1);
    private static final NodeId N2 = NodeId.of(2);
    private static final NodeId N3 = NodeId.of(3);
    private static final NodeId N4 = NodeId.of(4);

    // ====================================================================
    // ClusterConfig
    // ====================================================================

    @Nested
    class ClusterConfigGuards {

        @Test
        void jointRejectsEmptyOldOrNewVoters() {
            // Kills ClusterConfig.joint L65 EQUAL_ELSE (the
            // `oldVoters.isEmpty() || newVoters.isEmpty()` guard removed): an empty
            // side must throw, not build a degenerate joint config.
            assertThrows(IllegalArgumentException.class,
                    () -> ClusterConfig.joint(Set.of(), Set.of(N1)));
            assertThrows(IllegalArgumentException.class,
                    () -> ClusterConfig.joint(Set.of(N1), Set.of()));
            // A valid joint config is accepted.
            assertDoesNotThrow(() -> ClusterConfig.joint(Set.of(N1), Set.of(N2)));
        }

        @Test
        void hashCodeDistinguishesDifferentConfigs() {
            // Kills hashCode PrimitiveReturns (return 0): if hashCode always returned
            // 0, these distinct configs would collide. We assert they differ, which
            // a constant-0 hashCode cannot satisfy. (Hash inequality implies object
            // inequality, so this is a sound discriminator.)
            var a = ClusterConfig.simple(Set.of(N1, N2, N3));
            var b = ClusterConfig.simple(Set.of(N1, N2));
            var c = ClusterConfig.joint(Set.of(N1, N2, N3), Set.of(N2, N3, N4));
            assertNotEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a.hashCode(), c.hashCode());
        }

        @Test
        void equalsRejectsDifferentTypeAndNull() {
            // Kills equals L178/179 (BooleanFalse/True return): equals must return
            // false for a non-ClusterConfig and for null, and true for self.
            var cfg = ClusterConfig.simple(Set.of(N1, N2, N3));
            assertNotEquals(cfg, "not a config");
            assertNotEquals(cfg, null);
            assertEquals(cfg, cfg); // reflexive (this == o branch)
        }

        @Test
        void equalsDistinguishesJointFromSimpleWithSameVoters() {
            // joint flag participates in equals: a simple config and a joint config
            // sharing the same `voters` set must not be equal.
            var simple = ClusterConfig.simple(Set.of(N1, N2));
            var joint = ClusterConfig.joint(Set.of(N1, N2), Set.of(N3));
            assertNotEquals(simple, joint);
        }

        @Test
        void toStringReflectsSimpleVsJoint() {
            // Kills toString L192 EQUAL_ELSE (the `!joint` branch) and the
            // EmptyObjectReturns ("") mutants: the two shapes must render
            // differently and non-empty, and the joint form must name both sets.
            var simple = ClusterConfig.simple(Set.of(N1));
            var joint = ClusterConfig.joint(Set.of(N1), Set.of(N2));
            String s = simple.toString();
            String j = joint.toString();
            assertFalse(s.isEmpty());
            assertFalse(j.isEmpty());
            assertFalse(s.contains("JOINT"), "simple config must not render as JOINT");
            assertTrue(j.contains("JOINT"), "joint config must render as JOINT");
            assertNotEquals(s, j);
        }
    }

    // ====================================================================
    // ReadIndexState — quorum boundaries
    // ====================================================================

    @Nested
    class ReadIndexQuorumBoundaries {

        @Test
        void confirmLeadershipConfirmsExactlyAtQuorumNotBelow() {
            // Kills confirmLeadership L98 EQUAL_ELSE (`ackCount >= quorumSize`):
            // ack == quorum confirms; ack == quorum-1 does NOT.
            ReadIndexState below = new ReadIndexState();
            long r1 = below.startRead(3);
            below.confirmLeadership(r1, 2, 3); // ack 2 < quorum 3 -> not confirmed
            assertFalse(below.isReady(r1, 100), "ack below quorum must not confirm");

            ReadIndexState atQuorum = new ReadIndexState();
            long r2 = atQuorum.startRead(3);
            atQuorum.confirmLeadership(r2, 3, 3); // ack == quorum -> confirmed
            assertTrue(atQuorum.isReady(r2, 100), "ack exactly at quorum must confirm");
        }

        @Test
        void confirmAllConfirmsAtQuorumBoundaryNotJustBelow() {
            // Kills confirmAll L179 ConditionalsBoundary (`ackCount < quorumSize`):
            // ack == quorum confirms; ack == quorum-1 does not.
            ReadIndexState atQuorum = new ReadIndexState();
            long a = atQuorum.startRead(3);
            atQuorum.confirmAll(3, 3); // ack == quorum
            assertTrue(atQuorum.isReady(a, 3));

            ReadIndexState below = new ReadIndexState();
            long b = below.startRead(3);
            below.confirmAll(2, 3); // ack == quorum-1
            assertFalse(below.isReady(b, 100));
        }

        @Test
        void confirmLeadershipOnUnknownReadIdIsSafeNoOp() {
            // Kills confirmLeadership L94 EQUAL_ELSE (the `pending == null` early
            // return): if removed, the method would NPE on `pending.withAck(...)`.
            // A call on a never-started readId must be a silent no-op.
            ReadIndexState state = new ReadIndexState();
            assertDoesNotThrow(() -> state.confirmLeadership(999L, 3, 2));
            assertEquals(0, state.pendingCount());
        }

        @Test
        void confirmAllLeadershipPreservesAlreadyConfirmedReads() {
            // Kills the confirmAllLeadership lambda EQUAL_ELSE (the
            // `leadershipConfirmed() ? pending : pending.confirmed()` ternary):
            // an already-confirmed read must remain confirmed (idempotent), and an
            // unconfirmed one becomes confirmed.
            ReadIndexState state = new ReadIndexState();
            long confirmed = state.startRead(2);
            long fresh = state.startRead(2);
            state.confirmLeadership(confirmed, 5, 1); // confirm the first
            assertTrue(state.isReady(confirmed, 2));

            state.confirmAllLeadership();
            assertTrue(state.isReady(confirmed, 2), "already-confirmed read stays confirmed");
            assertTrue(state.isReady(fresh, 2), "unconfirmed read becomes confirmed");
        }
    }

    // ====================================================================
    // DurableRaftState — term-monotonicity boundary
    // ====================================================================

    @Nested
    class DurableTermBoundary {

        @Test
        void equalTermIsNoOpButLowerThrows() {
            // Kills setTerm L71 ConditionalsBoundary (`newTerm < currentTerm`):
            // strictly-lower throws; EQUAL term must NOT throw (it is a no-op).
            DurableRaftState state = new DurableRaftState(Storage.inMemory());
            state.setTerm(5);
            assertThrows(IllegalArgumentException.class, () -> state.setTerm(4));
            assertDoesNotThrow(() -> state.setTerm(5)); // equal -> no-op
            assertEquals(5, state.currentTerm());
        }

        @Test
        void advancingTermClearsVoteOnlyOnStrictIncrease() {
            // Kills setTerm L75 ConditionalsBoundary (`newTerm > currentTerm`):
            // a strict increase clears the vote and bumps the term; setting the SAME
            // term must NOT clear the existing vote.
            DurableRaftState state = new DurableRaftState(Storage.inMemory());
            state.setTerm(2);
            state.vote(N1);
            assertEquals(N1, state.votedFor());

            state.setTerm(2); // same term -> vote preserved
            assertEquals(N1, state.votedFor(), "same-term setTerm must not clear the vote");

            state.setTerm(3); // strict increase -> vote cleared
            assertNull(state.votedFor(), "term advance must clear the per-term vote");
            assertEquals(3, state.currentTerm());
        }

        @Test
        void termAndVoteSurviveReload() {
            // Kills load L146 (votedForId == NULL sentinel): a persisted (term, vote)
            // must reload with a NON-null votedFor.
            Storage storage = Storage.inMemory();
            DurableRaftState s1 = new DurableRaftState(storage);
            s1.setTerm(7);
            s1.vote(N2);

            DurableRaftState s2 = new DurableRaftState(storage);
            assertEquals(7, s2.currentTerm());
            assertEquals(N2, s2.votedFor());

            DurableRaftState fresh = new DurableRaftState(Storage.inMemory());
            assertEquals(0, fresh.currentTerm());
            assertNull(fresh.votedFor());
        }

        @Test
        void reloadDistinguishesNullVoteFromRealVote() {
            // Kills load L146 EQUAL_ELSE (`votedForId == VOTED_FOR_NULL`): a state
            // with a term but NO vote must reload votedFor == null, while a state with
            // a vote must reload it non-null. If the equality were removed, the null
            // sentinel (-1) would be turned into NodeId.of(-1) instead of null.
            Storage storage = Storage.inMemory();
            DurableRaftState s1 = new DurableRaftState(storage);
            s1.setTerm(4); // advancing term clears any vote -> votedFor null, term 4 persisted
            assertNull(s1.votedFor());

            DurableRaftState reloaded = new DurableRaftState(storage);
            assertEquals(4, reloaded.currentTerm());
            assertNull(reloaded.votedFor(), "a persisted null vote must reload as null");
        }

        @Test
        void shortPersistedBlobLoadsAsFreshState() {
            // Kills load L138 ORDER_ELSE (`data.length < 12`): a present-but-too-short
            // blob must be treated as fresh (term 0, null vote), not parsed as a valid
            // 12-byte record.
            Storage storage = Storage.inMemory();
            storage.put("raft.persistent_state", new byte[]{1, 2, 3}); // 3 bytes < 12
            DurableRaftState state = new DurableRaftState(storage);
            assertEquals(0, state.currentTerm());
            assertNull(state.votedFor());
        }
    }
}
