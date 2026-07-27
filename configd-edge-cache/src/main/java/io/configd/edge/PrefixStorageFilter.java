package io.configd.edge;

import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Stateless storage filter: applies subscription after signature verification (full chain
 * delivered to every edge). Versions preserved so gap detection unchanged.
 */
final class PrefixStorageFilter {

    private final PrefixSubscription subscriptions;
    private final StrongReadKeyClass strongReadKeyClass;

    PrefixStorageFilter(PrefixSubscription subscriptions, StrongReadKeyClass strongReadKeyClass) {
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions must not be null");
        this.strongReadKeyClass =
                Objects.requireNonNull(strongReadKeyClass, "strongReadKeyClass must not be null");
    }

    // Returns filtered delta preserving versions (versions advance even if all mutations filtered out).
    // If nothing filtered, returns original to avoid allocation on common path.
    ConfigDelta filter(ConfigDelta delta) {
        Objects.requireNonNull(delta, "delta must not be null");

        if (subscriptions.prefixes().isEmpty()) {
            return delta;
        }

        List<ConfigMutation> kept = null;
        List<ConfigMutation> mutations = delta.mutations();
        for (int i = 0; i < mutations.size(); i++) {
            ConfigMutation m = mutations.get(i);
            boolean store = shouldStore(m.key());
            if (store) {
                if (kept != null) {
                    kept.add(m);
                }
            } else {
                if (kept == null) {
                    kept = new ArrayList<>(mutations.size());
                    kept.addAll(mutations.subList(0, i));
                }
            }
        }

        if (kept == null) {
            return delta;
        }
        return new ConfigDelta(delta.fromVersion(), delta.toVersion(), kept);
    }

    private boolean shouldStore(String key) {
        return subscriptions.matches(key) || strongReadKeyClass.isStrongReadKey(key);
    }
}
