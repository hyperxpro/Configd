package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * The connection's authenticated credential <b>aged out</b>: {@link ErrorCode#CREDENTIAL_EXPIRED} (13) on a
 * token/basic edge — a static token's server-side TTL elapsed with no {@code REFRESH_AUTH}, an OIDC/JWT
 * token's {@code exp} was reached, an edge client certificate's {@code notAfter} was reached under
 * enforcement, or a {@code REFRESH_AUTH} presented an unacceptable/over-cap fresh credential. Always a framed
 * {@code ERROR_CLOSE}, always connection-fatal.
 *
 * <p><b>§07 reaction:</b> the credential <b>was</b> valid and the <b>session</b> expired — this is a
 * <b>reconnect + re-authenticate</b> signal, distinct from {@link AuthFailedException} ("never valid") and
 * from a permanent {@link ForbiddenException}. A token client presents a fresh credential in a new
 * {@code AUTH} on a new connection; a certificate client reconnects with its rotated certificate (a cert
 * cannot refresh in-band). A driver <b>SHOULD</b> refresh proactively (a lead-time {@code REFRESH_AUTH})
 * before expiry to avoid it entirely (§03 AU5-6).
 */
public final class CredentialExpiredException extends ConfigdException {

    public CredentialExpiredException(String message) {
        super(message);
    }

    public CredentialExpiredException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }
}
