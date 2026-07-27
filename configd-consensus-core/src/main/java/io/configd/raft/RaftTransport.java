package io.configd.raft;

import io.configd.common.NodeId;

/**
 * Raft message transport. Fire-and-forget: no delivery guarantee (Raft retransmits via heartbeats/AppendEntries).
 * send() must not block; silent drop on unreachable target.
 */
public interface RaftTransport {

    void send(NodeId target, RaftMessage message);
}
