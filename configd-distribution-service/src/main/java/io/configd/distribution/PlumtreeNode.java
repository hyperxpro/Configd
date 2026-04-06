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
 * Plumtree epidemic broadcast tree node (ADR-0003).
 * <p>
 * Plumtree maintains two peer sets:
 * <ul>
 *   <li><b>Eager peers</b> — receive messages immediately via eager push.
 *       These form the spanning tree for efficient O(N) broadcast.</li>
 *   <li><b>Lazy peers</b> — receive only IHAVE notifications. If a node
 *       hasn't received a message after a timeout, it GRAFTs from a lazy
 *       peer, repairing the tree.</li>
 * </ul>
 * <p>
 * Message flow:
 * <ol>
 *   <li>On broadcast: send to all eager peers, send IHAVE to all lazy peers</li>
 *   <li>On receive (new message): deliver to application, forward to eager peers (except sender)</li>
 *   <li>On receive (duplicate): send PRUNE to sender (move sender to lazy set)</li>
 *   <li>On IHAVE timeout: send GRAFT to the peer (move peer from lazy to eager)</li>
 * </ol>
 * <p>
 * Thread safety: designed for single-threaded access from the distribution
 * service I/O thread. No synchronization is used.
 *
 * @see HyParViewOverlay
 */
public final class PlumtreeNode {

    /** Message metadata for deduplication and lazy repair. */
    public record MessageId(long version, long timestamp) {}

    /** Outbound message produced by the Plumtree protocol. */
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

    /** How many message IDs to retain for deduplication. */
    private final int maxReceivedHistory;

    /** Ticks to wait before GRAFTing from a lazy peer after IHAVE. */
    private final int graftTimeoutTicks;

    /**
     * Creates a Plumtree node.
     *
     * @param localId            this node's identifier
     * @param maxReceivedHistory maximum message IDs to retain for deduplication
     * @param graftTimeoutTicks  ticks to wait before GRAFTing after IHAVE
     */
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

    /**
     * Adds a peer to the eager set. Called by HyParView when a new
     * neighbor is added to the active view.
     */
    public void addEagerPeer(NodeId peer) {
        Objects.requireNonNull(peer, "peer must not be null");
        lazyPeers.remove(peer);
        eagerPeers.add(peer);
    }

    /**
     * Removes a peer entirely. Called by HyParView when a neighbor
     * is removed from the active view.
     */
    public void removePeer(NodeId peer) {
        eagerPeers.remove(peer);
        lazyPeers.remove(peer);
    }

    /**
     * Broadcasts a message to the tree. Sends eagerly to eager peers
     * and IHAVE to lazy peers.
     *
     * @param id      unique identifier for this message
     * @param payload the message payload
     */
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
     * Processes a received eager push from a peer.
     *
     * @param from    the sending peer
     * @param id      the message identifier
     * @param payload the message payload
     * @return true if this is a new message (should be delivered to application)
     */
    public boolean receiveEagerPush(NodeId from, MessageId id, byte[] payload) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(id, "id must not be null");

        if (receivedMessages.contains(id)) {
            // Duplicate — PRUNE the sender to move them to lazy set
            outbox.add(new OutboundMessage.Prune(from));
            return false;
        }

        receivedMessages.add(id);

        // Cancel any pending lazy notification for this message
        lazyNotifications.remove(id);

        // Forward to all eager peers except the sender
        for (NodeId peer : eagerPeers) {
            if (!peer.equals(from)) {
                outbox.add(new OutboundMessage.EagerPush(peer, id, payload));
            }
        }
        // Send IHAVE to lazy peers
        for (NodeId peer : lazyPeers) {
            if (!peer.equals(from)) {
                outbox.add(new OutboundMessage.IHave(peer, id));
            }
        }

        return true;
    }

    /**
     * Processes a received IHAVE notification from a lazy peer.
     * Starts a timer; if the message is not received eagerly before
     * the timer fires, we GRAFT from this peer.
     *
     * @param from the peer that has the message
     * @param id   the message identifier
     */
    public void receiveIHave(NodeId from, MessageId id) {
        if (receivedMessages.contains(id)) {
            return; // Already have it
        }
        // Record the notification with a timeout
        lazyNotifications.putIfAbsent(id, new LazyNotification(from, graftTimeoutTicks));
    }

    /**
     * Processes a PRUNE message. Moves the sender from eager to lazy set.
     */
    public void receivePrune(NodeId from) {
        if (eagerPeers.remove(from)) {
            lazyPeers.add(from);
        }
    }

    /**
     * Processes a GRAFT message. Moves the sender from lazy to eager set.
     * The sender wants eager pushes from us again.
     */
    public void receiveGraft(NodeId from) {
        lazyPeers.remove(from);
        eagerPeers.add(from);
    }

    /**
     * Advances timers by one tick. Checks for IHAVE timeouts and sends
     * GRAFT messages for messages not yet received.
     */
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
                // GRAFT: move the peer from lazy to eager and request the message
                NodeId peer = notification.from;
                lazyPeers.remove(peer);
                eagerPeers.add(peer);
                outbox.add(new OutboundMessage.Graft(peer, id));
            }
        }
    }

    /**
     * Drains and returns all pending outbound messages.
     */
    public Queue<OutboundMessage> drainOutbox() {
        Queue<OutboundMessage> result = new LinkedList<>(outbox);
        outbox.clear();
        return result;
    }

    /** Returns the current eager peer set (unmodifiable). */
    public Set<NodeId> eagerPeers() {
        return Set.copyOf(eagerPeers);
    }

    /** Returns the current lazy peer set (unmodifiable). */
    public Set<NodeId> lazyPeers() {
        return Set.copyOf(lazyPeers);
    }

    /** Returns the number of known received message IDs. */
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
