package io.configd.transport;

import io.configd.common.NodeId;

public interface RaftTransport {
    void send(NodeId target, Object message);
    void registerHandler(MessageHandler handler);
    
    @FunctionalInterface
    interface MessageHandler {
        void onMessage(NodeId from, Object message);
    }
}
