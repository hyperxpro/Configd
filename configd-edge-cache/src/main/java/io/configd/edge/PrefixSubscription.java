package io.configd.edge;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

/**
 * Tracks which key prefixes this edge node is subscribed to.
 * <p>
 * Used by the distribution service to filter deltas — only mutations
 * matching at least one subscribed prefix are forwarded to this edge node.
 * <p>
 * Thread safety: backed by a {@link CopyOnWriteArraySet}, so reads
 * (including {@link #matches} and {@link #prefixes}) are lock-free and
 * never throw {@code ConcurrentModificationException}. Writes (subscribe,
 * unsubscribe) acquire the internal copy-on-write lock, which is acceptable
 * since subscription changes are rare control-plane operations.
 *
 * @see EdgeConfigClient#addSubscription(String)
 */
public final class PrefixSubscription {

    /**
     * The set of subscribed key prefixes. Copy-on-write ensures that
     * readers iterating over prefixes never see a partial write.
     */
    private final CopyOnWriteArraySet<String> prefixes = new CopyOnWriteArraySet<>();

    /**
     * Subscribes to a key prefix.
     *
     * @param prefix the key prefix to subscribe to (non-null, non-blank)
     * @return {@code true} if the prefix was newly added; {@code false} if already subscribed
     */
    public boolean subscribe(String prefix) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        return prefixes.add(prefix);
    }

    /**
     * Unsubscribes from a key prefix.
     *
     * @param prefix the key prefix to unsubscribe from (non-null)
     * @return {@code true} if the prefix was removed; {@code false} if not subscribed
     */
    public boolean unsubscribe(String prefix) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        return prefixes.remove(prefix);
    }

    /**
     * Returns {@code true} if the given key starts with any subscribed prefix.
     * <p>
     * This is the filter predicate used by the distribution service to decide
     * whether a mutation should be forwarded to this edge node.
     *
     * @param key the config key to test (non-null)
     * @return {@code true} if the key matches at least one subscribed prefix
     */
    public boolean matches(String key) {
        Objects.requireNonNull(key, "key must not be null");
        for (String prefix : prefixes) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns an unmodifiable snapshot of the currently subscribed prefixes.
     * <p>
     * The returned set is a point-in-time snapshot — subsequent subscribe or
     * unsubscribe calls do not affect it.
     *
     * @return unmodifiable set of subscribed prefixes
     */
    public Set<String> prefixes() {
        return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(prefixes));
    }

    /**
     * Returns all subscribed prefixes that match the given key.
     * <p>
     * A key may match multiple prefixes (e.g., key {@code "app.db.pool.size"}
     * matches both {@code "app."} and {@code "app.db."}).
     *
     * @param key the config key to test (non-null)
     * @return unmodifiable set of matching prefixes (may be empty)
     */
    public Set<String> matchingPrefixes(String key) {
        Objects.requireNonNull(key, "key must not be null");
        Set<String> result = prefixes.stream()
                .filter(key::startsWith)
                .collect(Collectors.toUnmodifiableSet());
        return result;
    }
}
