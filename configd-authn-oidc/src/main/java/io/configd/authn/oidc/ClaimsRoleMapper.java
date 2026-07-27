package io.configd.authn.oidc;

import com.nimbusds.jwt.JWTClaimsSet;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Maps a validated access token's claims to the set of Configd roles the in-core authorization engine keys
 * on. This is the boundary between an external identity provider's vocabulary (Keycloak's
 * {@code realm_access.roles}, EntraID's {@code roles}, Okta's {@code groups}, an OAuth {@code scope}) and
 * Configd's own role names: the authorization engine never sees an IdP claim, only the mapped roles (auth-SPI
 * boundary; RFC 8725 keeps the two vocabularies distinct).
 *
 * <p>The mapping is deliberately configurable per issuer because every IdP places authorization data in a
 * different claim with a different nesting and value shape:
 * <ul>
 *   <li><b>Claim path</b> - a dotted selector. It is resolved <em>whole-key-first</em> (so a namespaced
 *       claim whose name itself contains dots, e.g. {@code https://configd.example/roles}, is one segment),
 *       then, if no such top-level key exists, walked as a nested path ({@code realm_access.roles} descends
 *       into the {@code realm_access} object then reads {@code roles}). An absent claim yields <b>no
 *       roles</b> - default deny, never a default grant.</li>
 *   <li><b>Value shape</b> - auto-detected: a JSON array becomes its string elements; a single string is
 *       split on whitespace (covering the space-delimited {@code scope}/{@code scp} claims). This is
 *       strictly more robust than a configured value-type and cannot be mis-set.</li>
 *   <li><b>Value mapping</b> - an optional external-value to Configd-role map. When the map is non-empty it
 *       is an <b>allowlist</b>: only mapped external values produce a role, unmapped ones are dropped. When
 *       the map is empty the external values pass through as role names (with an optional prefix), for
 *       deployments that name their IdP roles to match Configd's.</li>
 * </ul>
 *
 * <p><b>Security note - prefer the roleMap allowlist for untrusted claims.</b> Pass-through (empty roleMap)
 * turns whatever the claim contains into role names verbatim. That is safe only when the mapped claim is
 * ISSUER-controlled (Keycloak {@code realm_access.roles}, group memberships) rather than CLIENT-influenceable.
 * The OAuth {@code scope}/{@code scp} claim is client-requested, so pass-through of {@code scope} lets a
 * caller name its own roles - configure a {@code roleMap.<scope>=<role>} allowlist for any issuer whose
 * mapped claim a client can influence (boot emits a warning for the {@code scope}/{@code scp} pass-through case).
 *
 * <p>Stateless and immutable; safe to share across threads.
 */
final class ClaimsRoleMapper {

    private final String claimPath;
    private final Map<String, String> roleMap;
    private final String rolePrefix;

    ClaimsRoleMapper(String claimPath, Map<String, String> roleMap, String rolePrefix) {
        this.claimPath = claimPath;
        this.roleMap = Map.copyOf(Objects.requireNonNull(roleMap, "roleMap"));
        this.rolePrefix = Objects.requireNonNull(rolePrefix, "rolePrefix");
    }

    /**
     * The Configd roles for {@code claims}, or an empty set (default deny) when no claim path is configured
     * or the claim is absent/empty. Never {@code null}.
     */
    Set<String> rolesOf(JWTClaimsSet claims) {
        if (claimPath == null) {
            return Set.of();
        }
        Object leaf = resolve(claims.toJSONObject(), claimPath);
        List<String> externals = coerce(leaf);
        Set<String> roles = new LinkedHashSet<>();
        for (String external : externals) {
            if (roleMap.isEmpty()) {
                roles.add(rolePrefix + external);
            } else {
                String mapped = roleMap.get(external);
                if (mapped != null) {
                    roles.add(mapped);
                }
            }
        }
        return Set.copyOf(roles);
    }

    static Object resolve(Map<String, Object> root, String path) {
        if (root.containsKey(path)) {
            return root.get(path);
        }
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    static List<String> coerce(Object leaf) {
        if (leaf instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object element : list) {
                if (element instanceof String s && !s.isBlank()) {
                    out.add(s);
                }
            }
            return out;
        }
        if (leaf instanceof String s) {
            List<String> out = new ArrayList<>();
            for (String token : s.trim().split("\\s+")) {
                if (!token.isEmpty()) {
                    out.add(token);
                }
            }
            return out;
        }
        return List.of();
    }
}
