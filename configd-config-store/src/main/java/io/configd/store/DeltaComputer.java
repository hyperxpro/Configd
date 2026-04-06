package io.configd.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Computes the minimal {@link ConfigDelta} between two {@link ConfigSnapshot}s.
 * <p>
 * The algorithm iterates both snapshots to detect:
 * <ul>
 *   <li>Keys present in {@code to} but absent in {@code from} (new puts)</li>
 *   <li>Keys present in both but with different values (updated puts)</li>
 *   <li>Keys present in {@code from} but absent in {@code to} (deletes)</li>
 * </ul>
 * This is a utility class with no state — all methods are static.
 */
public final class DeltaComputer {

    private DeltaComputer() {
        // utility class
    }

    /**
     * Computes the minimal delta to transform {@code from} into {@code to}.
     *
     * @param from source snapshot (may be {@code null} or empty for full sync)
     * @param to   target snapshot (must not be null)
     * @return delta containing only the necessary mutations
     */
    public static ConfigDelta compute(ConfigSnapshot from, ConfigSnapshot to) {
        Objects.requireNonNull(to, "target snapshot must not be null");

        final ConfigSnapshot source = (from == null) ? ConfigSnapshot.EMPTY : from;

        List<ConfigMutation> mutations = new ArrayList<>();

        // Track keys seen in 'to' so we can detect deletes
        Set<String> toKeys = new HashSet<>();

        // Scan 'to' for puts (new or changed)
        to.data().forEach((key, toVal) -> {
            toKeys.add(key);
            VersionedValue fromVal = source.data().get(key);
            if (fromVal == null) {
                // New key
                mutations.add(new ConfigMutation.Put(key, toVal.valueUnsafe()));
            } else if (!Arrays.equals(fromVal.valueUnsafe(), toVal.valueUnsafe())) {
                // Changed value
                mutations.add(new ConfigMutation.Put(key, toVal.valueUnsafe()));
            }
        });

        // Scan 'from' for deletes (keys absent in 'to')
        source.data().forEach((key, fromVal) -> {
            if (!toKeys.contains(key)) {
                mutations.add(new ConfigMutation.Delete(key));
            }
        });

        return new ConfigDelta(source.version(), to.version(), mutations);
    }
}
