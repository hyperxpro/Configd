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
 * Thread safety: designed for single-threaded access from the distribution
 * service I/O thread. No synchronization is used.
 */
public final class HyParViewOverlay {

    public sealed interface OutboundMessage {
        NodeId target();

        record Join(NodeId target, NodeId newNode) implements OutboundMessage {}
        record ForwardJoin(NodeId target, NodeId newNode, int ttl) implements OutboundMessage {}
        record ShuffleRequest(NodeId target, List<NodeId> sample) implements OutboundMessage {}
        record ShuffleReply(NodeId target, List<NodeId> sample) implements OutboundMessage {}
        record Disconnect(NodeId target) implements OutboundMessage {}
        record Neighbor(NodeId target, boolean highPriority) implements OutboundMessage {}
    }

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
     * @param maxActiveSize  maximum active view size (typically 4-6)
     * @param maxPassiveSize maximum passive view size (typically 24-30)
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

    public void setViewChangeListener(ViewChangeListener listener) {
        this.viewChangeListener = listener;
    }

    public void join(NodeId contactNode) {
        Objects.requireNonNull(contactNode, "contactNode must not be null");
        addToActiveView(contactNode);
        outbox.add(new OutboundMessage.Join(contactNode, localId));
    }

    public void receiveJoin(NodeId newNode) {
        if (newNode.equals(localId)) return;

        addToActiveView(newNode);
        for (NodeId peer : activeView) {
            if (!peer.equals(newNode)) {
                outbox.add(new OutboundMessage.ForwardJoin(peer, newNode, shuffleTtl));
            }
        }
    }

    public void receiveForwardJoin(NodeId newNode, int ttl) {
        if (newNode.equals(localId)) return;

        if (ttl == 0 || activeView.size() < maxActiveSize) {
            addToActiveView(newNode);
        } else {
            NodeId randomPeer = randomActiveExcluding(newNode);
            if (randomPeer != null) {
                outbox.add(new OutboundMessage.ForwardJoin(randomPeer, newNode, ttl - 1));
            }
            addToPassiveView(newNode);
        }
    }

    public void initiateShuffle() {
        if (activeView.isEmpty()) return;

        NodeId peer = randomActive();
        if (peer == null) return;

        List<NodeId> sample = samplePassiveView(shuffleLength - 1);
        sample.add(localId);
        outbox.add(new OutboundMessage.ShuffleRequest(peer, sample));
    }

    public void receiveShuffleRequest(NodeId from, List<NodeId> sample) {
        List<NodeId> reply = samplePassiveView(sample.size());
        outbox.add(new OutboundMessage.ShuffleReply(from, reply));
        integrateSample(sample);
    }

    public void receiveShuffleReply(List<NodeId> sample) {
        integrateSample(sample);
    }

    public void peerFailed(NodeId peer) {
        if (activeView.remove(peer)) {
            notifyViewChange(peer, false);
            promotePassivePeer();
            passiveView.add(peer);
        }
    }

    public void receiveDisconnect(NodeId from) {
        if (activeView.remove(from)) {
            notifyViewChange(from, false);
            promotePassivePeer();
            passiveView.add(from);
        }
    }

    public Set<NodeId> activeView() {
        return Set.copyOf(activeView);
    }

    public Set<NodeId> passiveView() {
        return Set.copyOf(passiveView);
    }

    public Queue<OutboundMessage> drainOutbox() {
        Queue<OutboundMessage> result = new LinkedList<>(outbox);
        outbox.clear();
        return result;
    }

    private void addToActiveView(NodeId peer) {
        if (peer.equals(localId) || activeView.contains(peer)) return;

        if (activeView.size() >= maxActiveSize) {
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
