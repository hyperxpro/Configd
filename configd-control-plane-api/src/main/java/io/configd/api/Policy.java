package io.configd.api;

import java.util.List;
import java.util.Objects;

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
