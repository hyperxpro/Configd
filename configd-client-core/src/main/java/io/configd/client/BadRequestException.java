package io.configd.client;

/**
 * Permanent request error: the request is malformed/invalid and will fail identically if retried unchanged.
 * Reaction: do not retry unchanged — fix the request. Distinct from retryable/indeterminate exceptions
 * and authorization failures.
 */
public final class BadRequestException extends ConfigdException {

    public BadRequestException(String message) {
        super(message);
    }

    public BadRequestException(String message, String sanitizedServerMessage) {
        super(message, null, null, sanitizedServerMessage);
    }
}
