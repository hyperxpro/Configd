package com.aayushatharva.configd.node;

/**
 * Operational mode of a Configd node within a data center.
 *
 * REPLICA — stores the full dataset (corresponds to Configd v1 and v2 Level 3).
 * RELAY   — maintains connections to replicas and serves as a proxy multiplexer
 *           within a data center (v2 architecture).
 * PROXY   — functions as a persistent cache, evicting unused KV pairs (v1.5+).
 */
public enum NodeMode {
    REPLICA,
    RELAY,
    PROXY
}
