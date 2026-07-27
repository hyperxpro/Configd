package io.configd.api;

import java.util.List;
import java.util.Objects;

public record Role(String name, List<Policy> policies) {

    /**
     * @param name     the role name (non-null)
     * @param policies the policies this role bundles (non-null; copied, must contain no null element)
     */
    public Role {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(policies, "policies must not be null");
        policies = List.copyOf(policies);
    }

    public List<PolicyRule> rules() {
        return policies.stream().flatMap(p -> p.rules().stream()).toList();
    }
}
