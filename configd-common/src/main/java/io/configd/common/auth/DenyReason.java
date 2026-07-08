package io.configd.common.auth;

/**
 * Why an {@link Authenticator} denied a {@link Credential}. The reason is load-bearing for chain
 * resolution ({@link AuthenticatorChain}): only {@link #INVALID_CREDENTIAL} is a hard stop; the other two
 * let the chain continue to the next authenticator.
 */
public enum DenyReason {

    /** Nothing usable was presented for this authenticator to verify. Chain continues. Maps to 401. */
    NO_CREDENTIAL,

    /**
     * The credential is OWNED by this authenticator and is bad: expired, forged, untrusted, or the
     * password did not match. HARD STOP - the chain must NOT fall through to a weaker authenticator. 401.
     */
    INVALID_CREDENTIAL,

    /**
     * The authenticator recognised the credential's type but it is not this authenticator's to verify
     * (e.g. a JWT for a different issuer). Chain continues to the next authenticator.
     */
    NOT_THIS_AUTHENTICATOR
}
