package io.configd.distribution;

import io.configd.common.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionManagerTest {

    private SubscriptionManager manager;

    @BeforeEach
    void setUp() {
        manager = new SubscriptionManager();
    }

    @Test
    void subscribeAndMatch() {
        NodeId node = NodeId.of(1);
        manager.subscribe(node, "feature.");

        var matched = manager.matchingNodes("feature.flags.dark-mode");
        assertTrue(matched.contains(node));
    }

    @Test
    void noMatchForDifferentPrefix() {
        manager.subscribe(NodeId.of(1), "feature.");
        assertTrue(manager.matchingNodes("config.timeout").isEmpty());
    }

    @Test
    void emptyPrefixMatchesAll() {
        NodeId node = NodeId.of(1);
        manager.subscribe(node, "");

        assertFalse(manager.matchingNodes("any.key").isEmpty());
        assertFalse(manager.matchingNodes("another.key").isEmpty());
    }

    @Test
    void multipleNodesMatchSameKey() {
        manager.subscribe(NodeId.of(1), "feature.");
        manager.subscribe(NodeId.of(2), "feature.flags.");

        var matched = manager.matchingNodes("feature.flags.dark-mode");
        assertEquals(2, matched.size());
    }

    @Test
    void unsubscribe() {
        NodeId node = NodeId.of(1);
        manager.subscribe(node, "feature.");
        assertTrue(manager.unsubscribe(node, "feature."));
        assertTrue(manager.matchingNodes("feature.x").isEmpty());
    }

    @Test
    void unsubscribeAll() {
        NodeId node = NodeId.of(1);
        manager.subscribe(node, "feature.");
        manager.subscribe(node, "config.");
        manager.unsubscribeAll(node);

        assertFalse(manager.isSubscribed(node));
        assertEquals(0, manager.subscriberCount());
    }

    @Test
    void duplicateSubscribeReturnsFalse() {
        NodeId node = NodeId.of(1);
        assertTrue(manager.subscribe(node, "feature."));
        assertFalse(manager.subscribe(node, "feature."));
    }

    @Test
    void subscriptionsForNode() {
        NodeId node = NodeId.of(1);
        manager.subscribe(node, "a.");
        manager.subscribe(node, "b.");

        var subs = manager.subscriptions(node);
        assertEquals(2, subs.size());
        assertTrue(subs.contains("a."));
        assertTrue(subs.contains("b."));
    }

    @Test
    void subscriberAndPrefixCounts() {
        manager.subscribe(NodeId.of(1), "a.");
        manager.subscribe(NodeId.of(2), "b.");
        manager.subscribe(NodeId.of(2), "a.");

        assertEquals(2, manager.subscriberCount());
        assertEquals(2, manager.prefixCount());
    }
}
