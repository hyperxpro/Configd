package io.configd.namespace;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One authorization rule (access-control.md §1, §4; RFC A5-3): {@code (scopes, pattern, effect) → caps}.
 * A principal's effective permission is the UNION of its matching ALLOW rules minus its matching DENY
 * rules (deny wins) — see {@link PolicySet}.
 */
public record PolicyRule(EnumSet<Scope> scopes, PathPattern pattern, Effect effect, EnumSet<Capability> caps) {

    public enum Effect { ALLOW, DENY }

    public PolicyRule {
        Objects.requireNonNull(scopes, "scopes");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(caps, "caps");
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("rule must apply to at least one scope");
        }
        if (caps.isEmpty()) {
            throw new IllegalArgumentException("rule must name at least one capability");
        }
        // defensive copies so the record is effectively immutable
        scopes = EnumSet.copyOf(scopes);
        caps = EnumSet.copyOf(caps);
    }

    /** ALLOW {@code caps} on {@code pattern} for all scopes (the common convenience). */
    public static PolicyRule allow(String pattern, Capability... caps) {
        return new PolicyRule(EnumSet.allOf(Scope.class), PathPattern.parse(pattern),
                Effect.ALLOW, EnumSet.copyOf(Set.of(caps)));
    }

    /** DENY {@code caps} on {@code pattern} for all scopes — absolute precedence (§4.1). */
    public static PolicyRule deny(String pattern, Capability... caps) {
        return new PolicyRule(EnumSet.allOf(Scope.class), PathPattern.parse(pattern),
                Effect.DENY, EnumSet.copyOf(Set.of(caps)));
    }
}
