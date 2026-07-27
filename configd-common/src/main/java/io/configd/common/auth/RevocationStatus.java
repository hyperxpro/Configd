package io.configd.common.auth;

/**
 * The outcome of an online-revocation lookup for a certificate, as reported by a {@link RevocationChecker}.
 * Deliberately three-valued: the {@link #UNKNOWN} case (responder unreachable / timeout / indeterminate)
 * is what the {@link RevocationMode} lax-vs-strict decision turns on - {@code lax} fails open on
 * {@code UNKNOWN}, {@code strict} fails closed on it.
 */
public enum RevocationStatus {
    GOOD,
    /** The responder affirmatively reports the certificate IS revoked. Rejected under {@code lax} and {@code strict}. */
    REVOKED,
    UNKNOWN
}
