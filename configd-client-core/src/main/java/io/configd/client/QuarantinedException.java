package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * Subscriber quarantined: repeated ack-lag/slow-consumer pressure or escalated UNHEALTHY.
 * Reaction: back off with driver's own bounded backoff, then reconnect and re-bootstrap.
 * Cooldown is identity-stateful and persists across connections.
 */
public final class QuarantinedException extends ConfigdException {

    public QuarantinedException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }
}
