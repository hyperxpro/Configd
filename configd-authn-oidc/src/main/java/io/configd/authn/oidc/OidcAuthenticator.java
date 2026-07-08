package io.configd.authn.oidc;

import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;

import io.configd.common.auth.AuthResult;
import io.configd.common.auth.Authenticator;
import io.configd.common.auth.Credential;
import io.configd.common.auth.DenyReason;

import java.text.ParseException;
import java.util.Map;
import java.util.Objects;

/**
 * The OAuth2/OIDC resource-server {@link Authenticator}: it VALIDATES a presented JWT access token and never
 * runs a token, redirect, or PKCE flow (that is the IdP's and the client's job - Configd is a pure resource
 * server). It handles {@link Credential.BearerToken} only and is registered in the chain as {@code oidc},
 * ahead of the static {@code bearer} catch-all so a foreign or opaque token falls through to bearer rather
 * than being hard-rejected here.
 *
 * <p>It supports one or more pinned issuers. The unverified {@code iss} claim is peeked ONLY to dispatch to
 * the right issuer's {@link OidcIssuerValidator} (never to make a trust decision); a token whose issuer is
 * not configured yields {@link DenyReason#NOT_THIS_AUTHENTICATOR} so the chain continues. All actual trust -
 * signature, {@code iss} exact-match, {@code aud}, expiry - is decided inside the validator against the
 * operator-pinned issuer + JWKS.
 *
 * <p>Immutable and thread-safe.
 */
public final class OidcAuthenticator implements Authenticator {

    /** Validators keyed by their exact pinned issuer identifier (the token's {@code iss} must equal one). */
    private final Map<String, OidcIssuerValidator> validatorsByIssuer;

    OidcAuthenticator(Map<String, OidcIssuerValidator> validatorsByIssuer) {
        Objects.requireNonNull(validatorsByIssuer, "validatorsByIssuer");
        if (validatorsByIssuer.isEmpty()) {
            throw new IllegalArgumentException("an OIDC authenticator needs at least one configured issuer");
        }
        this.validatorsByIssuer = Map.copyOf(validatorsByIssuer);
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
    public AuthResult authenticate(Credential credential) {
        String token = ((Credential.BearerToken) credential).token();

        JWT jwt;
        try {
            jwt = JWTParser.parse(token);
        } catch (ParseException e) {
            // Not a JWT (an opaque bearer token): not ours - let the static bearer catch-all try it.
            return notThis("credential is not a JWT");
        }

        String issuer;
        try {
            issuer = jwt.getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            // Unreadable claims (e.g. an encrypted JWT we cannot decrypt): not a token we validate.
            return notThis("token claims are not readable");
        }
        if (issuer == null) {
            return notThis("token has no iss claim");
        }

        OidcIssuerValidator validator = validatorsByIssuer.get(issuer);
        if (validator == null) {
            // A JWT for an issuer we are not configured to trust: dispatch continues down the chain.
            return notThis("issuer not configured: " + issuer);
        }

        if (!(jwt instanceof SignedJWT signed)) {
            // The issuer IS ours, but the token is not a signed JWT - an `alg:none` (plaintext) token or a
            // JWE. This is a bad credential we own, not a dispatch miss: reject it hard (RFC 9068 forbids
            // `alg:none`; a resource server validates signed access tokens only).
            return new AuthResult.Denied(DenyReason.INVALID_CREDENTIAL,
                    "token for issuer " + issuer + " is not a signed JWT (alg:none or encrypted rejected)");
        }
        return validator.validate(signed);
    }

    private static AuthResult notThis(String detail) {
        return new AuthResult.Denied(DenyReason.NOT_THIS_AUTHENTICATOR, detail);
    }
}
