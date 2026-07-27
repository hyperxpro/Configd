package io.configd.common.auth;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A verified caller identity - the single value that crosses the authentication -&gt; authorization
 * boundary. It is produced by an {@link Authenticator} and consumed by the in-core authorization engine
 * ({@code AclService.isAllowed(id, roles, ...)}); the authenticator's whole job is to turn a
 * {@link Credential} into one of these.
 *
 * <p>Typed, immutable, and it NEVER carries the credential. There is no field that can hold a secret, and
 * {@code toString} prints identity and attribute KEYS only (attribute VALUES may be sensitive claims). An
 * authenticator must not smuggle credential-derived material into {@link #attributes} or {@link #id}.
 *
 * @param id         the stable subject identifier (mTLS Subject DN, bearer principal, OIDC {@code iss#sub})
 * @param roles      the Configd roles the authorization engine keys on - already mapped from the external
 *                   identity by the authenticator (the authz engine never sees an OIDC claim or LDAP group)
 * @param attributes identity claims for audit / future ABAC (not consulted by today's role-based authz)
 * @param provenance the {@link Authenticator#type()} that minted this principal ({@code "mtls"}, {@code
 *                   "bearer"}, {@code "basic"}, {@code "oidc"}, ...) - recorded on audit lines
 */
public record Principal(String id, Set<String> roles, Map<String, String> attributes, String provenance) {

    public Principal {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("principal id must not be blank");
        }
        Objects.requireNonNull(provenance, "provenance");
        // Defensively copy to effectively-immutable snapshots; rejects nulls and aliasing.
        roles = Set.copyOf(roles);
        attributes = Map.copyOf(attributes);
    }

    public Principal(String id, Set<String> roles, String provenance) {
        this(id, roles, Map.of(), provenance);
    }

    @Override
    public String toString() {
        // Attribute values may be sensitive claims (tenant, email); print only the keys.
        return "Principal[id=" + id + ", roles=" + roles + ", attributeKeys=" + attributes.keySet()
                + ", provenance=" + provenance + "]";
    }
}
