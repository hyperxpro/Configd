package io.configd.authn;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The authn → authz seam: a verified caller identity. The ONLY value that crosses from the pluggable
 * {@link Authenticator} to the in-core authorization engine (authn-authz-boundary.md, B-1).
 *
 * <p>Design artifact (auth-SPI). NOT production code.
 *
 * <p>Typed, immutable, and it <b>never carries the credential</b> (RA-3): there is no field that could hold a
 * token or a private key — the discipline is <em>structural</em>, not a convention. {@code toString()} shows
 * the id, roles, provenance, and attribute <em>keys</em> only (claim values may be sensitive).
 *
 * @param id            stable subject identifier (cert Subject DN, token subject, oidc {@code iss#sub})
 * @param roles         the <b>Configd</b> roles the authz engine keys on — already mapped from any external
 *                      identity by the authenticator (authn-authz-boundary.md §2); never a raw OIDC/LDAP group
 * @param attributes    identity claims for audit / future ABAC (oidc claims, SAN URI, tenant); not consumed by
 *                      v1 role-based authz
 * @param authenticator provenance: the {@link Authenticator#type()} that produced this principal
 */
public record Principal(String id, Set<String> roles, Map<String, String> attributes, String authenticator) {

    public Principal {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("principal id must not be blank");
        }
        Objects.requireNonNull(authenticator, "authenticator");
        // defensive, null-rejecting copies → effectively immutable
        roles = Set.copyOf(roles);
        attributes = Map.copyOf(attributes);
    }

    @Override
    public String toString() {
        // id + roles + provenance + attribute KEYS only (values redacted); no field can hold a credential.
        return "Principal[id=" + id + ", roles=" + roles + ", via=" + authenticator
                + ", attrKeys=" + attributes.keySet() + "]";
    }
}
