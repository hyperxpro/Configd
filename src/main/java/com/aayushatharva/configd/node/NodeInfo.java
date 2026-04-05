package com.aayushatharva.configd.node;

import java.util.Objects;

/**
 * Metadata for a Configd node in the cluster.
 */
public record NodeInfo(
        String nodeId,
        String host,
        int internalPort,
        int apiPort,
        NodeRole role,
        NodeMode mode,
        String dataCenter,
        String region
) {

    public String address() {
        return host + ":" + internalPort;
    }

    public String apiAddress() {
        return host + ":" + apiPort;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NodeInfo that)) return false;
        return Objects.equals(nodeId, that.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(nodeId);
    }
}
