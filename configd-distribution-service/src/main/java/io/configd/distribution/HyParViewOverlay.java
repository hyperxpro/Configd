package io.configd.distribution;

import io.configd.common.NodeId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * HyParView overlay network management (ADR-0003).
 * <p>
 * HyParView maintains two views of the network:
 * <ul>
 *   <li><b>Active view</b> — small set of peers with open connections.
 *       These peers are used for eager push in Plumtree. Typical size: 4-6.</li>
 *   <li><b>Passive view</b> — larger set of peers used for recovery when
 *       active view members fail. Typical size: 24-30.</li>
 * </ul>
 * <p>
 * Membership protocol:
 * <ul>
 *   <li><b>Join</b> — new node contacts a known peer; that peer adds it to
 *       its active view and forwards a ForwardJoin to random active peers.</li>
 *   <li><b>Shuffle</b> — periodic exchange of passive view entries between
 *       active peers, ensuring passive views remain fresh.</li>
 *   <li><b>Disconnect</b> — when an active peer is removed, it is moved to
 *       the passive view and a passive peer is promoted.</li>
 * </ul>
 * <p>
 * Thread safety: designed for single-threaded access from the distribution
 * service I/O thread. No synchronization is used.
 */
public final class HyParViewOverlay {

    /** Outbound protocol messages. */
    public sealed interface OutboundMessage {
        NodeId target();

        record Join(NodeId target, NodeId newNode) implements OutboundMessage {}
        record ForwardJoin(NodeId target, NodeId newNode, int ttl) implements OutboundMessage {}
        record ShuffleRequest(NodeId target, List<NodeId> sample) implements OutboundMessage {}
        record ShuffleReply(NodeId target, List<NodeId> sample) implements OutboundMessage {}
        record Disconnect(NodeId target) implements OutboundMessage {}
        record Neighbor(NodeId target, boolean highPriority) implements OutboundMessage {}
    }

    /** Callback for active view changes (used to update PlumtreeNode). */
    @FunctionalInterface
    public interface ViewChangeListener {
        void onViewChange(NodeId peer, boolean added);
    }

    private final NodeId localId;
    private final int maxActiveSize;
    private final int maxPassiveSize;
    private final int shuffleLength;
    private final int shuffleTtl;
    private final RandomGenerator random;

    private final Set<NodeId> activeView;
    private final Set<NodeId> passiveView;
    private final Queue<OutboundMessage> outbox;
    private ViewChangeListener viewChangeListener;

    /**
     * Creates a HyParView overlay.
     *
     * @param localId        this node's identifier
     * @param maxActiveSize  maximum active view size (typically 4-6)
     * @param maxPassiveSize maximum passive view size (typically 24-30)
     * @param shuffleLength  number of entries per shuffle exchange
     * @param shuffleTtl     TTL for ForwardJoin messages
     * @param random         random generator (seeded for deterministic testing)
     */
    public HyParViewOverlay(NodeId localId, int maxActiveSize, int maxPassiveSize,
                             int shuffleLength, int shuffleTtl, RandomGenerator random) {
        this.localId = Objects.requireNonNull(localId, "localId must not be null");
        this.maxActiveSize = maxActiveSize;
        this.maxPassiveSize = maxPassiveSize;
        this.shuffleLength = shuffleLength;
        this.shuffleTtl = shuffleTtl;
        this.random = Objects.requireNonNull(random, "random must not be null");
        this.activeView = new HashSet<>();
        this.passiveView = new HashSet<>();
        this.outbox = new LinkedList<>();
    }

    /**
     * Sets the listener for active view changes.
     */
    public void setViewChangeListener(ViewChangeListener listener) {
        this.viewChangeListener = listener;
    }

    /**
     * Initiates a join by contacting a known peer (bootstrap node).
     *
     * @param contactNode the peer to join through
     */
    public void join(NodeId contactNode) {
        Objects.requireNonNull(contactNode, "contactNode must not be null");
        addToActiveView(contactNode);
        outbox.add(new OutboundMessage.Join(contactNode, localId));
    }

    /**
     * Processes a Join request from a new node.
     */
    public void receiveJoin(NodeId newNode) {
        if (newNode.equals(localId)) return;

        addToActiveView(newNode);
        // Forward to all active peers with TTL
        for (NodeId peer : activeView) {
            if (!peer.equals(newNode)) {
                outbox.add(new OutboundMessage.ForwardJoin(peer, newNode, shuffleTtl));
            }
        }
    }

    /**
     * Processes a ForwardJoin message. If TTL reaches 0 or the active view
     * is not full, add the node. Otherwise decrement TTL and forward.
     */
    public void receiveForwardJoin(NodeId newNode, int ttl) {
        if (newNode.equals(localId)) return;

        if (ttl == 0 || activeView.size() < maxActiveSize) {
            addToActiveView(newNode);
        } else {
            // Forward to a random active peer (not the new node)
            NodeId randomPeer = randomActiveExcluding(newNode);
            if (randomPeer != null) {
                outbox.add(new OutboundMessage.ForwardJoin(randomPeer, newNode, ttl - 1));
            }
            // Add to passive view regardless
            addToPassiveView(newNode);
        }
    }

