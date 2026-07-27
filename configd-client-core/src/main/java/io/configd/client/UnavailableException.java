package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * Retryable capacity/availability condition. Reaction: retry with backoff; follow X-Leader-Hint once if present.
 * Pre-handshake refusal is routine capacity condition, not protocol error. Distinct from IndeterminateException:
 * UnavailableException is definite non-commit (safe to retry).
 */
public final class UnavailableException extends ConfigdException {

    public UnavailableException(String message) {
        super(message);
    }

    public UnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public UnavailableException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }
}
