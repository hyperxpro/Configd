package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * Credential aged out: the session expired (distinct from {@link AuthFailedException} which means "never valid").
 * Reaction: reconnect + re-authenticate. A driver SHOULD refresh proactively before expiry to avoid this.
 */
public final class CredentialExpiredException extends ConfigdException {

    public CredentialExpiredException(String message) {
        super(message);
    }

    public CredentialExpiredException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }
}