    /**
     * Initiates a shuffle exchange with a random active peer.
     * Called periodically (e.g., every 30 seconds) to refresh passive views.
     */
    public void initiateShuffle() {
        if (activeView.isEmpty()) return;

        NodeId peer = randomActive();
        if (peer == null) return;

        List<NodeId> sample = samplePassiveView(shuffleLength - 1);
        sample.add(localId);
        outbox.add(new OutboundMessage.ShuffleRequest(peer, sample));
    }

    /**
     * Processes a shuffle request. Selects a sample from the passive view,
     * sends it back, and integrates the received sample.
     */
    public void receiveShuffleRequest(NodeId from, List<NodeId> sample) {
        List<NodeId> reply = samplePassiveView(sample.size());
        outbox.add(new OutboundMessage.ShuffleReply(from, reply));
        integrateSample(sample);
    }

    /**
     * Processes a shuffle reply. Integrates the received sample into
     * the passive view.
     */
    public void receiveShuffleReply(List<NodeId> sample) {
        integrateSample(sample);
    }

    /**
     * Handles a peer failure. Removes the peer from the active view
     * and promotes a passive peer to maintain connectivity.
     */
    public void peerFailed(NodeId peer) {
        if (activeView.remove(peer)) {
            notifyViewChange(peer, false);
            promotePassivePeer();
            passiveView.add(peer);
        }
    }

    /**
     * Processes a Disconnect notification from a peer.
     */
    public void receiveDisconnect(NodeId from) {
        if (activeView.remove(from)) {
            notifyViewChange(from, false);
            promotePassivePeer();
            passiveView.add(from);
        }
    }

    /** Returns the active view (unmodifiable). */
    public Set<NodeId> activeView() {
        return Set.copyOf(activeView);
    }

    /** Returns the passive view (unmodifiable). */
    public Set<NodeId> passiveView() {
        return Set.copyOf(passiveView);
    }

    /** Drains all pending outbound messages. */
    public Queue<OutboundMessage> drainOutbox() {
        Queue<OutboundMessage> result = new LinkedList<>(outbox);
        outbox.clear();
        return result;
    }

    // ---- Internal helpers ----

    private void addToActiveView(NodeId peer) {
        if (peer.equals(localId) || activeView.contains(peer)) return;

        if (activeView.size() >= maxActiveSize) {
            // Evict a random active peer to make room
            NodeId evicted = randomActive();
            if (evicted != null) {
                activeView.remove(evicted);
                notifyViewChange(evicted, false);
                passiveView.add(evicted);
                outbox.add(new OutboundMessage.Disconnect(evicted));
            }
        }

        passiveView.remove(peer);
        activeView.add(peer);
        notifyViewChange(peer, true);
    }

    private void addToPassiveView(NodeId peer) {
        if (peer.equals(localId) || activeView.contains(peer)) return;

        if (passiveView.size() >= maxPassiveSize) {
            // Evict a random passive peer
            var it = passiveView.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        passiveView.add(peer);
    }

    private void promotePassivePeer() {
        if (passiveView.isEmpty()) return;
        NodeId promoted = randomPassive();
        if (promoted != null) {
            passiveView.remove(promoted);
            activeView.add(promoted);
            notifyViewChange(promoted, true);
            outbox.add(new OutboundMessage.Neighbor(promoted, activeView.size() <= 1));
        }
    }

    private List<NodeId> samplePassiveView(int count) {
        List<NodeId> all = new ArrayList<>(passiveView);
        int n = Math.min(count, all.size());
        List<NodeId> sample = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int idx = random.nextInt(all.size());
            sample.add(all.remove(idx));
        }
        return sample;
    }

    private void integrateSample(List<NodeId> sample) {
        for (NodeId node : sample) {
            if (!node.equals(localId) && !activeView.contains(node)) {
                addToPassiveView(node);
            }
        }
    }

    private NodeId randomActive() {
        if (activeView.isEmpty()) return null;
        int idx = random.nextInt(activeView.size());
        var it = activeView.iterator();
        for (int i = 0; i < idx; i++) it.next();
        return it.next();
    }

    private NodeId randomActiveExcluding(NodeId exclude) {
        List<NodeId> candidates = new ArrayList<>();
        for (NodeId n : activeView) {
            if (!n.equals(exclude)) candidates.add(n);
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }

    private NodeId randomPassive() {
        if (passiveView.isEmpty()) return null;
        int idx = random.nextInt(passiveView.size());
        var it = passiveView.iterator();
        for (int i = 0; i < idx; i++) it.next();
        return it.next();
    }

    private void notifyViewChange(NodeId peer, boolean added) {
        if (viewChangeListener != null) {
            viewChangeListener.onViewChange(peer, added);
        }
    }
}
