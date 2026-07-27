package io.configd.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class DeltaComputer {

    private DeltaComputer() {
    }

    /** {@code from} may be {@code null} or empty for a full sync (delta against nothing). */
    public static ConfigDelta compute(ConfigSnapshot from, ConfigSnapshot to) {
        Objects.requireNonNull(to, "target snapshot must not be null");

        final ConfigSnapshot source = (from == null) ? ConfigSnapshot.EMPTY : from;

        List<ConfigMutation> mutations = new ArrayList<>();

        Set<String> toKeys = new HashSet<>();

        to.data().forEach((key, toVal) -> {
            toKeys.add(key);
            VersionedValue fromVal = source.data().get(key);
            if (fromVal == null) {
                mutations.add(new ConfigMutation.Put(key, toVal.valueUnsafe()));
            } else if (!Arrays.equals(fromVal.valueUnsafe(), toVal.valueUnsafe())) {
                mutations.add(new ConfigMutation.Put(key, toVal.valueUnsafe()));
            }
        });

        source.data().forEach((key, fromVal) -> {
            if (!toKeys.contains(key)) {
                mutations.add(new ConfigMutation.Delete(key));
            }
        });

        return new ConfigDelta(source.version(), to.version(), mutations);
    }
}
