package io.configd.distribution;

import io.configd.common.NodeId;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * Thread safety: designed for single-threaded access from the distribution
 * service I/O thread. No synchronization is used.
 */
public final class PlumtreeNode {

    public record MessageId(long version, long timestamp) {}

    public sealed interface OutboundMessage {
        NodeId target();

        record EagerPush(NodeId target, MessageId id, byte[] payload) implements OutboundMessage {}
        record IHave(NodeId target, MessageId id) implements OutboundMessage {}
        record Prune(NodeId target) implements OutboundMessage {}
        record Graft(NodeId target, MessageId id) implements OutboundMessage {}
    }

    private final NodeId localId;
    private final Set<NodeId> eagerPeers;
    private final Set<NodeId> lazyPeers;
    private final Set<MessageId> receivedMessages;
    private final Map<MessageId, LazyNotification> lazyNotifications;
    private final Queue<OutboundMessage> outbox;

    private final int maxReceivedHistory;

    private final int graftTimeoutTicks;

    public PlumtreeNode(NodeId localId, int maxReceivedHistory, int graftTimeoutTicks) {
        this.localId = Objects.requireNonNull(localId, "localId must not be null");
        this.maxReceivedHistory = maxReceivedHistory;
        this.graftTimeoutTicks = graftTimeoutTicks;
        this.eagerPeers = new LinkedHashSet<>();
        this.lazyPeers = new LinkedHashSet<>();
        this.receivedMessages = Collections.newSetFromMap(new java.util.LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<MessageId, Boolean> eldest) {
                return size() > maxReceivedHistory;
            }
        });
        this.lazyNotifications = new HashMap<>();
        this.outbox = new LinkedList<>();
    }

    /** Called by HyParView when a new neighbor is added to the active view. */
    public void addEagerPeer(NodeId peer) {
        Objects.requireNonNull(peer, "peer must not be null");
        lazyPeers.remove(peer);
        eagerPeers.add(peer);
    }

    /** Called by HyParView when a neighbor is removed from the active view. */
    public void removePeer(NodeId peer) {
        eagerPeers.remove(peer);
        lazyPeers.remove(peer);
    }

    public void broadcast(MessageId id, byte[] payload) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(payload, "payload must not be null");

        receivedMessages.add(id);

        for (NodeId peer : eagerPeers) {
            outbox.add(new OutboundMessage.EagerPush(peer, id, payload));
        }
        for (NodeId peer : lazyPeers) {
            outbox.add(new OutboundMessage.IHave(peer, id));
        }
    }

    /**
     * @return true if this is a new message (should be delivered to application)
     */
    public boolean receiveEagerPush(NodeId from, MessageId id, byte[] payload) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(id, "id must not be null");

        if (receivedMessages.contains(id)) {
            outbox.add(new OutboundMessage.Prune(from));
            return false;
        }

        receivedMessages.add(id);
        lazyNotifications.remove(id);

        for (NodeId peer : eagerPeers) {
            if (!peer.equals(from)) {
                outbox.add(new OutboundMessage.EagerPush(peer, id, payload));
            }
        }
        for (NodeId peer : lazyPeers) {
            if (!peer.equals(from)) {
                outbox.add(new OutboundMessage.IHave(peer, id));
            }
        }

        return true;
    }

    /**
     * Starts a timer; if the message is not received eagerly before the timer
     * fires, GRAFTs from this peer.
     */
    public void receiveIHave(NodeId from, MessageId id) {
        if (receivedMessages.contains(id)) {
            return;
        }
        lazyNotifications.putIfAbsent(id, new LazyNotification(from, graftTimeoutTicks));
    }

    public void receivePrune(NodeId from) {
        if (eagerPeers.remove(from)) {
            lazyPeers.add(from);
        }
    }

    public void receiveGraft(NodeId from) {
        lazyPeers.remove(from);
        eagerPeers.add(from);
    }

    public void tick() {
        var expired = new HashSet<MessageId>();
        for (var entry : lazyNotifications.entrySet()) {
            LazyNotification notification = entry.getValue();
            notification.remainingTicks--;
            if (notification.remainingTicks <= 0) {
                expired.add(entry.getKey());
            }
        }
        for (MessageId id : expired) {
            LazyNotification notification = lazyNotifications.remove(id);
            if (notification != null && !receivedMessages.contains(id)) {
                NodeId peer = notification.from;
                lazyPeers.remove(peer);
                eagerPeers.add(peer);
                outbox.add(new OutboundMessage.Graft(peer, id));
            }
        }
    }

    public Queue<OutboundMessage> drainOutbox() {
        Queue<OutboundMessage> result = new LinkedList<>(outbox);
        outbox.clear();
        return result;
    }

    public Set<NodeId> eagerPeers() {
        return Set.copyOf(eagerPeers);
    }

    public Set<NodeId> lazyPeers() {
        return Set.copyOf(lazyPeers);
    }

    public int receivedCount() {
        return receivedMessages.size();
    }

    private static final class LazyNotification {
        final NodeId from;
        int remainingTicks;

        LazyNotification(NodeId from, int remainingTicks) {
            this.from = from;
            this.remainingTicks = remainingTicks;
        }
    }
}
