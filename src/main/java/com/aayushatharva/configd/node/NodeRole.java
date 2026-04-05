package com.aayushatharva.configd.node;

/**
 * Position in the replication hierarchy.
 *
 * ROOT nodes are the write ingestion point (hyperscale core data centers).
 * INTERMEDIATE nodes replicate from roots and serve as fan-out points.
 * LEAF nodes serve end-user traffic and receive updates from intermediates.
 */
public enum NodeRole {
    ROOT,
    INTERMEDIATE,
    LEAF
}
