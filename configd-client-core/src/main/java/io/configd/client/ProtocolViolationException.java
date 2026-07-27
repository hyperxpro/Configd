package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * Framing/protocol-state failure: bug in client's own frame sequence/version, or corrupted stream.
 * Reaction: do not retry unchanged. FRAME_CORRUPT tolerates single reconnect (transient); persistent is codec bug.
 */
public final class ProtocolViolationException extends ConfigdException {

    public ProtocolViolationException(String message) {
        super(message);
    }

    public ProtocolViolationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProtocolViolationException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }
}
