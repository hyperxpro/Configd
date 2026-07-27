package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * 403 authorization failure: authenticated but principal lacks capability over target (permanent).
 * Reaction: do not retry unchanged; request narrower target. On edge, per-watch (siblings survive).
 */
public final class ForbiddenException extends ConfigdException {

    public ForbiddenException(String message) {
        super(message);
    }

    public ForbiddenException(String message, String sanitizedServerMessage) {
        super(message, null, null, sanitizedServerMessage);
    }

    public ForbiddenException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }
}
