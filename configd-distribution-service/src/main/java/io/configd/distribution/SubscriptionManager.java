package io.configd.distribution;

import io.configd.common.NodeId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tracks prefix-based subscriptions per edge node.
 * <p>
 * Each edge node subscribes to a set of key prefixes. When a config
 * mutation is committed, the subscription manager determines which
 * edge nodes should receive the delta based on their subscriptions.
 * <p>
 * A prefix of "" (empty string) matches all keys (full-store subscription).
 * <p>
 * Thread safety: designed for single-threaded access from the distribution
 * service I/O thread. No synchronization is used.
 */
public final class SubscriptionManager {

    /** Mapping from edge node → set of subscribed prefixes. */
    private final Map<NodeId, Set<String>> subscriptions;

    /** Reverse index: prefix → set of subscribing nodes. */
    private final Map<String, Set<NodeId>> prefixIndex;

    public SubscriptionManager() {
        this.subscriptions = new HashMap<>();
        this.prefixIndex = new HashMap<>();
    }

    /**
     * Subscribes an edge node to a key prefix.
     *
     * @param node   the subscribing edge node
     * @param prefix the key prefix to subscribe to (empty string = all keys)
     * @return true if this is a new subscription for this node+prefix pair
     */
    public boolean subscribe(NodeId node, String prefix) {
        Objects.requireNonNull(node, "node must not be null");
        Objects.requireNonNull(prefix, "prefix must not be null");

        boolean added = subscriptions
                .computeIfAbsent(node, k -> new HashSet<>())
                .add(prefix);
        if (added) {
            prefixIndex
                    .computeIfAbsent(prefix, k -> new HashSet<>())
                    .add(node);
        }
        return added;
    }

    /**
     * Unsubscribes an edge node from a key prefix.
     *
     * @return true if the subscription existed and was removed
     */
    public boolean unsubscribe(NodeId node, String prefix) {
        Objects.requireNonNull(node, "node must not be null");
        Objects.requireNonNull(prefix, "prefix must not be null");

        Set<String> nodePrefixes = subscriptions.get(node);
        if (nodePrefixes == null || !nodePrefixes.remove(prefix)) {
            return false;
        }
        if (nodePrefixes.isEmpty()) {
            subscriptions.remove(node);
        }

        Set<NodeId> nodes = prefixIndex.get(prefix);
        if (nodes != null) {
            nodes.remove(node);
            if (nodes.isEmpty()) {
                prefixIndex.remove(prefix);
            }
        }
        return true;
    }

    /**
     * Removes all subscriptions for an edge node.
     *
     * @param node the edge node to unsubscribe
     */
    public void unsubscribeAll(NodeId node) {
        Set<String> prefixes = subscriptions.remove(node);
        if (prefixes != null) {
            for (String prefix : prefixes) {
                Set<NodeId> nodes = prefixIndex.get(prefix);
                if (nodes != null) {
                    nodes.remove(node);
                    if (nodes.isEmpty()) {
                        prefixIndex.remove(prefix);
                    }
                }
            }
        }
    }

    /**
     * Returns all edge nodes that should receive a mutation for the given key.
     * A node matches if any of its subscribed prefixes is a prefix of the key.
     *
     * @param key the config key being mutated
     * @return set of matching edge nodes
     */
    public Set<NodeId> matchingNodes(String key) {
        Objects.requireNonNull(key, "key must not be null");

        Set<NodeId> result = new HashSet<>();
        for (var entry : prefixIndex.entrySet()) {
            String prefix = entry.getKey();
            if (key.startsWith(prefix)) {
                result.addAll(entry.getValue());
            }
        }
        return result;
    }

    /**
     * Returns all prefixes subscribed by a specific node.
     */
    public Set<String> subscriptions(NodeId node) {
        Set<String> prefixes = subscriptions.get(node);
        return (prefixes != null) ? Set.copyOf(prefixes) : Set.of();
    }

    /**
     * Returns true if the node has any subscriptions.
     */
    public boolean isSubscribed(NodeId node) {
        Set<String> prefixes = subscriptions.get(node);
        return prefixes != null && !prefixes.isEmpty();
    }

    /**
     * Returns the total number of subscribing nodes.
     */
    public int subscriberCount() {
        return subscriptions.size();
    }

    /**
     * Returns the total number of unique prefix subscriptions across all nodes.
     */
    public int prefixCount() {
        return prefixIndex.size();
    }
}
