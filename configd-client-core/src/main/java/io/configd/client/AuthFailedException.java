package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * Authentication failure: the credential was never valid (distinct from {@link CredentialExpiredException}
 * which is "session aged out"). Reaction: re-authenticate. A rejected pre-auth {@code AUTH} closes the connection,
 * so retry costs a fresh connection — do not hot-loop on one connection.
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
