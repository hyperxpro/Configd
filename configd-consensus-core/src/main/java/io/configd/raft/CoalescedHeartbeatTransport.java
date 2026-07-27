package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Map;

/**
 * Drain target for coalesced heartbeats. Decides framing:
 * one group = single AppendEntriesRequest (production wire unchanged),
 * multiple groups = CoalescedHeartbeat (N>1 test-only).
 */
@FunctionalInterface
public interface CoalescedHeartbeatTransport {

    /** Called once per peer at tick end with all groups' heartbeats. Never called with empty map. */
    void sendCoalesced(NodeId peer, Map<Integer, AppendEntriesRequest> groupHeartbeats);
}
