package io.configd.kms.vault;

import io.configd.common.config.ConfigSource;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class MapConfig implements ConfigSource {

    private final Map<String, String> map;

    MapConfig(Map<String, String> map) {
        this.map = map;
    }

    @Override
    public Optional<String> getString(String key) {
        return Optional.ofNullable(map.get(key));
    }

    @Override
    public Set<String> keysWithPrefix(String prefix) {
        return map.keySet().stream().filter(k -> k.startsWith(prefix)).collect(Collectors.toSet());
    }
}
