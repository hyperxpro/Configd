package io.configd.raft;

import io.configd.common.NodeId;
import io.configd.common.Storage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Discriminating boundary/guard tests for the small safety-kernel classes
 * ({@link ClusterConfig}, {@link ReadIndexState}).
 * <p>
 * These classes already had behavioral tests, but a
 * handful of boundaries went untested because the existing tests exercised the
 * happy path without pinning the exact comparison/equality boundary involved.
 * Each test here is the minimal example that fails iff that boundary regresses:
 * an equality at the boundary, a {@code <} vs {@code <=}, a removed
 * guard, or a degenerate {@code return 0}/{@code return ""}. Pure, in-process,
 * deterministic.
 */
class KernelBoundaryUnitTest {

    private static final NodeId N1 = NodeId.of(1);
    private static final NodeId N2 = NodeId.of(2);
    private static final NodeId N3 = NodeId.of(3);
    private static final NodeId N4 = NodeId.of(4);


    @Nested
    class ClusterConfigGuards {

        @Test
        void jointRejectsEmptyOldOrNewVoters() {
            // Guards the `oldVoters.isEmpty() || newVoters.isEmpty()` check in
            // ClusterConfig.joint: an empty side must throw, not build a degenerate
            // joint config.
            assertThrows(IllegalArgumentException.class,
                    () -> ClusterConfig.joint(Set.of(), Set.of(N1)));
            assertThrows(IllegalArgumentException.class,
                    () -> ClusterConfig.joint(Set.of(N1), Set.of()));
            assertDoesNotThrow(() -> ClusterConfig.joint(Set.of(N1), Set.of(N2)));
        }

        @Test
        void hashCodeDistinguishesDifferentConfigs() {
            // If hashCode always returned 0, these distinct configs would collide.
            // Asserting they differ is a discriminator a constant-0 hashCode cannot
            // satisfy (hash inequality implies object inequality).
            var a = ClusterConfig.simple(Set.of(N1, N2, N3));
            var b = ClusterConfig.simple(Set.of(N1, N2));
            var c = ClusterConfig.joint(Set.of(N1, N2, N3), Set.of(N2, N3, N4));
            assertNotEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a.hashCode(), c.hashCode());
        }

        @Test
        void equalsRejectsDifferentTypeAndNull() {
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
            // Guards the `!joint` branch and the empty-string degenerate case: the
            // two shapes must render differently and non-empty, and the joint form
            // must name both sets.
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


    @Nested
    class ReadIndexQuorumBoundaries {

        @Test
        void confirmLeadershipConfirmsExactlyAtQuorumNotBelow() {
            // Pins the `ackCount >= quorumSize` boundary in confirmLeadership:
            // ack == quorum confirms; ack == quorum-1 does not.
            ReadIndexState below = new ReadIndexState();
            long r1 = below.startRead(3);
            below.confirmLeadership(r1, 2, 3);
            assertFalse(below.isReady(r1, 100), "ack below quorum must not confirm");

            ReadIndexState atQuorum = new ReadIndexState();
            long r2 = atQuorum.startRead(3);
            atQuorum.confirmLeadership(r2, 3, 3);
            assertTrue(atQuorum.isReady(r2, 100), "ack exactly at quorum must confirm");
        }

        @Test
        void confirmAllConfirmsAtQuorumBoundaryNotJustBelow() {
            // Pins the `ackCount < quorumSize` boundary in confirmAll: ack == quorum
            // confirms; ack == quorum-1 does not.
            ReadIndexState atQuorum = new ReadIndexState();
            long a = atQuorum.startRead(3);
            atQuorum.confirmAll(3, 3);
            assertTrue(atQuorum.isReady(a, 3));

            ReadIndexState below = new ReadIndexState();
            long b = below.startRead(3);
            below.confirmAll(2, 3);
            assertFalse(below.isReady(b, 100));
        }

        @Test
        void confirmLeadershipOnUnknownReadIdIsSafeNoOp() {
            // Guards the `pending == null` early return in confirmLeadership: without
            // it the method would NPE on `pending.withAck(...)`. A call on a
            // never-started readId must be a silent no-op.
            ReadIndexState state = new ReadIndexState();
            assertDoesNotThrow(() -> state.confirmLeadership(999L, 3, 2));
            assertEquals(0, state.pendingCount());
        }

        @Test
        void confirmAllLeadershipPreservesAlreadyConfirmedReads() {
            // Pins the `leadershipConfirmed() ? pending : pending.confirmed()`
            // ternary in confirmAllLeadership: an already-confirmed read must remain
            // confirmed (idempotent), and an unconfirmed one becomes confirmed.
            ReadIndexState state = new ReadIndexState();
            long confirmed = state.startRead(2);
            long fresh = state.startRead(2);
            state.confirmLeadership(confirmed, 5, 1);
            assertTrue(state.isReady(confirmed, 2));

            state.confirmAllLeadership();
            assertTrue(state.isReady(confirmed, 2), "already-confirmed read stays confirmed");
            assertTrue(state.isReady(fresh, 2), "unconfirmed read becomes confirmed");
        }
    }

}
