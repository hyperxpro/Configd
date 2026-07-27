package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * Subscription-level reject: malformed spec/cursor (terminal — fix it), or resource cap exceeded
 * (recover by cancelling a watch to free a slot, or reconnecting).
 */
public final class BadSubscribeException extends ConfigdException {

    public BadSubscribeException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }
}
