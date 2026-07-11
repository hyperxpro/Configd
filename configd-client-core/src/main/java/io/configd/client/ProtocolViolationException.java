package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * A framing / protocol-state failure: {@link ErrorCode#PROTOCOL_VIOLATION} (10),
 * {@link ErrorCode#BAD_WIRE_VERSION} (1), {@link ErrorCode#FRAME_TOO_LARGE} (2), or
 * {@link ErrorCode#FRAME_CORRUPT} (3) — and the HTTP {@code 400}/{@code 405} class.
 *
 * <p><b>Reaction:</b> a bug in the client's own frame sequence, version pin, or a corrupted stream —
 * <b>do not retry unchanged</b>. {@code FRAME_CORRUPT} (3) alone tolerates a single reconnect (transient
 * corruption); a persistent one is a codec bug. The others are producer bugs a conforming driver never
 * elicits.
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
