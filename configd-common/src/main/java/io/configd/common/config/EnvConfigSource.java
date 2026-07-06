package io.configd.common.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A {@link ConfigSource} backed by environment variables, for deployments (containers, CI) where
 * {@code -D} system properties are awkward. The environment is immutable for the life of a process, so
 * it is snapshotted once at construction.
 *
 * <p>Two mappings project env names onto the dotted keyspace:
 * <ul>
 *   <li><b>Systematic:</b> every {@code CONFIGD_*} variable maps to the lowercased dotted key with
 *       {@code _} replaced by {@code .} - {@code CONFIGD_RAFT_ENCRYPTION_ENABLED} &rarr;
 *       {@code configd.raft.encryption.enabled}. This mapping cannot reconstruct camelCase, so it reaches
 *       only all-lowercase keys; camelCase keys are handled by the explicit aliases below or stay
 *       env-unreachable (which is byte-identical to today, where they were never env-readable).</li>
 *   <li><b>Legacy aliases:</b> the FOUR env names the server historically consulted are mapped
 *       explicitly to their exact (camelCase) canonical keys, so their long-standing behavior is
 *       preserved verbatim. An alias overrides the systematic mapping if both target the same key.</li>
 * </ul>
 */
public final class EnvConfigSource implements ConfigSource {

    /**
     * The env names the server read directly before config was unified, each mapped to its exact
     * canonical key. Their canonical keys are camelCase, which the systematic rule cannot produce, so
     * they MUST be registered explicitly to keep those flags working. See {@code ConfigdServer}'s
     * encryption/co-location helpers, which OR the system property with these env vars.
     */
    private static final Map<String, String> LEGACY_ALIASES = Map.of(
            "CONFIGD_ALLOW_COLOCATED_SIGNING_KEY", "configd.security.allowColocatedSigningKey",
            "CONFIGD_ENCRYPTION_AT_REST", "configd.raft.encryption.enabled",
            "CONFIGD_ENCRYPTION_REQUIRE_ENCRYPTED", "configd.raft.encryption.requireEncrypted",
            "CONFIGD_ENCRYPTION_KMS_PROVIDER", "configd.raft.encryption.kms.provider");

    private final Map<String, String> byCanonicalKey;

    /** Snapshots the current process environment. */
    public EnvConfigSource() {
        this(System.getenv());
    }

    /** Package-visible for tests: build from an explicit environment map rather than the live process env. */
    EnvConfigSource(Map<String, String> env) {
        Map<String, String> mapped = new HashMap<>();
        // Systematic first; the explicit aliases are applied last so they win on any collision.
        for (Map.Entry<String, String> e : env.entrySet()) {
            String name = e.getKey();
            if (name.startsWith("CONFIGD_")) {
                mapped.put(systematicKey(name), e.getValue());
            }
        }
        for (Map.Entry<String, String> alias : LEGACY_ALIASES.entrySet()) {
            String value = env.get(alias.getKey());
            if (value != null) {
                mapped.put(alias.getValue(), value);
            }
        }
        this.byCanonicalKey = Map.copyOf(mapped);
    }

    private static String systematicKey(String envName) {
        return envName.toLowerCase().replace('_', '.');
    }

    @Override
    public Optional<String> getString(String key) {
        return Optional.ofNullable(byCanonicalKey.get(key));
    }

    @Override
    public Set<String> keysWithPrefix(String prefix) {
        return byCanonicalKey.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .collect(Collectors.toUnmodifiableSet());
    }
}
