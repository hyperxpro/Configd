package io.configd.common;

/**
 * Defines the replication scope for a configuration key.
 * Determines which Raft group handles writes for this key.
 */
public enum ConfigScope {
    /** Replicated across all regions via global Raft group. */
    GLOBAL,
    
    /** Replicated within a single region via regional Raft group. */
    REGIONAL,
    
    /** Not replicated. Local to a single node. */
    LOCAL
}
