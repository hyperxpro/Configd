package io.configd.authn;

/**
 * Why an {@link Authenticator} did not authenticate — the typed reason that drives chain resolution
 * (authenticator-spi.md §5.1). The distinction between {@link #INVALID_CREDENTIAL} (a HARD STOP) and
 * {@link #NOT_THIS_AUTHENTICATOR} (continue) is load-bearing: it is what stops a forged credential from
 * falling through to a weaker authenticator (RA-2), modelled on JAAS {@code Requisite} vs {@code Sufficient}
 * (prior-art.md §2.3).
 *
 * <p>Design artifact (auth-SPI). NOT production code.
 */
public enum RejectReason {
    /** Nothing presented for this authenticator to act on. → 401. (Continue down the chain.) */
    NO_CREDENTIAL,
    /** OWNED by this authenticator and bad: expired / forged / untrusted-issuer. → 401, HARD STOP (no fall-through). */
    INVALID_CREDENTIAL,
    /** Recognised the credential type, but it isn't mine (e.g. a JWT for another issuer). Try the next authenticator. */
    NOT_THIS_AUTHENTICATOR
}
