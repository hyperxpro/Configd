package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * The <b>401-class</b> authentication failure at the connection boundary: an mTLS handshake rejection
 * (TLS-layer — not a framed error, surfaced as a handshake failure/reset), a token/basic {@code AUTH}-frame
 * credential rejected, an edge client certificate rejected or revoked, a credential over the size caps, or a
 * {@code REFRESH_AUTH} that resolves to a different identity — every case reported on the wire (when framed)
 * as {@link ErrorCode#AUTH_FAIL} (4). The HTTP {@code 401} class maps here too.
 *
 * <p><b>Reaction:</b> <b>(re)authenticate</b> — fix the certificate or present a valid token/basic
 * credential. A rejected pre-auth {@code AUTH} closes the connection, so a retry costs a <b>fresh
 * connection</b> — a driver <b>MUST NOT hot-loop</b> {@code AUTH} frames on one connection.
 * Distinct from {@link CredentialExpiredException}: this is "the credential was never valid", not "the
 * session aged out".
 */
public final class AuthFailedException extends ConfigdException {

    public AuthFailedException(String message) {
        super(message);
    }

    public AuthFailedException(String message, Throwable cause) {
        super(message, cause);
    }

    public AuthFailedException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }
}
