package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Transport-level multi-group heartbeat coalescer (not a RaftMessage).
 * Arises only when N>1 groups on one owner heartbeat same peer in one tick;
 * receiver demultiplexes back into per-group routeMessage(groupId, ae) calls.
 */
public record CoalescedHeartbeat(NodeId from, Map<Integer, AppendEntriesRequest> groupHeartbeats) {

    public CoalescedHeartbeat {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(groupHeartbeats, "groupHeartbeats");
        if (groupHeartbeats.isEmpty()) {
            throw new IllegalArgumentException("groupHeartbeats must not be empty");
        }
        // Immutable copy preserving insertion order for deterministic demux replay.
        groupHeartbeats = Collections.unmodifiableMap(new LinkedHashMap<>(groupHeartbeats));
    }
}
