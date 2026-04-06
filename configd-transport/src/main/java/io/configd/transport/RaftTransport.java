package io.configd.transport;

import io.configd.common.NodeId;

/**
 * Transport abstraction for sending Raft and Plumtree messages.
 * Implementations: Netty (production), SimulatedNetwork (testing).
 */
public interface RaftTransport {
    
    /**
     * Send a message to the target node.
     * Non-blocking. Message may be batched with configurable delay.
     * 
     * @param target destination node
     * @param message the message to send (must be a known Raft/Plumtree message type)
     */
    void send(NodeId target, Object message);
    
    /**
     * Register a handler for incoming messages.
     * 
     * @param handler callback invoked on the Raft I/O thread when a message arrives
     */
    void registerHandler(MessageHandler handler);
    
    @FunctionalInterface
    interface MessageHandler {
        void onMessage(NodeId from, Object message);
    }
}
