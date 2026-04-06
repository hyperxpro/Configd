package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a Raft cluster configuration, supporting both simple and
 * joint consensus configurations (Raft §6).
 * <p>
 * A <em>simple</em> configuration contains a single set of voting members.
 * A <em>joint</em> configuration (C_old,new) contains two sets: the old
 * membership and the new membership. During joint consensus, both
 * majorities must agree for commitment.
 * <p>
 * Lifecycle of a membership change:
 * <ol>
 *   <li>Leader has simple config C_old</li>
 *   <li>Leader proposes joint config C_old,new — appends to log, begins replication</li>
 *   <li>Once C_old,new is committed (requires majority of BOTH C_old AND C_new),
 *       leader proposes simple config C_new</li>
 *   <li>Once C_new is committed, transition is complete</li>
 * </ol>
 * <p>
 * Safety guarantee: at no point can two independent majorities make
 * conflicting decisions, because the joint config requires agreement
 * from both old and new quorums.
 */
public final class ClusterConfig {

    private final Set<NodeId> voters;
    private final Set<NodeId> newVoters; // null for simple config
    private final boolean joint;

    /** Cached peers-of sets, computed lazily on first call per NodeId. */
    private final Map<NodeId, Set<NodeId>> peersCache = new HashMap<>();

    private ClusterConfig(Set<NodeId> voters, Set<NodeId> newVoters) {
        this.voters = Set.copyOf(Objects.requireNonNull(voters, "voters"));
        this.newVoters = newVoters != null ? Set.copyOf(newVoters) : null;
        this.joint = newVoters != null;
    }

    /**
     * Creates a simple (non-joint) configuration with the given voting members.
     */
    public static ClusterConfig simple(Set<NodeId> voters) {
        if (voters.isEmpty()) {
            throw new IllegalArgumentException("Voters set must not be empty");
        }
        return new ClusterConfig(voters, null);
    }

    /**
     * Creates a joint consensus configuration C_old,new.
     *
     * @param oldVoters the current (old) voting members
     * @param newVoters the proposed (new) voting members
     */
    public static ClusterConfig joint(Set<NodeId> oldVoters, Set<NodeId> newVoters) {
        if (oldVoters.isEmpty() || newVoters.isEmpty()) {
            throw new IllegalArgumentException("Both voter sets must be non-empty");
        }
        return new ClusterConfig(oldVoters, newVoters);
    }

    /**
     * Returns true if this is a joint consensus configuration.
     */
    public boolean isJoint() {
        return joint;
    }

    /**
     * Returns the primary voter set (C_old for joint, the only set for simple).
     */
    public Set<NodeId> voters() {
        return voters;
    }

    /**
     * Returns the new voter set (C_new). Only valid for joint configs.
     *
     * @throws IllegalStateException if this is a simple config
     */
    public Set<NodeId> newVoters() {
        if (!joint) {
            throw new IllegalStateException("Not a joint configuration");
        }
        return newVoters;
    }

    /**
     * Returns all nodes that are voters in either the old or new config.
     * For simple configs, this equals {@link #voters()}.
     */
    public Set<NodeId> allVoters() {
        if (!joint) {
            return voters;
        }
        var all = new java.util.HashSet<>(voters);
        all.addAll(newVoters);
        return Collections.unmodifiableSet(all);
    }

    /**
     * Checks whether a given set of nodes constitutes a quorum
     * under this configuration.
     * <p>
     * For simple configs: majority of voters.
     * For joint configs: majority of BOTH old voters AND new voters.
     */
    public boolean isQuorum(Set<NodeId> respondents) {
        if (!joint) {
            return countIntersection(respondents, voters) >= majorityOf(voters.size());
        }
        return countIntersection(respondents, voters) >= majorityOf(voters.size())
                && countIntersection(respondents, newVoters) >= majorityOf(newVoters.size());
    }

    /**
     * Returns the quorum size for simple configs.
     * For joint configs, use {@link #isQuorum(Set)} instead.
     */
    public int quorumSize() {
        return majorityOf(voters.size());
    }

    /**
     * Returns true if the given node is a voter in this configuration.
     */
    public boolean isVoter(NodeId node) {
        if (voters.contains(node)) return true;
        return joint && newVoters.contains(node);
    }

    /**
     * Returns the peers of the given node (all voters except the node itself).
     * The result is cached — repeated calls with the same node return the same set.
     */
    public Set<NodeId> peersOf(NodeId self) {
        return peersCache.computeIfAbsent(self, id -> {
            var peers = new java.util.HashSet<>(allVoters());
            peers.remove(id);
            return Collections.unmodifiableSet(peers);
        });
    }

    /**
     * Returns the simple config C_new after joint consensus completes.
     * Only valid for joint configs.
     */
    public ClusterConfig transitionToNew() {
        if (!joint) {
            throw new IllegalStateException("Cannot transition from a simple config");
        }
        return simple(newVoters);
    }

    private static int majorityOf(int size) {
        return size / 2 + 1;
    }

    private static int countIntersection(Set<NodeId> a, Set<NodeId> b) {
        int count = 0;
        for (NodeId node : a) {
            if (b.contains(node)) count++;
        }
        return count;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClusterConfig that)) return false;
        return joint == that.joint
                && voters.equals(that.voters)
                && Objects.equals(newVoters, that.newVoters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(voters, newVoters, joint);
    }

    @Override
    public String toString() {
        if (!joint) {
            return "ClusterConfig[" + voters + "]";
        }
        return "ClusterConfig[JOINT old=" + voters + ", new=" + newVoters + "]";
    }
}
