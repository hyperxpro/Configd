package io.configd.transport;

import io.configd.common.NodeId;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Routes incoming messages to the correct handler based on group ID.
 * <p>
 * In a multi-Raft deployment, each Raft group registers a handler.
 * When a framed message arrives, the router extracts the group ID from
 * the frame header and dispatches to the registered handler.
 * <p>
 * Also supports a default handler for messages not associated with a
 * specific group (e.g., Plumtree gossip, HyParView membership).
 * <p>
 * Thread safety: designed for single-threaded access from the transport
 * I/O thread. Handlers are registered during setup and not modified
 * during message processing.
 */
public final class MessageRouter {

    @FunctionalInterface
    public interface GroupMessageHandler {
        void onMessage(NodeId from, int groupId, Object message);
    }

    private final Map<Integer, GroupMessageHandler> groupHandlers;
    private GroupMessageHandler defaultHandler;

    public MessageRouter() {
        this.groupHandlers = new HashMap<>();
    }

    public void registerGroup(int groupId, GroupMessageHandler handler) {
        Objects.requireNonNull(handler, "handler must not be null");
        groupHandlers.put(groupId, handler);
    }

    public void unregisterGroup(int groupId) {
        groupHandlers.remove(groupId);
    }

    /**
     * Sets the default handler for messages that don't match any registered group.
     * Used for protocol-level messages (HyParView, Plumtree) that operate
     * outside the Raft group context.
     */
    public void setDefaultHandler(GroupMessageHandler handler) {
        this.defaultHandler = handler;
    }

    public boolean route(NodeId from, int groupId, Object message) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(message, "message must not be null");

        GroupMessageHandler handler = groupHandlers.get(groupId);
        if (handler != null) {
            handler.onMessage(from, groupId, message);
            return true;
        }
        if (defaultHandler != null) {
            defaultHandler.onMessage(from, groupId, message);
            return true;
        }
        return false;
    }

    public boolean hasHandler(int groupId) {
        return groupHandlers.containsKey(groupId);
    }

    public int groupCount() {
        return groupHandlers.size();
    }
}
