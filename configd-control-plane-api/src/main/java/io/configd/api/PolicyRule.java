package io.configd.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public record PolicyRule(String prefix, Set<AclService.Permission> allow, Set<AclService.Permission> deny) {

    /**
     * @param prefix the literal key prefix this rule applies to (non-null)
     * @param allow  the capabilities this rule ALLOWs (non-null; may be empty)
     * @param deny   the capabilities this rule DENYs (non-null; may be empty)
     */
    public PolicyRule {
        Objects.requireNonNull(prefix, "prefix must not be null");
        Objects.requireNonNull(allow, "allow must not be null");
        Objects.requireNonNull(deny, "deny must not be null");
        allow = immutable(allow);
        deny = immutable(deny);
    }

    public boolean matches(String key) {
        return key.startsWith(prefix);
    }

    private static Set<AclService.Permission> immutable(Set<AclService.Permission> permissions) {
        return permissions.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(permissions));
    }
}
