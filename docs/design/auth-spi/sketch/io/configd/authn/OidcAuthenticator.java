package io.configd.authn;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The one non-trivial provider, end-to-end (authenticator-spi.md §8.1) — proof the SPI is implementable for a
 * <b>remote-validating</b> provider that must fail closed. In production this lives in the optional
 * {@code configd-authn-oidc} module and depends on a vetted JWT library (e.g. Nimbus JOSE+JWT); the core pulls
 * in no JWT SDK.
 *
 * <p>Design artifact (auth-SPI). NOT production code. The crypto/JWKS work is behind the local
 * {@link TokenVerifier} seam so this file <b>compiles standalone</b> (no JWT dependency) and the smoke test can
 * drive the fail-closed control flow; the Nimbus-backed {@code TokenVerifier} is described in the design doc,
 * exactly as the KMS AWS sketch describes the AWS SDK calls without compiling against them.
 */
public final class OidcAuthenticator implements Authenticator {

    /**
     * The JWT verification seam. The production impl wraps a vetted JWT library: caches the issuer's JWKS keys
     * (bounded TTL), verifies the signature, and checks {@code iss}/{@code aud}/{@code exp}/{@code nbf}.
     */
    public interface TokenVerifier {
        /** Unverified parse of the {@code iss} claim ONLY, for dispatch (cheap, no signature check). */
        String peekIssuer(String jwt);

        /**
         * Full verification.
         *
         * @throws AuthnUnavailableException if the JWKS can't be fetched (cold cache + issuer unreachable) — RA-1
         * @throws InvalidJwtException       if the signature/claims are bad — RA-2
         */
        Claims verify(String jwt, String audience) throws AuthnUnavailableException, InvalidJwtException;
    }

    /** Verified claims (subset the authenticator maps). */
    public record Claims(String issuer, String subject, List<String> groups) {}

    /** Owned-but-invalid JWT (bad signature/aud/exp). Becomes {@code Rejected(INVALID_CREDENTIAL)}. */
    public static final class InvalidJwtException extends Exception {
        public InvalidJwtException(String message) {
            super(message);
        }
    }

    private final String issuer;                 // the iss this authenticator owns
    private final String audience;               // expected aud
    private final TokenVerifier verifier;
    private final Map<String, String> groupToRole; // external group → Configd role (the role mapping, boundary §2)

    public OidcAuthenticator(String issuer, String audience, TokenVerifier verifier,
                             Map<String, String> groupToRole) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.audience = Objects.requireNonNull(audience, "audience");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.groupToRole = Map.copyOf(groupToRole);
    }

    @Override
    public String type() {
        return "oidc";
    }

    @Override
    public boolean canAttempt(Credential credential) {
        return credential instanceof Credential.BearerToken;
    }

    @Override
    public AuthResult authenticate(Credential credential) throws AuthnUnavailableException {
        String jwt = ((Credential.BearerToken) credential).token();
        if (!issuer.equals(verifier.peekIssuer(jwt))) {
            // Recognised the type (a bearer token) but not my issuer → let another authenticator try (RA-2).
            return new AuthResult.Rejected(RejectReason.NOT_THIS_AUTHENTICATOR, "issuer not mine");
        }
        Claims claims;
        try {
            claims = verifier.verify(jwt, audience);                 // Nimbus: JWKS sig + iss/aud/exp/nbf
        } catch (InvalidJwtException e) {
            return new AuthResult.Rejected(RejectReason.INVALID_CREDENTIAL, "jwt rejected");  // RA-2, no leak
        }
        // throws AuthnUnavailableException straight out → resolver fails closed (RA-1), never falls through.

        // map external groups → CONFIGD roles HERE; the authz engine only ever sees Configd roles (boundary §2)
        Set<String> roles = new LinkedHashSet<>();
        for (String g : claims.groups()) {
            String role = groupToRole.get(g);
            if (role != null) {
                roles.add(role);
            }
        }
        String id = claims.issuer() + "#" + claims.subject();        // iss#sub — stable, collision-free
        return new AuthResult.Authenticated(
                new Principal(id, roles, Map.of("iss", claims.issuer()), "oidc"));
    }
}
