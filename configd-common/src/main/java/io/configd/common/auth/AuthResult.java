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

    /** The credential verified to a {@link Principal}. */
    record Authenticated(Principal principal) implements AuthResult {
        public Authenticated {
            Objects.requireNonNull(principal, "principal");
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
