package io.configd.common.config;

import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A {@link ConfigSource} backed by JVM system properties ({@code -Dconfigd.raft.shardCount=4}). Values
 * are read LIVE from {@link System#getProperty(String)} on every access so a property set after this
 * source is constructed is still seen (the existing tests set properties per-test and expect the reads
 * to observe them).
 *
 * <p>The keyspace is the dotted property name unchanged, so this is the highest-precedence layer that
 * preserves every existing {@code -D} override exactly. Absence is reported as {@link Optional#empty()},
 * NOT as {@code false} - so a caller can distinguish an unset property from one explicitly set to
 * {@code "false"} (this is why the layered resolution never uses {@code Boolean.getBoolean} semantics
 * internally).
 */
public final class SystemPropertyConfigSource implements ConfigSource {

    @Override
    public Optional<String> getString(String key) {
        return Optional.ofNullable(System.getProperty(key));
    }

    @Override
    public Set<String> keysWithPrefix(String prefix) {
        Properties props = System.getProperties();
        // Snapshot the key set defensively: getProperties() is a live view and enumerating it while
        // another thread sets a property would otherwise risk a ConcurrentModificationException.
        return props.stringPropertyNames().stream()
                .filter(k -> k.startsWith(prefix))
                .collect(Collectors.toUnmodifiableSet());
    }
}
