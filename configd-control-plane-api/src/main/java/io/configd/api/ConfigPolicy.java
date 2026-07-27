package io.configd.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable snapshot of the config-sourced authorization policy: the role definitions and
 * principal-to-role bindings loaded from the reserved {@code _acl/} key subtree.
 * <p>
 * This is a SEPARATE, additive layer on top of {@link AclService}'s static imperative grant layer (the
 * per-prefix {@code acls} grants plus the imperative {@code defineRole}/{@code assignRole} maps). It is
 * published into {@link AclService} via a single volatile reference swap
 * ({@link AclService#publishConfigPolicy}), mirroring {@code VersionedConfigStore}'s one-volatile-snapshot
 * discipline so a concurrent {@link AclService#isAllowed} reads the whole policy exactly once and never
 * observes a half-applied (torn) reload. The snapshot is deeply immutable, so holding a reference to it is
 * always safe.
 * <p>
 * <b>Empty by default ({@link #EMPTY}).</b> An unconfigured deployment defines no {@code _acl/} keys, so
 * the config-policy snapshot is empty and contributes nothing to any authorization decision.
 * <p>
 * <b>Roles + bindings only (Vault-shaped).</b> Config authority flows through {@link Role}s (which bundle
 * {@link Policy policies} of literal-prefix {@link PolicyRule}s) and per-principal role bindings; the
 * snapshot carries NO direct per-principal prefix grants (those remain the imperative break-glass layer,
 * e.g. the static root grant). Each {@code Role} is keyed by {@link Role#name()}; each binding maps a
 * principal to the set of role names it holds via config.
 *
 * @param roles    role definitions, keyed by role name (non-null; deeply copied)
 * @param bindings principal to set of config-bound role names (non-null; deeply copied)
 */
public record ConfigPolicy(Map<String, Role> roles, Map<String, Set<String>> bindings) {

    public static final ConfigPolicy EMPTY = new ConfigPolicy(Map.of(), Map.of());

    public ConfigPolicy {
        Objects.requireNonNull(roles, "roles must not be null");
        Objects.requireNonNull(bindings, "bindings must not be null");
        // Defensive, deep, immutable copies: Role values are already immutable records, but each binding's
        // role-name set must itself be copied so the published snapshot cannot be mutated after the swap.
        roles = Map.copyOf(roles);
        Map<String, Set<String>> b = new HashMap<>(bindings.size());
        for (Map.Entry<String, Set<String>> e : bindings.entrySet()) {
            b.put(Objects.requireNonNull(e.getKey(), "binding principal must not be null"),
                    Set.copyOf(Objects.requireNonNull(e.getValue(), "binding role set must not be null")));
        }
        bindings = Map.copyOf(b);
    }
}
