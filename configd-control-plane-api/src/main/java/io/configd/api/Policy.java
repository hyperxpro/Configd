package io.configd.api;

import java.util.List;
import java.util.Objects;

/**
 * A named, reusable bundle of {@link PolicyRule}s (RFC §01 A5-3 — "a policy is a set of rules").
 * <p>
 * This is the production realization of the docs-only
 * {@code docs/design/namespace-model/sketch/.../PolicySet} concept, deliberately <b>without</b> the
 * sketch's {@code Scope} or glob {@code PathPattern}: a policy here is simply a named list of literal
 * prefix rules. Immutable: the rule list is defensively copied to an unmodifiable snapshot.
 */
public record Policy(String name, List<PolicyRule> rules) {

    /**
     * @param name  the policy name (non-null)
     * @param rules the rules this policy bundles (non-null; copied — must contain no null element)
     */
    public Policy {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(rules, "rules must not be null");
        rules = List.copyOf(rules);
    }
}
