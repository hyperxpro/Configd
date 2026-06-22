package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A coalesced heartbeat: the per-group empty {@link AppendEntriesRequest}s that one node drained for a
 * single peer in one tick, carried as ONE message instead of one message per group (M3; ADR-0010).
 * <p>
 * This is a transport-level VALUE, deliberately <b>not</b> a {@link RaftMessage} — it never enters a
 * {@link RaftNode} (which only handles single-group messages) and it never goes on the production wire
 * at N=1 (a single-group drain sends the plain {@link AppendEntriesRequest}, so {@code FrameCodec} /
 * {@code MessageType} / the sealed {@link RaftMessage} set are all unchanged). A {@code CoalescedHeartbeat}
 * arises only when more than one group on an owner heartbeats the same peer in a tick — the N&gt;1
 * multi-group surface, which is test-only until Phase-1 sharding wires it onto the wire. The receiver
 * demultiplexes it back into per-group {@code routeMessage(groupId, ae)} calls
 * (see {@code MultiRaftDriver.routeCoalescedHeartbeat}).
 *
 * @param from            the node that sent this coalesced heartbeat
 * @param groupHeartbeats the per-group heartbeats: {@code groupId -> empty AppendEntriesRequest}
 */
public record CoalescedHeartbeat(NodeId from, Map<Integer, AppendEntriesRequest> groupHeartbeats) {

    public CoalescedHeartbeat {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(groupHeartbeats, "groupHeartbeats");
        if (groupHeartbeats.isEmpty()) {
            throw new IllegalArgumentException("groupHeartbeats must not be empty");
        }
        // Defensive immutable copy preserving group order (so demux replay is deterministic — L-1).
        groupHeartbeats = Collections.unmodifiableMap(new LinkedHashMap<>(groupHeartbeats));
    }
}
