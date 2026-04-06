package io.configd.distribution;

import io.configd.common.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlumtreeNodeTest {

    private PlumtreeNode node;

    @BeforeEach
    void setUp() {
        node = new PlumtreeNode(NodeId.of(0), 1000, 5);
    }

    @Test
    void broadcastSendsToEagerAndLazy() {
        NodeId eager = NodeId.of(1);
        NodeId lazy = NodeId.of(2);
        node.addEagerPeer(eager);
        node.addEagerPeer(lazy);
        node.receivePrune(lazy); // move to lazy

        var id = new PlumtreeNode.MessageId(1, 100);
        node.broadcast(id, new byte[]{1, 2, 3});

        var messages = node.drainOutbox();
        long eagerCount = messages.stream()
                .filter(m -> m instanceof PlumtreeNode.OutboundMessage.EagerPush).count();
        long ihaveCount = messages.stream()
                .filter(m -> m instanceof PlumtreeNode.OutboundMessage.IHave).count();

        assertEquals(1, eagerCount);
        assertEquals(1, ihaveCount);
    }

    @Test
    void receiveNewMessageReturnsTrue() {
        NodeId sender = NodeId.of(1);
        node.addEagerPeer(sender);

        var id = new PlumtreeNode.MessageId(1, 100);
        assertTrue(node.receiveEagerPush(sender, id, new byte[]{1}));
    }

    @Test
    void receiveDuplicatePrunesSender() {
        NodeId sender = NodeId.of(1);
        node.addEagerPeer(sender);

        var id = new PlumtreeNode.MessageId(1, 100);
        node.receiveEagerPush(sender, id, new byte[]{1});
        node.drainOutbox();

        // Receive same message again
        assertFalse(node.receiveEagerPush(sender, id, new byte[]{1}));
        var messages = node.drainOutbox();
        assertTrue(messages.stream()
                .anyMatch(m -> m instanceof PlumtreeNode.OutboundMessage.Prune p
                        && p.target().equals(sender)));
    }

    @Test
    void pruneMovesFromEagerToLazy() {
        NodeId peer = NodeId.of(1);
        node.addEagerPeer(peer);
        assertTrue(node.eagerPeers().contains(peer));

        node.receivePrune(peer);
        assertFalse(node.eagerPeers().contains(peer));
        assertTrue(node.lazyPeers().contains(peer));
    }

    @Test
    void graftMovesFromLazyToEager() {
        NodeId peer = NodeId.of(1);
        node.addEagerPeer(peer);
        node.receivePrune(peer);
        assertTrue(node.lazyPeers().contains(peer));

        node.receiveGraft(peer);
        assertTrue(node.eagerPeers().contains(peer));
        assertFalse(node.lazyPeers().contains(peer));
    }

    @Test
    void ihaveTimeoutTriggersGraft() {
        NodeId lazyPeer = NodeId.of(1);
        node.addEagerPeer(lazyPeer);
        node.receivePrune(lazyPeer);

        var id = new PlumtreeNode.MessageId(99, 200);
        node.receiveIHave(lazyPeer, id);

        // Tick past timeout
        for (int i = 0; i < 6; i++) {
            node.tick();
        }

        var messages = node.drainOutbox();
        assertTrue(messages.stream()
                .anyMatch(m -> m instanceof PlumtreeNode.OutboundMessage.Graft g
                        && g.target().equals(lazyPeer)));
        // Peer should now be eager
        assertTrue(node.eagerPeers().contains(lazyPeer));
    }

    @Test
    void ihaveForAlreadyReceivedIsIgnored() {
        NodeId sender = NodeId.of(1);
        NodeId lazyPeer = NodeId.of(2);
        node.addEagerPeer(sender);
        node.addEagerPeer(lazyPeer);
        node.receivePrune(lazyPeer);

        var id = new PlumtreeNode.MessageId(1, 100);
        node.receiveEagerPush(sender, id, new byte[]{1});
        node.drainOutbox();

        node.receiveIHave(lazyPeer, id);
        for (int i = 0; i < 10; i++) node.tick();

        var messages = node.drainOutbox();
        assertTrue(messages.stream()
                .noneMatch(m -> m instanceof PlumtreeNode.OutboundMessage.Graft));
    }

    @Test
    void forwardsToEagerPeersExceptSender() {
        NodeId sender = NodeId.of(1);
        NodeId peer2 = NodeId.of(2);
        NodeId peer3 = NodeId.of(3);
        node.addEagerPeer(sender);
        node.addEagerPeer(peer2);
        node.addEagerPeer(peer3);

        var id = new PlumtreeNode.MessageId(1, 100);
        node.receiveEagerPush(sender, id, new byte[]{1});

        var messages = node.drainOutbox();
        long eagerPushCount = messages.stream()
                .filter(m -> m instanceof PlumtreeNode.OutboundMessage.EagerPush)
                .count();
        // Should forward to peer2 and peer3, not sender
        assertEquals(2, eagerPushCount);
        assertTrue(messages.stream()
                .filter(m -> m instanceof PlumtreeNode.OutboundMessage.EagerPush)
                .noneMatch(m -> ((PlumtreeNode.OutboundMessage.EagerPush) m).target().equals(sender)));
    }
}
