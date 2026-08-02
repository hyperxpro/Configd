package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Simple (single set) or joint (C_old,new) Raft cluster configuration (Raft §6).
 * Joint config requires majority from BOTH sets for commitment; ensures no two
 * independent majorities can make conflicting decisions.
 */
public final class ClusterConfig {

    private final Set<NodeId> voters;
    private final Set<NodeId> newVoters;
    private final boolean joint;

    private final Map<NodeId, Set<NodeId>> peersCache = new HashMap<>();

    private ClusterConfig(Set<NodeId> voters, Set<NodeId> newVoters) {
        this.voters = Set.copyOf(Objects.requireNonNull(voters, "voters"));
        this.newVoters = newVoters != null ? Set.copyOf(newVoters) : null;
        this.joint = newVoters != null;
    }

    public static ClusterConfig simple(Set<NodeId> voters) {
        if (voters.isEmpty()) {
            throw new IllegalArgumentException("Voters set must not be empty");
        }
        return new ClusterConfig(voters, null);
    }

    public static ClusterConfig joint(Set<NodeId> oldVoters, Set<NodeId> newVoters) {
        if (oldVoters.isEmpty() || newVoters.isEmpty()) {
            throw new IllegalArgumentException("Both voter sets must be non-empty");
        }
        return new ClusterConfig(oldVoters, newVoters);
    }

    public boolean isJoint() {
        return joint;
    }

    /** Returns C_old (joint) or sole set (simple). */
    public Set<NodeId> voters() {
        return voters;
    }

    /** Returns C_new. Throws if simple config. */
    public Set<NodeId> newVoters() {
        if (!joint) {
            throw new IllegalStateException("Not a joint configuration");
        }
        return newVoters;
    }

    public Set<NodeId> allVoters() {
        if (!joint) {
            return voters;
        }
        var all = new java.util.HashSet<>(voters);
        all.addAll(newVoters);
        return Collections.unmodifiableSet(all);
    }

    public boolean isQuorum(Set<NodeId> respondents) {
        if (!joint) {
            return countIntersection(respondents, voters) >= majorityOf(voters.size());
        }
        return countIntersection(respondents, voters) >= majorityOf(voters.size())
                && countIntersection(respondents, newVoters) >= majorityOf(newVoters.size());
    }

    public int quorumSize() {
        return majorityOf(voters.size());
    }

    public boolean isVoter(NodeId node) {
        if (voters.contains(node)) return true;
        return joint && newVoters.contains(node);
    }

    public Set<NodeId> peersOf(NodeId self) {
        return peersCache.computeIfAbsent(self, id -> {
            var peers = new java.util.HashSet<>(allVoters());
            peers.remove(id);
            return Collections.unmodifiableSet(peers);
        });
    }

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
