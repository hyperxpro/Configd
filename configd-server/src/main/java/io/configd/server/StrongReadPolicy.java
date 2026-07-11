package io.configd.server;

import io.configd.edge.StrongReadKeyClass;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Decides which config keys are {@code GLOBAL}/security ("strong-read") keys that
 * MUST be served by the fail-closed linearizable read path.
 *
 * <p>The policy mandates that reads of a {@code GLOBAL}
 * (security kill-switch, ACL/auth revocation, legal gate) key MUST use the
 * linearizable root ReadIndex path and MUST <em>fail closed</em> - deny, never
 * serve a bounded-stale local copy - when that linearizable read cannot be
 * confirmed. A stale "allow" on a revoked credential is unbounded damage, so the
 * safe failure is to refuse to answer.
 *
 * <p><b>Freshness, not confidentiality.</b> "strong-read" / {@code secure/} is a
 * <em>freshness</em> guarantee, NOT encryption. At-rest encryption is optional
 * ({@code configd.raft.encryption.enabled}) and off by default; unless it is turned on, values
 * are stored plaintext at rest (integrity-checked only). Do not rely on this class alone to
 * protect secrets unless encryption at rest is enabled.
 *
 * <p>Key-class assignment here is <b>configuration-driven</b>: a key is a
 * strong-read key iff it starts with one of a configured set of prefixes
 * (default {@code secure/}). This is the minimal, testable enforcement mechanism; a fuller
 * {@code ConfigScope}-based classification (GLOBAL/REGIONAL/LOCAL routing) does not exist yet.
 * Until then the prefix set is the single source of truth for the strong-read class.
 *
 * <p>Immutable and thread-safe.
 */
public final class StrongReadPolicy {

    /**
     * Default strong-read prefix when none is configured. Sourced from the shared
     * {@link StrongReadKeyClass#DEFAULT_PREFIX} (the single definition both the control
     * plane and the edge storage filter agree on) so the two cannot drift.
     */
    public static final String DEFAULT_PREFIX = StrongReadKeyClass.DEFAULT_PREFIX;

    private final Set<String> prefixes;

    /**
     * @param prefixes the set of key prefixes that mark a strong-read key; an
     *                 empty set disables strong-read enforcement entirely
     */
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

    /** Policy with the single default {@link #DEFAULT_PREFIX} prefix. */
    public static StrongReadPolicy defaultPolicy() {
        return new StrongReadPolicy(Set.of(DEFAULT_PREFIX));
    }

    /**
     * Returns {@code true} if {@code key} belongs to the strong-read class, i.e.
     * it starts with one of the configured prefixes and so MUST be served via the
     * fail-closed linearizable path.
     */
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

    /** The configured strong-read prefixes (unmodifiable). */
    public Set<String> prefixes() {
        return prefixes;
    }

    @Override
    public String toString() {
        return "StrongReadPolicy" + prefixes;
    }
}
