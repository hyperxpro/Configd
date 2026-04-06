package io.configd.raft;

import io.configd.common.NodeId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClusterConfig} — simple and joint consensus configurations.
 */
class ClusterConfigTest {

    private static final NodeId N1 = NodeId.of(1);
    private static final NodeId N2 = NodeId.of(2);
    private static final NodeId N3 = NodeId.of(3);
    private static final NodeId N4 = NodeId.of(4);
    private static final NodeId N5 = NodeId.of(5);

    @Nested
    class SimpleConfig {

        @Test
        void isNotJoint() {
            var cfg = ClusterConfig.simple(Set.of(N1, N2, N3));
            assertFalse(cfg.isJoint());
        }

        @Test
        void votersReturnsConfiguredSet() {
            var cfg = ClusterConfig.simple(Set.of(N1, N2, N3));
            assertEquals(Set.of(N1, N2, N3), cfg.voters());
        }

        @Test
        void newVotersThrowsForSimpleConfig() {
            var cfg = ClusterConfig.simple(Set.of(N1, N2, N3));
            assertThrows(IllegalStateException.class, cfg::newVoters);
        }

        @Test
        void quorumSizeForThreeNodes() {
            var cfg = ClusterConfig.simple(Set.of(N1, N2, N3));
            assertEquals(2, cfg.quorumSize());
        }

        @Test
        void quorumSizeForFiveNodes() {
            var cfg = ClusterConfig.simple(Set.of(N1, N2, N3, N4, N5));
            assertEquals(3, cfg.quorumSize());
        }

        @Test
        void isQuorumWithMajority() {
            var cfg = ClusterConfig.simple(Set.of(N1, N2, N3));
            assertTrue(cfg.isQuorum(Set.of(N1, N2)));
            assertTrue(cfg.isQuorum(Set.of(N1, N2, N3)));
        }

        @Test
        void isNotQuorumWithoutMajority() {
            var cfg = ClusterConfig.simple(Set.of(N1, N2, N3));
            assertFalse(cfg.isQuorum(Set.of(N1)));
            assertFalse(cfg.isQuorum(Set.of()));
        }

        @Test
        void isVoterChecksCorrectly() {
            var cfg = ClusterConfig.simple(Set.of(N1, N2, N3));
            assertTrue(cfg.isVoter(N1));
            assertFalse(cfg.isVoter(N4));
        }

        @Test
        void peersOfExcludesSelf() {
            var cfg = ClusterConfig.simple(Set.of(N1, N2, N3));
            assertEquals(Set.of(N2, N3), cfg.peersOf(N1));
        }

        @Test
        void singleNodeConfigHasNoPeers() {
            var cfg = ClusterConfig.simple(Set.of(N1));
            assertTrue(cfg.peersOf(N1).isEmpty());
            assertEquals(1, cfg.quorumSize());
            assertTrue(cfg.isQuorum(Set.of(N1)));
        }

        @Test
        void rejectsEmptyVoters() {
            assertThrows(IllegalArgumentException.class, () -> ClusterConfig.simple(Set.of()));
        }
    }

    @Nested
    class JointConfig {

        @Test
        void isJoint() {
            var cfg = ClusterConfig.joint(Set.of(N1, N2, N3), Set.of(N1, N2, N3, N4));
            assertTrue(cfg.isJoint());
        }

        @Test
        void votersReturnsOldSet() {
            var cfg = ClusterConfig.joint(Set.of(N1, N2, N3), Set.of(N2, N3, N4));
            assertEquals(Set.of(N1, N2, N3), cfg.voters());
        }

        @Test
        void newVotersReturnsNewSet() {
            var cfg = ClusterConfig.joint(Set.of(N1, N2, N3), Set.of(N2, N3, N4));
            assertEquals(Set.of(N2, N3, N4), cfg.newVoters());
        }

        @Test
        void allVotersReturnsUnion() {
            var cfg = ClusterConfig.joint(Set.of(N1, N2, N3), Set.of(N2, N3, N4));
            assertEquals(Set.of(N1, N2, N3, N4), cfg.allVoters());
        }

        @Test
        void isQuorumRequiresBothMajorities() {
            var cfg = ClusterConfig.joint(Set.of(N1, N2, N3), Set.of(N2, N3, N4));
            // Need 2 of {1,2,3} AND 2 of {2,3,4}
            assertTrue(cfg.isQuorum(Set.of(N1, N2, N3, N4))); // all 4
            assertTrue(cfg.isQuorum(Set.of(N2, N3))); // 2/3 old, 2/3 new
            assertFalse(cfg.isQuorum(Set.of(N1, N2))); // 2/3 old but only 1/3 new
            assertFalse(cfg.isQuorum(Set.of(N3, N4))); // 1/3 old, 2/3 new
        }

        @Test
        void isVoterChecksAllVoters() {
            var cfg = ClusterConfig.joint(Set.of(N1, N2, N3), Set.of(N2, N3, N4));
            assertTrue(cfg.isVoter(N1)); // only in old
            assertTrue(cfg.isVoter(N4)); // only in new
            assertTrue(cfg.isVoter(N2)); // in both
            assertFalse(cfg.isVoter(N5)); // in neither
        }

        @Test
        void peersOfIncludesAllVotersMinusSelf() {
            var cfg = ClusterConfig.joint(Set.of(N1, N2, N3), Set.of(N2, N3, N4));
            assertEquals(Set.of(N2, N3, N4), cfg.peersOf(N1));
            assertEquals(Set.of(N1, N3, N4), cfg.peersOf(N2));
        }

        @Test
        void transitionToNewReturnsSimpleConfig() {
            var cfg = ClusterConfig.joint(Set.of(N1, N2, N3), Set.of(N2, N3, N4));
            var newCfg = cfg.transitionToNew();
            assertFalse(newCfg.isJoint());
            assertEquals(Set.of(N2, N3, N4), newCfg.voters());
        }

        @Test
        void transitionFromSimpleThrows() {
            var cfg = ClusterConfig.simple(Set.of(N1, N2, N3));
            assertThrows(IllegalStateException.class, cfg::transitionToNew);
        }
    }

    @Nested
    class Equality {

        @Test
        void equalSimpleConfigs() {
            var a = ClusterConfig.simple(Set.of(N1, N2, N3));
            var b = ClusterConfig.simple(Set.of(N1, N2, N3));
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        void unequalSimpleConfigs() {
            var a = ClusterConfig.simple(Set.of(N1, N2, N3));
            var b = ClusterConfig.simple(Set.of(N1, N2));
            assertNotEquals(a, b);
        }

        @Test
        void equalJointConfigs() {
            var a = ClusterConfig.joint(Set.of(N1, N2, N3), Set.of(N2, N3, N4));
            var b = ClusterConfig.joint(Set.of(N1, N2, N3), Set.of(N2, N3, N4));
            assertEquals(a, b);
        }
    }
}
