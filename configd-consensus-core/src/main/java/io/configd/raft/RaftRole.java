package io.configd.raft;

/**
 * The three possible states of a Raft node.
 */
public enum RaftRole {
    FOLLOWER,
    CANDIDATE,
    LEADER
}
