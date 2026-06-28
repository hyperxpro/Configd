package io.configd.namespace;

import java.util.EnumSet;
import java.util.List;

/**
 * Evaluates a principal's effective rules (access-control.md §4; RFC A5-4) — the SAME engine the
 * control-plane read/write/list path and the edge watch path must use (§7, the consistency requirement).
 *
 * <p>The rule (A5-4): {@code authorized(C) ⟺ C ∈ (⋃ matching ALLOW caps) ∧ C ∉ (⋃ matching DENY caps)}.
 * UNION of ancestors (not longest-match-only — the deliberate supersession of the built
 * {@code AclService}, §4.2), DENY with absolute precedence, default-deny.
 */
public final class PolicySet {

    private PolicySet() {}

    /** Effective capabilities a principal holds for a CONCRETE {@code (scope, path)} (A5-4 per-key eval). */
    public static EnumSet<Capability> effectiveCaps(List<PolicyRule> rules, Scope scope, ConfigPath path) {
        EnumSet<Capability> allow = EnumSet.noneOf(Capability.class);
        EnumSet<Capability> deny = EnumSet.noneOf(Capability.class);
        for (PolicyRule r : rules) {
            if (!r.scopes().contains(scope) || !r.pattern().matches(path)) {
                continue;
            }
            (r.effect() == PolicyRule.Effect.ALLOW ? allow : deny).addAll(r.caps());
        }
        allow.removeAll(deny); // deny wins
        return allow;
    }

    /** True iff the principal holds {@code cap} on the concrete {@code (scope, path)}. */
    public static boolean authorized(List<PolicyRule> rules, Scope scope, ConfigPath path, Capability cap) {
        return effectiveCaps(rules, scope, path).contains(cap);
    }

    /**
     * True iff the principal holds {@code cap} over the ENTIRE target subtree/pattern {@code target}
     * (access-control.md §6.1 "covers all of T"; RFC A6-1). Requires an ALLOW rule whose pattern
     * CONTAINS the target and grants {@code cap}, AND no DENY rule with {@code cap} that INTERSECTS the
     * target (a deny touching any part of the target defeats whole-target coverage). This is the
     * whole-target check that makes a subtree watch/list authorizable once at subscription rather than
     * per-key (INV-WATCH-READ, A6-4).
     */
    public static boolean coversTarget(List<PolicyRule> rules, Scope scope, PathPattern target, Capability cap) {
        boolean allowed = false;
        for (PolicyRule r : rules) {
            if (!r.scopes().contains(scope) || !r.caps().contains(cap)) {
                continue;
            }
            if (r.effect() == PolicyRule.Effect.DENY && r.pattern().intersects(target)) {
                return false; // any deny overlapping the target defeats whole-target coverage
            }
            if (r.effect() == PolicyRule.Effect.ALLOW && r.pattern().contains(target)) {
                allowed = true;
            }
        }
        return allowed;
    }
}
