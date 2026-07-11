package io.configd.api;

import java.util.List;
import java.util.Objects;

/**
 * A named role: a bundle of {@link Policy policies} a principal can hold, deliberately without a
 * scope or glob pattern concept. Immutable: the policy list is defensively copied to an
 * unmodifiable snapshot. A role is bound to {@link AclService} via {@link AclService#defineRole} and
 * resolved for a principal via {@link AclService#isAllowed(String, java.util.Set, String, AclService.Permission)}.
 */
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

    /**
     * The flattened {@link PolicyRule}s across all this role's policies, in policy-then-rule order.
     * {@link AclService#isAllowed(String, java.util.Set, String, AclService.Permission)} unions the
     * subset matching a key into the same allow/deny accumulators as the principal's own grants.
     *
     * @return an unmodifiable list of every rule in every policy of this role
     */
    public List<PolicyRule> rules() {
        return policies.stream().flatMap(p -> p.rules().stream()).toList();
    }
}
