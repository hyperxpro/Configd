package io.configd.replication;

import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.raft.ProposalResult;
import io.configd.raft.RaftMessage;
import io.configd.raft.RaftNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Manages multiple Raft groups on a single node. Each tick advances all
 * groups. Messages from the transport are routed to the correct group.
 * <p>
 * Design: a single I/O thread calls {@link #tick()} which iterates all
 * groups. This is the CockroachDB "store" pattern — one I/O thread per
 * node, not per Raft group (ADR-0009). The driver itself holds no locks
 * and must be accessed from a single thread only.
 * <p>
 * Groups are identified by integer group IDs. Each group ID maps to
 * exactly one {@link RaftNode}. Adding or removing groups is expected
 * to be infrequent (configuration change) and is O(1).
 *
 * @see RaftNode
 */
public final class MultiRaftDriver {

    private final NodeId localNode;
    private final Clock clock;

    /**
     * Map from group ID to the RaftNode driving that group.
     * Iteration order is undefined; tick order among groups is not
     * guaranteed and must not be relied upon.
     */
    private final Map<Integer, RaftNode> groups;

    /**
     * Creates a new MultiRaftDriver.
     *
     * @param localNode the identifier of this node in the cluster
     * @param clock     clock source for time-dependent operations
     * @throws NullPointerException if any argument is null
     */
    public MultiRaftDriver(NodeId localNode, Clock clock) {
        this.localNode = Objects.requireNonNull(localNode, "localNode");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.groups = new HashMap<>();
    }

    // ========================================================================
    // Group management
    // ========================================================================

    /**
     * Registers a Raft group with this driver.
     *
     * @param groupId the unique identifier for this Raft group
     * @param node    the RaftNode instance driving the group's consensus
     * @throws NullPointerException     if {@code node} is null
     * @throws IllegalArgumentException if a group with the given ID is already registered
     */
    public void addGroup(int groupId, RaftNode node) {
        Objects.requireNonNull(node, "node");
        if (groups.containsKey(groupId)) {
            throw new IllegalArgumentException("Group already registered: " + groupId);
        }
        groups.put(groupId, node);
    }

    /**
     * Removes a Raft group from this driver. After removal, the group
     * will no longer be ticked and messages routed to it will be dropped.
     *
     * @param groupId the identifier of the group to remove
     * @throws IllegalArgumentException if no group with the given ID is registered
     */
    public void removeGroup(int groupId) {
        if (groups.remove(groupId) == null) {
            throw new IllegalArgumentException("Group not registered: " + groupId);
        }
    }

    // ========================================================================
    // Tick and message routing
    // ========================================================================

    /**
     * Advances all registered Raft groups by one tick.
     * <p>
     * This is the primary driver loop entry point. The caller (I/O thread)
     * invokes this at a fixed interval (e.g., every 1ms). Each call
     * iterates all groups exactly once — O(groups), not O(groups * peers).
     */
    public void tick() {
        for (RaftNode node : groups.values()) {
            node.tick();
        }
    }

    /**
     * Routes an incoming message to the correct Raft group.
     * <p>
     * If no group with the given ID is registered, the message is
     * silently dropped. This can happen during group removal or
     * when a stale message arrives for a group that has been
     * decommissioned.
     *
     * @param groupId the target Raft group identifier
     * @param message the Raft protocol message to deliver
     */
    public void routeMessage(int groupId, RaftMessage message) {
        RaftNode node = groups.get(groupId);
        if (node != null) {
            node.handleMessage(message);
        }
    }

    /**
     * Proposes a command to the specified Raft group. Only the leader
     * of that group can accept proposals.
     *
     * @param groupId the target Raft group identifier
     * @param command the command bytes to replicate
     * @return the result of the proposal attempt; {@link ProposalResult#NOT_LEADER}
     *         if the group does not exist or this node is not the leader
     */
    public ProposalResult propose(int groupId, byte[] command) {
        RaftNode node = groups.get(groupId);
        if (node == null) {
            return ProposalResult.NOT_LEADER;
        }
        return node.propose(command);
    }

    // ========================================================================
    // Query methods
    // ========================================================================

    /**
     * Returns the {@link RaftNode} for the given group, or {@code null}
     * if no such group is registered.
     *
     * @param groupId the group identifier
     * @return the RaftNode, or null
     */
    public RaftNode getGroup(int groupId) {
        return groups.get(groupId);
    }

    /**
     * Returns an unmodifiable view of the currently registered group IDs.
     *
     * @return set of group IDs; never null
     */
    public Set<Integer> groupIds() {
        return Collections.unmodifiableSet(groups.keySet());
    }

    /**
     * Returns the number of Raft groups currently registered.
     *
     * @return the group count
     */
    public int groupCount() {
        return groups.size();
    }

    /**
     * Returns the local node identifier for this driver.
     *
     * @return the local node ID
     */
    public NodeId localNode() {
        return localNode;
    }

    /**
     * Returns the clock used by this driver.
     *
     * @return the clock instance
     */
    public Clock clock() {
        return clock;
    }
}
