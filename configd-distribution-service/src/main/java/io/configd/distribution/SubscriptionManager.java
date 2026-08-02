package io.configd.distribution;

import io.configd.common.NodeId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A prefix of "" (empty string) matches all keys (full-store subscription).
 * <p>
 * Thread safety: designed for single-threaded access from the distribution
 * service I/O thread. No synchronization is used.
 */
public final class SubscriptionManager {

    private final Map<NodeId, Set<String>> subscriptions;

    private final Map<String, Set<NodeId>> prefixIndex;

    public SubscriptionManager() {
        this.subscriptions = new HashMap<>();
        this.prefixIndex = new HashMap<>();
    }

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

    public Set<String> subscriptions(NodeId node) {
        Set<String> prefixes = subscriptions.get(node);
        return (prefixes != null) ? Set.copyOf(prefixes) : Set.of();
    }

    public boolean isSubscribed(NodeId node) {
        Set<String> prefixes = subscriptions.get(node);
        return prefixes != null && !prefixes.isEmpty();
    }

    public int subscriberCount() {
        return subscriptions.size();
    }

    public int prefixCount() {
        return prefixIndex.size();
    }
}
