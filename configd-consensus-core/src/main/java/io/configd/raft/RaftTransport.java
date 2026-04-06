package io.configd.raft;

import io.configd.common.NodeId;

/**
 * Abstraction for sending Raft protocol messages to peer nodes.
 * <p>
 * Implementations may use gRPC, Netty, or any other transport (ADR-0010).
 * For simulation testing (ADR-0007), a deterministic in-memory transport
 * is used to control message delivery, reordering, and loss.
 * <p>
 * Messages are fire-and-forget: the transport does not guarantee delivery.
 * Raft's built-in retransmission (via periodic heartbeats and AppendEntries)
 * handles message loss.
 */
public interface RaftTransport {

    /**
     * Sends a message to the specified target node.
     * <p>
     * This method must not block. If the target is unreachable, the
     * message is silently dropped (Raft handles retransmission).
     *
     * @param target  the destination node
     * @param message a Raft protocol message (one of the sealed message types)
     */
    void send(NodeId target, RaftMessage message);
}
