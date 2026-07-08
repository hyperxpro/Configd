package io.configd.common.auth;

import java.util.Objects;

/**
 * The outcome of an {@link Authenticator#authenticate} attempt, or of an {@link AuthenticatorChain}
 * resolution. Sealed to a closed set of THREE outcomes so every consumer must handle all of them - the
 * third, {@link Unavailable}, is the fail-closed outcome that a two-case {@code Authenticated}/{@code
 * Denied} model cannot express: a configured authenticator whose backend is unreachable must yield a
 * retryable "auth temporarily unavailable" (HTTP 503 / M2M retryable), never a 401 and never a
 * fall-through to a weaker authenticator.
 */
public sealed interface AuthResult
        permits AuthResult.Authenticated, AuthResult.Denied, AuthResult.Unavailable {

    /**
     * {@link Authenticated#credentialExpiresAtMillis} sentinel: the authenticator has no authority-issued
     * absolute expiry for this credential (an mTLS peer cert, a Basic password, or the static bearer token,
     * none of which carry an {@code exp}). The consuming connection gate then falls back to its own
     * session-lifetime cap. A credential that DOES carry an authority expiry (an OIDC/JWT access token's
     * {@code exp}) reports that instant instead, and the gate closes the connection at {@code exp + leeway}.
     */
    long NO_EXPIRY = Long.MAX_VALUE;

    /**
     * The credential verified to a {@link Principal}. {@code credentialExpiresAtMillis} is the
     * authority-issued absolute expiry of the credential (epoch millis, e.g. a JWT {@code exp} claim), or
     * {@link #NO_EXPIRY} when the credential carries none. It exists so a long-lived authenticated
     * connection can be closed when the presented credential actually expires (the Gate-5 expiry model),
     * rather than only at a server-computed session cap; it is redaction-safe (a timestamp, never the
     * credential). The single-argument constructor preserves the pre-expiry behaviour ({@link #NO_EXPIRY}).
     */
    record Authenticated(Principal principal, long credentialExpiresAtMillis) implements AuthResult {
        public Authenticated {
            Objects.requireNonNull(principal, "principal");
            if (credentialExpiresAtMillis < 0L) {
                throw new IllegalArgumentException(
                        "credentialExpiresAtMillis must be non-negative or NO_EXPIRY: " + credentialExpiresAtMillis);
            }
        }

        /** A principal with no authority-issued credential expiry ({@link #NO_EXPIRY}). */
        public Authenticated(Principal principal) {
            this(principal, NO_EXPIRY);
        }
    }

    /**
     * The credential was NOT accepted. The {@link DenyReason} drives chain resolution: {@link
     * DenyReason#INVALID_CREDENTIAL} is a hard stop (the credential is owned by this authenticator and is
     * bad - never fall through), whereas {@link DenyReason#NOT_THIS_AUTHENTICATOR} and {@link
     * DenyReason#NO_CREDENTIAL} let the chain try the next authenticator. {@code detail} is a
     * redaction-safe message - it MUST NOT echo the credential.
     */
    record Denied(DenyReason reason, String detail) implements AuthResult {
        public Denied {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /**
     * A configured authenticator could not perform verification because its backend is unavailable (OIDC
     * JWKS unreachable and uncached, LDAP down, ...). Fail-closed: the chain STOPS and rejects (503-class,
     * retryable); it never downgrades to a weaker authenticator. {@code reason} is redaction-safe.
     */
    record Unavailable(String reason) implements AuthResult {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
