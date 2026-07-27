package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

import java.util.Optional;

/**
 * Root of the reference-client exception hierarchy. Each subtype IS the normative reaction to a class of
 * outcomes: callers branch on type, never on message. Unchecked because protocol failures are handled by
 * policy (retry/re-auth/reconnect) keyed on type, not handled inline. Edge error codes carried on
 * {@link #edgeCode()} for observability; server diagnostic is sanitized on {@link #serverMessage()}.
 */
public abstract class ConfigdException extends RuntimeException {

    private final transient ErrorCode edgeCode;
    private final transient String serverMessage;

    protected ConfigdException(String message) {
        this(message, null, null, null);
    }

    protected ConfigdException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    /**
     * Edge-originated failure: edgeCode and serverMessage must already be sanitized.
     */
    protected ConfigdException(String message, Throwable cause, ErrorCode edgeCode, String serverMessage) {
        super(message, cause);
        this.edgeCode = edgeCode;
        this.serverMessage = serverMessage;
    }

    public final Optional<ErrorCode> edgeCode() {
        return Optional.ofNullable(edgeCode);
    }

    /**
     * The server's diagnostic: already sanitized for safe logging. Diagnostic only — do not branch on it.
     */
    public final Optional<String> serverMessage() {
        return Optional.ofNullable(serverMessage);
    }
}
