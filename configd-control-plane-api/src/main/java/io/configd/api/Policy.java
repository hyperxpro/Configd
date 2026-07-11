package io.configd.api;

import java.util.List;
import java.util.Objects;

/**
 * A named, reusable bundle of {@link PolicyRule}s: a policy is simply a named list of literal
 * prefix rules, deliberately without a scope or glob pattern concept. Immutable: the rule list is
 * defensively copied to an unmodifiable snapshot.
 */
public record Policy(String name, List<PolicyRule> rules) {

    /**
     * @param name  the policy name (non-null)
     * @param rules the rules this policy bundles (non-null; copied, must contain no null element)
     */
    public Policy {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(rules, "rules must not be null");
        rules = List.copyOf(rules);
    }
}
