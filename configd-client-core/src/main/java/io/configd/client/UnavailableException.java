package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * A <b>retryable</b> capacity / availability condition: HTTP {@code 503} (not-leader, lost,
 * strong-read-fail-closed, unhealthy — Gate 4), a pre-handshake connection refusal (the silent session-cap
 * close, §06 F10-2), {@link ErrorCode#SERVER_SHUTDOWN} (9) carried on an {@code ERROR_CLOSE}, or a transport
 * drop on a read.
 *
 * <p><b>§07 reaction:</b> <b>retry with backoff</b> — follow an {@code X-Leader-Hint} once when present
 * (Gate 4), otherwise back off and retry. A pre-handshake refusal is a routine capacity condition, <b>not</b>
 * a protocol error (§06 F10-2). This is deliberately distinct from {@link IndeterminateException}: an
 * {@code UnavailableException} is a definite non-commit (safe to retry); a {@code 504}/mutation-timeout is
 * indeterminate.
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
