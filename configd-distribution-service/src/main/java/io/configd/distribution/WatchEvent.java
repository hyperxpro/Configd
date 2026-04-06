package io.configd.distribution;

import io.configd.store.ConfigMutation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable event representing one or more config mutations that a watcher
 * should be notified about.
 * <p>
 * Events carry the mutations themselves and the version at which they were
 * applied. The version serves as the cursor for the watcher: after processing
 * an event, the watcher's cursor advances to {@code version}.
 * <p>
 * When coalescing is active, multiple rapid mutations may be batched into a
 * single event. The {@code mutations} list contains all individual changes;
 * the {@code version} reflects the latest applied version in the batch.
 * <p>
 * The {@code affectedKeys} set is pre-computed at construction time to avoid
 * repeated allocation and iteration during fan-out dispatch (hot path).
 * <p>
 * Events are produced by the {@link WatchService} and delivered to
 * {@link WatchListener} callbacks on the distribution service I/O thread.
 *
 * @param mutations    the config mutations in this event (immutable, non-empty)
 * @param version      the store version after applying these mutations
 * @param affectedKeys the set of keys affected by mutations (pre-computed, immutable)
 */
public record WatchEvent(List<ConfigMutation> mutations, long version, Set<String> affectedKeys) {

    /**
     * Compact constructor with validation. Pre-computes the affected keys
     * set at construction time — zero allocation on the dispatch hot path.
     */
    public WatchEvent {
        Objects.requireNonNull(mutations, "mutations must not be null");
        if (mutations.isEmpty()) {
            throw new IllegalArgumentException("mutations must not be empty");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive: " + version);
        }
        mutations = List.copyOf(mutations);
        // Pre-compute affected keys for O(1) access during fan-out
        if (affectedKeys == null) {
            var keys = new HashSet<String>();
            for (ConfigMutation m : mutations) {
                keys.add(switch (m) {
                    case ConfigMutation.Put put -> put.key();
                    case ConfigMutation.Delete delete -> delete.key();
                });
            }
            affectedKeys = Set.copyOf(keys);
        }
    }

    /**
     * Creates a WatchEvent without pre-computed affectedKeys (they will be
     * computed by the compact constructor).
     */
    public WatchEvent(List<ConfigMutation> mutations, long version) {
        this(mutations, version, null);
    }

    /**
     * Creates a single-mutation event.
     *
     * @param mutation the mutation
     * @param version  the version after applying the mutation
     * @return a new WatchEvent
     */
    public static WatchEvent of(ConfigMutation mutation, long version) {
        Objects.requireNonNull(mutation, "mutation must not be null");
        return new WatchEvent(List.of(mutation), version);
    }

    /**
     * Returns true if this event contains only a single mutation.
     */
    public boolean isSingle() {
        return mutations.size() == 1;
    }

    /**
     * Returns the number of mutations in this event.
     */
    public int size() {
        return mutations.size();
    }
}
