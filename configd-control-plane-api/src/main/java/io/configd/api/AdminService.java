package io.configd.api;

import io.configd.common.NodeId;

import java.util.Objects;
import java.util.Set;

public final class AdminService {

    public sealed interface AdminResult {
        record Success(String message) implements AdminResult {}
        record Failure(String reason) implements AdminResult {}
        record NotLeader(NodeId leaderId) implements AdminResult {}
    }

    public interface ClusterStateProvider {
        /** The current leader, or null if unknown. */
        NodeId currentLeader();
        Set<NodeId> clusterNodes();
        boolean isLeader();
        long currentTerm();
        long commitIndex();
    }

    /**
     * Executes membership changes through the Raft protocol (not a direct membership map) to
     * preserve Raft's safety guarantees across a reconfiguration.
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

    public ClusterStatus clusterStatus() {
        return new ClusterStatus(
                stateProvider.currentLeader(),
                stateProvider.clusterNodes(),
                stateProvider.isLeader(),
                stateProvider.currentTerm(),
                stateProvider.commitIndex()
        );
    }

    public record ClusterStatus(
            NodeId leader,
            Set<NodeId> nodes,
            boolean isLeader,
            long currentTerm,
            long commitIndex
    ) {}
}
