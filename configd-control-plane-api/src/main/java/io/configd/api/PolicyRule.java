package io.configd.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One authorization rule: a literal key {@code prefix} and the capabilities it {@code allow}s and
 * {@code deny}s. ALLOW and DENY are carried as two independent sets, mirroring {@link AclService}'s
 * per-prefix {@code GrantEntry}.
 * <p>
 * Matching is literal {@code key.startsWith(prefix)}, the same matcher {@link AclService} uses. Scope
 * is not part of the rule (it is folded into the key hash at routing time). Segment-aware and glob
 * matching are deferred.
 * <p>
 * Permissive like {@code GrantEntry}: either set may be empty (an empty {@code allow} grants nothing, an
 * empty {@code deny} denies nothing). Both sets are defensively copied to unmodifiable snapshots, so the
 * record is immutable and safe to share across threads.
 */
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

    /**
     * True when this rule's prefix is an ancestor of (or equals) {@code key} - the same literal
     * {@code key.startsWith(prefix)} matcher {@link AclService#isAllowed} uses. Glob and
     * segment-aware matching are deferred and deliberately excluded here.
     *
     * @param key the config key to test (non-null)
     * @return whether this rule applies to {@code key}
     */
    public boolean matches(String key) {
        return key.startsWith(prefix);
    }

    /**
     * Empty-safe defensive immutable copy (mirrors {@code AclService.immutable}, but tolerant of an
     * empty <i>non-</i>{@code EnumSet} which would otherwise make {@link EnumSet#copyOf} throw): an
     * empty input is stored as {@link Set#of()}, a non-empty one as an unmodifiable {@link EnumSet}.
     */
    private static Set<AclService.Permission> immutable(Set<AclService.Permission> permissions) {
        return permissions.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(permissions));
    }
}
