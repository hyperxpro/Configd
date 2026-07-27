package io.configd.server;

import io.configd.edge.StrongReadKeyClass;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;


public final class StrongReadPolicy {

    
    public static final String DEFAULT_PREFIX = StrongReadKeyClass.DEFAULT_PREFIX;

    private final Set<String> prefixes;

    
    public StrongReadPolicy(Set<String> prefixes) {
        Objects.requireNonNull(prefixes, "prefixes must not be null");
        // Defensive, order-preserving copy; reject blank prefixes so an empty
        // token (e.g. a stray trailing comma) cannot silently match every key.
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

    
    public static StrongReadPolicy defaultPolicy() {
        return new StrongReadPolicy(Set.of(DEFAULT_PREFIX));
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
        return "StrongReadPolicy" + prefixes;
    }
}
