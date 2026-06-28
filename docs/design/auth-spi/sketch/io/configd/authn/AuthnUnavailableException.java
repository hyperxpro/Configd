package io.configd.authn;

/**
 * Thrown by {@link Authenticator#authenticate} when a <b>configured</b> authenticator cannot do its job —
 * an OIDC issuer/JWKS unreachable (cold cache), an LDAP server down, a Kubernetes TokenReview API unreachable.
 *
 * <p>Design artifact (auth-SPI). NOT production code.
 *
 * <p><b>Checked on purpose</b> (mirroring {@code KmsUnavailableException}): the fail-closed decision (RA-1)
 * must be a <em>conscious</em> one at the resolution seam, so the type system forces the resolver to handle it
 * — by rejecting (a {@code 503}/{@code 401}-class outcome), <b>never</b> by falling through to a weaker
 * authenticator. A silent downgrade is how a "the request was authenticated" claim becomes fiction.
 */
public class AuthnUnavailableException extends Exception {

    public AuthnUnavailableException(String message) {
        super(message);
    }

    public AuthnUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
