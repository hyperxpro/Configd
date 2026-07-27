package io.configd.common.config;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Composes several {@link ConfigSource sources} into one, resolving each key by consulting them in
 * precedence order, highest first. The server layers them as:
 *
 * <pre>system properties &gt; environment &gt; YAML file &gt; built-in default</pre>
 *
 * where the "built-in default" is the {@code defaultValue} argument the caller passes to
 * {@code getInt}/{@code getBoolean}/... (applied here only when no source defines the key). The first
 * source that DEFINES a key wins for that key - a partially-overriding source shadows only the keys it
 * actually defines, so a deployment with no YAML file (only system properties + environment) behaves
 * identically to reading system properties and the environment directly, and every existing {@code -D}
 * and env override keeps sitting above the YAML layer.
 */
public final class LayeredConfigSource implements ConfigSource {

    private final List<ConfigSource> sources;

    private LayeredConfigSource(List<ConfigSource> sources) {
        this.sources = List.copyOf(sources);
    }

    public static LayeredConfigSource of(ConfigSource... sources) {
        if (sources.length == 0) {
            throw new IllegalArgumentException("a LayeredConfigSource needs at least one source");
        }
        return new LayeredConfigSource(List.of(sources));
    }

    @Override
    public Optional<String> getString(String key) {
        for (ConfigSource source : sources) {
            Optional<String> v = source.getString(key);
            if (v.isPresent()) {
                return v;
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean anyLayerTrue(String key) {
        // OR across every layer (not first-present-wins): matches flags whose original semantics were
        // "system-property true OR env-alias true". Each source applies its own single-layer test.
        for (ConfigSource source : sources) {
            if (source.anyLayerTrue(key)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Set<String> keysWithPrefix(String prefix) {
        Set<String> union = new TreeSet<>();
        for (ConfigSource source : sources) {
            union.addAll(source.keysWithPrefix(prefix));
        }
        return Set.copyOf(union);
    }
}
