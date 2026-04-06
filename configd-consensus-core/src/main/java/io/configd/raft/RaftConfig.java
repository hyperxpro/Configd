package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable configuration for a single {@link RaftNode}.
 *
 * @param nodeId              this node's unique identifier
 * @param peers               the set of peer node identifiers (excluding this node)
 * @param electionTimeoutMinMs minimum election timeout in milliseconds (default 150)
 * @param electionTimeoutMaxMs maximum election timeout in milliseconds (default 300)
 * @param heartbeatIntervalMs  heartbeat interval in milliseconds (default 50)
 * @param maxBatchSize         maximum number of entries per AppendEntries RPC (default 64)
 * @param maxBatchBytes        maximum total bytes per AppendEntries RPC (default 256 KB)
 * @param maxPendingProposals  maximum uncommitted entries before rejecting proposals (default 1024)
 * @param maxInflightAppends   maximum in-flight AppendEntries RPCs per peer (default 10)
 */
public record RaftConfig(
        NodeId nodeId,
        Set<NodeId> peers,
        int electionTimeoutMinMs,
        int electionTimeoutMaxMs,
        int heartbeatIntervalMs,
        int maxBatchSize,
        int maxBatchBytes,
        int maxPendingProposals,
        int maxInflightAppends
) {

    public RaftConfig {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(peers, "peers must not be null");
        peers = Set.copyOf(peers); // defensive copy, unmodifiable
        if (electionTimeoutMinMs <= 0) {
            throw new IllegalArgumentException("electionTimeoutMinMs must be positive: " + electionTimeoutMinMs);
        }
        if (electionTimeoutMaxMs < electionTimeoutMinMs) {
            throw new IllegalArgumentException("electionTimeoutMaxMs must be >= electionTimeoutMinMs");
        }
        if (heartbeatIntervalMs <= 0) {
            throw new IllegalArgumentException("heartbeatIntervalMs must be positive: " + heartbeatIntervalMs);
        }
        if (heartbeatIntervalMs >= electionTimeoutMinMs) {
            throw new IllegalArgumentException(
                    "heartbeatIntervalMs (" + heartbeatIntervalMs + ") must be < electionTimeoutMinMs ("
                            + electionTimeoutMinMs + ")");
        }
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive: " + maxBatchSize);
        }
        if (maxBatchBytes <= 0) {
            throw new IllegalArgumentException("maxBatchBytes must be positive: " + maxBatchBytes);
        }
        if (maxPendingProposals <= 0) {
            throw new IllegalArgumentException("maxPendingProposals must be positive: " + maxPendingProposals);
        }
        if (maxInflightAppends <= 0) {
            throw new IllegalArgumentException("maxInflightAppends must be positive: " + maxInflightAppends);
        }
    }

    /**
     * Total number of nodes in the cluster (this node + peers).
     */
    public int clusterSize() {
        return peers.size() + 1;
    }

    /**
     * Majority quorum size: floor(clusterSize/2) + 1.
     */
    public int quorumSize() {
        return clusterSize() / 2 + 1;
    }

    /**
     * Convenience builder for tests and configuration.
     */
    public static RaftConfig of(NodeId nodeId, Set<NodeId> peers) {
        return new RaftConfig(nodeId, peers, 150, 300, 50, 64, 256 * 1024, 1024, 10);
    }
}
