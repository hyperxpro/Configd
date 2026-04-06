package io.configd.api;

import io.configd.common.NodeId;

import java.util.Objects;
import java.util.Set;

/**
 * Cluster administration service. Handles node membership changes,
 * leadership transfers, and cluster health queries.
 * <p>
 * Membership changes go through the Raft reconfiguration protocol
 * to maintain safety. This service is the external API surface for
 * operators and management tools.
 */
public final class AdminService {

    /**
     * Result of an admin operation.
     */
    public sealed interface AdminResult {
        record Success(String message) implements AdminResult {}
        record Failure(String reason) implements AdminResult {}
        record NotLeader(NodeId leaderId) implements AdminResult {}
    }

    /**
     * Provides cluster state information.
     */
    public interface ClusterStateProvider {
        /** Returns the current leader, or null if unknown. */
        NodeId currentLeader();
        /** Returns all known nodes in the cluster. */
        Set<NodeId> clusterNodes();
        /** Returns true if this node is the current leader. */
        boolean isLeader();
        /** Returns the current Raft term. */
        long currentTerm();
        /** Returns the commit index. */
        long commitIndex();
    }

    /**
     * Executes membership changes through the Raft protocol.
     */
    public interface MembershipChanger {
        boolean addNode(NodeId node);
        boolean removeNode(NodeId node);
        boolean transferLeadership(NodeId target);
    }

    private final ClusterStateProvider stateProvider;
    private final MembershipChanger membershipChanger;

    public AdminService(ClusterStateProvider stateProvider, MembershipChanger membershipChanger) {
        this.stateProvider = Objects.requireNonNull(stateProvider, "stateProvider must not be null");
        this.membershipChanger = Objects.requireNonNull(membershipChanger, "membershipChanger must not be null");
    }

    /**
     * Adds a node to the cluster.
     */
    public AdminResult addNode(NodeId node) {
        Objects.requireNonNull(node, "node must not be null");
        if (!stateProvider.isLeader()) {
            return new AdminResult.NotLeader(stateProvider.currentLeader());
        }
        boolean success = membershipChanger.addNode(node);
        return success
                ? new AdminResult.Success("Node " + node + " added to cluster")
                : new AdminResult.Failure("Failed to add node " + node);
    }

    /**
     * Removes a node from the cluster.
     */
    public AdminResult removeNode(NodeId node) {
        Objects.requireNonNull(node, "node must not be null");
        if (!stateProvider.isLeader()) {
            return new AdminResult.NotLeader(stateProvider.currentLeader());
        }
        boolean success = membershipChanger.removeNode(node);
        return success
                ? new AdminResult.Success("Node " + node + " removed from cluster")
                : new AdminResult.Failure("Failed to remove node " + node);
    }

    /**
     * Transfers leadership to another node.
     */
    public AdminResult transferLeadership(NodeId target) {
        Objects.requireNonNull(target, "target must not be null");
        if (!stateProvider.isLeader()) {
            return new AdminResult.NotLeader(stateProvider.currentLeader());
        }
        boolean success = membershipChanger.transferLeadership(target);
        return success
                ? new AdminResult.Success("Leadership transfer to " + target + " initiated")
                : new AdminResult.Failure("Failed to initiate leadership transfer to " + target);
    }

    /**
     * Returns current cluster state summary.
     */
    public ClusterStatus clusterStatus() {
        return new ClusterStatus(
                stateProvider.currentLeader(),
                stateProvider.clusterNodes(),
                stateProvider.isLeader(),
                stateProvider.currentTerm(),
                stateProvider.commitIndex()
        );
    }

    /**
     * Cluster state summary.
     */
    public record ClusterStatus(
            NodeId leader,
            Set<NodeId> nodes,
            boolean isLeader,
            long currentTerm,
            long commitIndex
    ) {}
}
