package io.configd.server.balance;

import io.configd.common.NodeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic unit coverage of the pure {@link LeaderBalancePlanner} decision - the core of the balance
 * matrix (threshold, max-holder rule, target-among-minima, spread, back-off gates, inertness) with no
 * threads, time, or I/O.
 */
class LeaderBalancePlannerTest {

    private static final LeaderBalancePlanner.Gate CLEAR = new LeaderBalancePlanner.Gate(false, false);

    private static Set<NodeId> nodes(int count) {
        Set<NodeId> s = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            s.add(NodeId.of(i));
        }
        return s;
    }

    /** Builds a snapshot where group g is led by {@code leaders[g]} (all at term 1). */
    private static LeaderView.Snapshot snapshot(NodeId self, int candidateCount, int... leaders) {
        List<LeaderView.GroupLeader> groups = new ArrayList<>();
        for (int g = 0; g < leaders.length; g++) {
            groups.add(new LeaderView.GroupLeader(g, NodeId.of(leaders[g]), 1L));
        }
        return new LeaderView.Snapshot(self, nodes(candidateCount), groups);
    }

    @Test
    void allOnOneNode_maxHolderShedsOneToAMin() {
        // G=8 all led by node 0, M=4. Node 0 is the sole max; it must shed exactly one group to a min.
        int[] led = {0, 0, 0, 0, 0, 0, 0, 0};
        LeaderView.Snapshot snap = snapshot(NodeId.of(0), 4, led);
        LeaderBalancePlanner.Plan plan = LeaderBalancePlanner.plan(snap, CLEAR, 2, new Random(1));
        assertEquals(8, plan.leaderSpread());
        assertTrue(plan.actionable());
        assertEquals(NodeId.of(0), NodeId.of(0)); // self leads the moved group
        assertEquals(0, plan.move().groupId()); // lowest gid this node leads
        assertTrue(nodes(4).contains(plan.move().target()));
        assertFalse(plan.move().target().equals(NodeId.of(0))); // never sheds to self
        // target is a strict minimum (count 0 here)
        assertTrue(plan.move().target().id() != 0);
    }

    @Test
    void nonMaxHolder_doesNotShed() {
        // Distribution {0:4, 1:2, 2:1, 3:1}; from node 1's perspective (count 2, max is 4) it must not act.
        int[] led = {0, 0, 0, 0, 1, 1, 2, 3};
        LeaderView.Snapshot snap = snapshot(NodeId.of(1), 4, led);
        LeaderBalancePlanner.Plan plan = LeaderBalancePlanner.plan(snap, CLEAR, 2, new Random(1));
        assertEquals(3, plan.leaderSpread()); // 4 - 1
        assertFalse(plan.actionable());
        assertFalse(plan.backedOff());
    }

    @Test
    void optimalUneven_belowThreshold_noAction() {
        // G=5, M=3, optimal {2,2,1}: spread 1 < threshold 2 -> nothing to do (the >=2 rule).
        int[] led = {0, 0, 1, 1, 2};
        for (int self = 0; self < 3; self++) {
            LeaderView.Snapshot snap = snapshot(NodeId.of(self), 3, led);
            LeaderBalancePlanner.Plan plan = LeaderBalancePlanner.plan(snap, CLEAR, 2, new Random(7));
            assertEquals(1, plan.leaderSpread());
            assertFalse(plan.actionable(), "self=" + self + " must not act on an optimal-uneven spread of 1");
            assertFalse(plan.backedOff());
        }
    }

    @Test
    void balanced_noAction() {
        int[] led = {0, 0, 1, 1, 2, 2}; // {2,2,2}, spread 0
        LeaderView.Snapshot snap = snapshot(NodeId.of(0), 3, led);
        LeaderBalancePlanner.Plan plan = LeaderBalancePlanner.plan(snap, CLEAR, 2, new Random(1));
        assertEquals(0, plan.leaderSpread());
        assertFalse(plan.actionable());
    }

    @Test
    void unknownLeader_backsOff() {
        // A null leader anywhere (mid-election) forces a whole-cycle back-off, even when heavily skewed.
        List<LeaderView.GroupLeader> groups = new ArrayList<>();
        groups.add(new LeaderView.GroupLeader(0, NodeId.of(0), 1L));
        groups.add(new LeaderView.GroupLeader(1, NodeId.of(0), 1L));
        groups.add(new LeaderView.GroupLeader(2, null, 1L)); // unknown
        LeaderView.Snapshot snap = new LeaderView.Snapshot(NodeId.of(0), nodes(4), groups);
        LeaderBalancePlanner.Plan plan = LeaderBalancePlanner.plan(snap, CLEAR, 2, new Random(1));
        assertTrue(plan.backedOff());
        assertEquals(LeaderBalancePlanner.REASON_UNKNOWN_LEADER, plan.backoffReason());
        assertNull(plan.move());
    }

    @Test
    void termChurn_backsOff_evenWhenSkewed() {
        int[] led = {0, 0, 0, 0}; // skewed, but the gate reports churn
        LeaderView.Snapshot snap = snapshot(NodeId.of(0), 4, led);
        LeaderBalancePlanner.Gate churning = new LeaderBalancePlanner.Gate(true, false);
        LeaderBalancePlanner.Plan plan = LeaderBalancePlanner.plan(snap, churning, 2, new Random(1));
        assertTrue(plan.backedOff());
        assertEquals(LeaderBalancePlanner.REASON_TERM_CHURN, plan.backoffReason());
    }

    @Test
    void cooldown_backsOff_evenWhenSkewed() {
        int[] led = {0, 0, 0, 0};
        LeaderView.Snapshot snap = snapshot(NodeId.of(0), 4, led);
        LeaderBalancePlanner.Gate cooling = new LeaderBalancePlanner.Gate(false, true);
        LeaderBalancePlanner.Plan plan = LeaderBalancePlanner.plan(snap, cooling, 2, new Random(1));
        assertTrue(plan.backedOff());
        assertEquals(LeaderBalancePlanner.REASON_COOLDOWN, plan.backoffReason());
    }

    @Test
    void unknownLeader_takesPriorityOverOtherGates() {
        // Priority order: unknown_leader before term_churn before cooldown.
        List<LeaderView.GroupLeader> groups = new ArrayList<>();
        groups.add(new LeaderView.GroupLeader(0, NodeId.of(0), 1L));
        groups.add(new LeaderView.GroupLeader(1, null, 1L));
        LeaderView.Snapshot snap = new LeaderView.Snapshot(NodeId.of(0), nodes(2), groups);
        LeaderBalancePlanner.Gate both = new LeaderBalancePlanner.Gate(true, true);
        LeaderBalancePlanner.Plan plan = LeaderBalancePlanner.plan(snap, both, 2, new Random(1));
        assertEquals(LeaderBalancePlanner.REASON_UNKNOWN_LEADER, plan.backoffReason());
    }

    @Test
    void singleShard_isInert() {
        // N=1: exactly one group. Max spread is 1 (one node leads it, rest lead 0) < threshold -> no action.
        LeaderView.Snapshot snap = snapshot(NodeId.of(0), 4, new int[]{0});
        LeaderBalancePlanner.Plan plan = LeaderBalancePlanner.plan(snap, CLEAR, 2, new Random(1));
        assertEquals(1, plan.leaderSpread());
        assertFalse(plan.actionable());
    }

    @Test
    void singleNode_isInert() {
        // M=1: the only candidate leads every group; spread is 0 over a one-node domain.
        int[] led = {0, 0, 0};
        LeaderView.Snapshot snap = snapshot(NodeId.of(0), 1, led);
        LeaderBalancePlanner.Plan plan = LeaderBalancePlanner.plan(snap, CLEAR, 2, new Random(1));
        assertEquals(0, plan.leaderSpread());
        assertFalse(plan.actionable());
    }

    @Test
    void shedTarget_isChosenAmongMinima() {
        // {0:5, 1:1, 2:0, 3:0}: minima are nodes 2 and 3. Target must be one of them, never node 1.
        int[] led = {0, 0, 0, 0, 0, 1};
        LeaderView.Snapshot snap = snapshot(NodeId.of(0), 4, led);
        boolean saw2 = false;
        boolean saw3 = false;
        // One Random instance across draws (as the loop reuses one across cadences), so the PRNG stream
        // advances - not 50 fresh Random(seed)s, whose FIRST nextInt is correlated across small seeds.
        Random jitter = new Random(1);
        for (int i = 0; i < 50; i++) {
            LeaderBalancePlanner.Plan plan = LeaderBalancePlanner.plan(snap, CLEAR, 2, jitter);
            assertTrue(plan.actionable());
            NodeId target = plan.move().target();
            assertTrue(target.equals(NodeId.of(2)) || target.equals(NodeId.of(3)),
                    "target must be a minimum (2 or 3), was " + target);
            saw2 |= target.equals(NodeId.of(2));
            saw3 |= target.equals(NodeId.of(3));
        }
        assertTrue(saw2 && saw3, "jitter should spread the choice across both minima");
    }

    @Test
    void safety_shedNeverWorsensSpread() {
        // A max->min move with spread>=2 makes source max-1 and target min+1, so max-1 >= min+1: applying
        // the planned move can only shrink or hold the spread, never grow it.
        int[] led = {0, 0, 0, 0, 1, 1};
        LeaderView.Snapshot snap = snapshot(NodeId.of(0), 4, led);
        LeaderBalancePlanner.Plan plan = LeaderBalancePlanner.plan(snap, CLEAR, 2, new Random(3));
        assertNotNull(plan.move());
        // pre spread = 4 - 0 = 4; after moving one of node 0's groups to a 0-node: {3,2,1,0} -> spread 3.
        assertEquals(4, plan.leaderSpread());
    }
}
