package io.configd.edge;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Shared strong-read key-class predicate.
 * <p>
 * A "strong-read" key (security kill-switch, ACL/auth revocation, legal gate) MUST NOT
 * be served from bounded-stale local edge state - it is served by the control plane's
 * fail-closed linearizable ReadIndex path, or the edge fails closed. The signed chain
 * delivers these keys to every edge (so a relay cannot suppress them undetectably), and
 * the edge <b>stores</b> them (store-and-fail-closed-serve) - the serving refusal is the
 * edge process's job.
 *
 * <h2>Freshness, not confidentiality</h2>
 * The name {@code secure/} denotes a <em>freshness</em> property (always-linearizable,
 * fail-closed), <b>NOT</b> encryption or at-rest confidentiality. Strong-read values held
 * here are <b>plaintext</b> in memory (integrity-checked via HMAC) and never written to
 * edge-local disk. Do not store secret material (passwords, tokens, keys) under
 * {@code secure/} or any key; use a dedicated secret manager. See
 * {@code docs/operations/known-limitations.md}.
 *
 * <h2>Why this class exists - one source of truth for the prefix</h2>
 * Both the edge ({@link EdgeConfigClient}'s storage filter, which must ALWAYS store
 * strong-read keys regardless of subscription) and the control plane
 * ({@code io.configd.server.StrongReadPolicy}) must agree on which keys are strong-read.
 * This class is the single definition of the <b>default</b> prefix ({@value #DEFAULT_PREFIX})
 * and the matching predicate; {@code StrongReadPolicy} references {@link #DEFAULT_PREFIX}
 * for its own default rather than re-declaring the literal, so the two cannot drift.
 * <p>
 * This class deliberately carries <b>no configuration plumbing</b> - the control plane's
 * configurable prefix set, CLI flags, and {@code ServerConfig} wiring stay in
 * {@code StrongReadPolicy}. This is purely the shared constant + predicate. Edge-side,
 * the default prefix is the policy: the edge filter uses {@link #DEFAULT} unless a future
 * session threads a configured set through.
 *
 * <p>Immutable and thread-safe.
 */
public final class StrongReadKeyClass {

    /**
     * The default strong-read prefix. Kept here as the single definition;
     * {@code StrongReadPolicy.DEFAULT_PREFIX} references this constant.
     */
    public static final String DEFAULT_PREFIX = "secure/";

    /** The default key-class with the single {@link #DEFAULT_PREFIX} prefix. */
    public static final StrongReadKeyClass DEFAULT =
            new StrongReadKeyClass(Set.of(DEFAULT_PREFIX));

    private final Set<String> prefixes;

    /**
     * @param prefixes the key prefixes that mark a strong-read key; an empty set means
     *                 "no strong-read keys" (the edge filter then stores by subscription
     *                 only). Blank prefixes are rejected - a blank would match every key.
     */
    public StrongReadKeyClass(Set<String> prefixes) {
        Objects.requireNonNull(prefixes, "prefixes must not be null");
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

    /**
     * Returns {@code true} if {@code key} belongs to the strong-read class - it starts
     * with one of the configured prefixes. A strong-read key is ALWAYS stored at the edge
     * (regardless of prefix subscription) and is NEVER served from local state.
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
        return "StrongReadKeyClass" + prefixes;
    }
}
