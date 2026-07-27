package io.configd.edge;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Strong-read key predicate: keys MUST be served linearizable-read only (fail-closed),
 * never from bounded-stale edge state. Immutable, single source of truth (edge+control-plane).
 * {@code secure/} is a freshness property, NOT encryption — plaintext in memory.
 */
public final class StrongReadKeyClass {

    public static final String DEFAULT_PREFIX = "secure/";

    public static final StrongReadKeyClass DEFAULT =
            new StrongReadKeyClass(Set.of(DEFAULT_PREFIX));

    private final Set<String> prefixes;

    public StrongReadKeyClass(Set<String> prefixes) {
        Objects.requireNonNull(prefixes, "prefixes must not be null");
        Set<String> copy = new LinkedHashSet<>();
        for (String p : prefixes) {
            Objects.requireNonNull(p, "strong-read prefix must not be null");
            if (p.isBlank()) {
                throw new IllegalArgumentException(
                        "strong-read prefix must not be blank (a blank prefix would match every key)");
            }
            copy.add(p);
        }
        this.prefixes = Set.copyOf(copy);
    }

    public boolean isStrongReadKey(String key) {
        if (key == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> prefixes() {
        return prefixes;
    }

    @Override
    public String toString() {
        return "StrongReadKeyClass" + prefixes;
    }
}
