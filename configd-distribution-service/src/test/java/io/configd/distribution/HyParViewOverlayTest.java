package io.configd.distribution;

import io.configd.common.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class HyParViewOverlayTest {

    private HyParViewOverlay overlay;

    @BeforeEach
    void setUp() {
        overlay = new HyParViewOverlay(
                NodeId.of(0), 4, 24, 3, 3, new Random(42));
    }

    @Test
    void joinAddsToActiveView() {
        overlay.join(NodeId.of(1));
        assertTrue(overlay.activeView().contains(NodeId.of(1)));
    }

    @Test
    void receiveJoinAddsNodeAndForwards() {
        overlay.join(NodeId.of(1));
        overlay.drainOutbox();

        overlay.receiveJoin(NodeId.of(2));
        assertTrue(overlay.activeView().contains(NodeId.of(2)));

        var messages = overlay.drainOutbox();
        assertTrue(messages.stream()
                .anyMatch(m -> m instanceof HyParViewOverlay.OutboundMessage.ForwardJoin));
    }

    @Test
    void activeViewBounded() {
        for (int i = 1; i <= 10; i++) {
            overlay.join(NodeId.of(i));
        }
        assertTrue(overlay.activeView().size() <= 4);
    }

    @Test
    void evictionMovesToPassiveView() {
        for (int i = 1; i <= 5; i++) {
            overlay.join(NodeId.of(i));
        }
        // At least one node should have been moved to passive
        assertFalse(overlay.passiveView().isEmpty());
    }

    @Test
    void peerFailedRemovesFromActive() {
        overlay.join(NodeId.of(1));
        overlay.join(NodeId.of(2));
        overlay.drainOutbox();

        overlay.peerFailed(NodeId.of(1));

        // Failed peer removed from active, moved to passive
        assertFalse(overlay.activeView().contains(NodeId.of(1)));
        assertTrue(overlay.passiveView().contains(NodeId.of(1)));
    }

    @Test
    void viewChangeListenerNotified() {
        var added = new java.util.ArrayList<NodeId>();
        var removed = new java.util.ArrayList<NodeId>();

        overlay.setViewChangeListener((peer, isAdded) -> {
            if (isAdded) added.add(peer);
            else removed.add(peer);
        });

        overlay.join(NodeId.of(1));
        assertTrue(added.contains(NodeId.of(1)));

        overlay.peerFailed(NodeId.of(1));
        assertTrue(removed.contains(NodeId.of(1)));
    }

    @Test
    void selfIsNeverAddedToViews() {
        overlay.receiveJoin(NodeId.of(0)); // self
        assertFalse(overlay.activeView().contains(NodeId.of(0)));
    }

    @Test
    void shuffleExchangesPassiveEntries() {
        // Build up some passive entries
        for (int i = 1; i <= 3; i++) {
            overlay.join(NodeId.of(i));
        }
        overlay.drainOutbox();

        overlay.initiateShuffle();
        var messages = overlay.drainOutbox();
        assertTrue(messages.stream()
                .anyMatch(m -> m instanceof HyParViewOverlay.OutboundMessage.ShuffleRequest));
    }

    @Test
    void receiveDisconnectRemovesFromActive() {
        overlay.join(NodeId.of(1));
        assertTrue(overlay.activeView().contains(NodeId.of(1)));

        overlay.receiveDisconnect(NodeId.of(1));
        assertFalse(overlay.activeView().contains(NodeId.of(1)));
    }
}
