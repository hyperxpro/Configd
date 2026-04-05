package com.aayushatharva.configd.discovery;

import com.aayushatharva.configd.node.NodeInfo;

import java.util.List;
import java.util.Optional;

/**
 * Node discovery interface for the Configd cluster.
 */
public interface NodeDiscovery {

    /** Register this node with the discovery service. */
    void register(NodeInfo self);

    /** Deregister this node. */
    void deregister(String nodeId);

    /** Get all known nodes. */
    List<NodeInfo> getKnownNodes();

    /** Get nodes in a specific data center. */
    List<NodeInfo> getNodesInDataCenter(String dataCenter);

    /** Find the closest node to this one (by RTT). */
    Optional<NodeInfo> findClosestNode(String dataCenter);

    /** Check if a node is alive. */
    boolean isAlive(String nodeId);
}
