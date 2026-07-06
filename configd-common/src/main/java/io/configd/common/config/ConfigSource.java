package io.configd.common.config;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only, fail-closed access to configuration under a flat dotted-key namespace
 * ({@code configd.raft.shardCount}, {@code configd.security.allowColocatedSigningKey}, ...). It is the
 * single home for all operator-facing configuration; every source (system properties, environment,
 * a YAML file) presents the same keyspace so they can be layered by precedence
 * ({@link LayeredConfigSource}) without any caller knowing where a value came from.
 *
 * <p>Only {@link #getString(String)} and {@link #keysWithPrefix(String)} are primitive - every typed
 * accessor is derived from {@code getString} so a source implements just those two and inherits
 * consistent parse/fail-closed semantics. A value that is present but cannot be parsed to the requested
 * type is a {@link ConfigException}, never a silent fallback to the supplied default: the default is for
 * an ABSENT key, not a malformed one.
 */
public interface ConfigSource {

    /** The value for {@code key}, or empty if this source does not define it. Never returns blank-as-absent. */
    Optional<String> getString(String key);

    /**
     * The value for {@code key}, or a {@link ConfigException} if it is absent or blank. Use for values
     * with no sensible default whose absence must fail the boot.
     */
    default String getRequiredString(String key) {
        return getString(key)
                .filter(v -> !v.isBlank())
                .orElseThrow(() -> new ConfigException(
                        "required configuration key '" + key + "' is not set (or is blank)"));
    }

    /**
     * The value for {@code key} parsed as a base-10 int, or {@code defaultValue} if absent. A present but
     * non-integer value is a {@link ConfigException} (fail-closed): a typo in a numeric knob must fail the
     * boot, not silently fall back to the default and mask the mistake.
     */
    default int getInt(String key, int defaultValue) {
        Optional<String> v = getString(key);
        if (v.isEmpty()) {
            return defaultValue;
        }
        String s = v.get().trim();
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new ConfigException("configuration key '" + key + "' must be an integer, got: '" + s + "'", e);
        }
    }

    /** As {@link #getInt} but for a base-10 long. Present-but-unparseable is a {@link ConfigException}. */
    default long getLong(String key, long defaultValue) {
        Optional<String> v = getString(key);
        if (v.isEmpty()) {
            return defaultValue;
        }
        String s = v.get().trim();
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new ConfigException("configuration key '" + key + "' must be a long, got: '" + s + "'", e);
        }
    }

    /**
     * The value for {@code key} as a boolean ({@code true}/{@code false}, case-insensitive), or
     * {@code defaultValue} if absent. A present value that is neither is a {@link ConfigException} - this
     * accessor is STRICT so a garbage boolean fails the boot. Flags whose historical semantics accept any
     * non-{@code "true"} string as false (e.g. {@code configd.raft.witnessStrict}) must NOT use this
     * accessor; they read {@link #getString} and apply their own lenient test to stay byte-identical.
     */
    default boolean getBoolean(String key, boolean defaultValue) {
        Optional<String> v = getString(key);
        if (v.isEmpty()) {
            return defaultValue;
        }
        String s = v.get().trim();
        if (s.equalsIgnoreCase("true")) {
            return true;
        }
        if (s.equalsIgnoreCase("false")) {
            return false;
        }
        throw new ConfigException(
                "configuration key '" + key + "' must be a boolean (true/false), got: '" + s + "'");
    }

    /**
     * The value for {@code key} as a list. A YAML sequence is flattened to a comma-joined scalar at load
     * time, so both a sequence and a comma-separated scalar (the existing convention for {@code --peers},
     * {@code --strong-read-prefixes}) read identically: split on commas, trim, drop empty elements.
     * Returns an empty list when the key is absent. List elements must not themselves contain commas.
     */
    default List<String> getList(String key) {
        Optional<String> v = getString(key);
        if (v.isEmpty()) {
            return List.of();
        }
        String s = v.get().trim();
        if (s.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(e -> !e.isEmpty())
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * The set of defined keys that start with {@code prefix}. Used to enumerate repeated blocks (later
     * gates: per-issuer / per-provider config). The prefix is matched literally against the dotted keys.
     */
    Set<String> keysWithPrefix(String prefix);

    /**
     * True if ANY layer defines {@code key} with the value {@code "true"} (case-insensitive) - an
     * OR-across-layers boolean, distinct from the precedence-based {@link #getBoolean}. This exists to
     * faithfully reproduce the handful of legacy opt-in/opt-out flags whose original expression was
     * {@code Boolean.getBoolean(prop) || "true".equalsIgnoreCase(getenv(ALIAS))} - true if EITHER the
     * system property OR the environment variable is set, NOT first-present-wins. Pure precedence would
     * change their behavior (e.g. {@code -Dx=false} with the env alias {@code =true} yields {@code true}
     * today, but precedence would yield {@code false}), so those flags use this accessor. For a
     * single-layer source this is exactly "is this source's value {@code true}".
     */
    default boolean anyLayerTrue(String key) {
        return getString(key).map(v -> v.equalsIgnoreCase("true")).orElse(false);
    }

    /**
     * The ambient, process-wide config: system properties layered over the environment, with NO YAML
     * layer. This is byte-identical to reading {@code System.getProperty}/{@code System.getenv} directly
     * for the pre-existing knobs, so it is what the config-reading helpers resolve against when no
     * explicit source is threaded in (i.e. no {@code --config} file was supplied). System properties are
     * read live on every access, so a test that mutates a property mid-run sees the new value.
     */
    static ConfigSource system() {
        return SystemDefault.INSTANCE;
    }

    /** Lazy holder for the cached ambient {@link #system()} source (env is immutable; sysprops read live). */
    final class SystemDefault {
        private static final ConfigSource INSTANCE =
                LayeredConfigSource.of(new SystemPropertyConfigSource(), new EnvConfigSource());

        private SystemDefault() {
        }
    }
}
