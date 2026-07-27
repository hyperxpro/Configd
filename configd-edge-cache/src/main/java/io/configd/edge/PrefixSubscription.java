package io.configd.edge;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

/**
 * Thread-safe subscription set: copy-on-write for writes (rare), lock-free reads.
 */
public final class PrefixSubscription {

    private final CopyOnWriteArraySet<String> prefixes = new CopyOnWriteArraySet<>();

    public boolean subscribe(String prefix) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        return prefixes.add(prefix);
    }

    public boolean unsubscribe(String prefix) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        return prefixes.remove(prefix);
    }

    public boolean matches(String key) {
        Objects.requireNonNull(key, "key must not be null");
        for (String prefix : prefixes) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return prefixes.isEmpty();
    }

    public Set<String> prefixes() {
        return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(prefixes));
    }

    public Set<String> matchingPrefixes(String key) {
        Objects.requireNonNull(key, "key must not be null");
        Set<String> result = prefixes.stream()
                .filter(key::startsWith)
                .collect(Collectors.toUnmodifiableSet());
        return result;
    }
}
