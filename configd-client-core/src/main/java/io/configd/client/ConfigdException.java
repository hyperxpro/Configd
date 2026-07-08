package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

import java.util.Optional;

/**
 * The root of the reference client's exception hierarchy. Each concrete subtype <b>is</b> the normative
 * §07 reaction to a class of outcomes: a caller branches on the exception <b>type</b>, never on a parsed
 * message ({@code 07-errors.md} E6). The base is unchecked because a driver-protocol failure is rarely
 * something a caller can handle inline at the call site — it is handled by a policy (retry / re-auth /
 * reconnect) keyed on the type.
 *
 * <p>When an exception originates from a binary edge {@link ErrorCode}, that numeric code is carried on
 * {@link #edgeCode()} for observability, and the server's diagnostic — which is untrusted, may carry
 * control/ANSI bytes, and MUST NOT be machine-parsed (§06 F6-9 / §07 E6) — is carried, <b>already
 * sanitized</b>, on {@link #serverMessage()}. Neither is a control signal; the type is.
 */
public abstract class ConfigdException extends RuntimeException {

    private final transient ErrorCode edgeCode;
    private final transient String serverMessage;

    /** A client-originated failure (no server error code / diagnostic). */
    protected ConfigdException(String message) {
        this(message, null, null, null);
    }

    /** A client-originated failure wrapping a transport/codec cause. */
    protected ConfigdException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    /**
     * A failure carrying an edge {@link ErrorCode} and the server's (already-sanitized) diagnostic.
     *
     * @param message       the client-facing message (safe; not the raw server text)
     * @param cause         the underlying cause, or {@code null}
     * @param edgeCode      the originating wire {@link ErrorCode}, or {@code null} if not edge-originated
     * @param serverMessage the SANITIZED server diagnostic, or {@code null} — never the raw wire bytes
     */
    protected ConfigdException(String message, Throwable cause, ErrorCode edgeCode, String serverMessage) {
        super(message, cause);
        this.edgeCode = edgeCode;
        this.serverMessage = serverMessage;
    }

    /** The originating binary-edge {@link ErrorCode}, when this failure came from an edge terminal frame. */
    public final Optional<ErrorCode> edgeCode() {
        return Optional.ofNullable(edgeCode);
    }

    /**
     * The server's diagnostic string, <b>already sanitized</b> for safe logging (control/ANSI/NUL bytes
     * escaped or stripped; §06 F6-9). Diagnostic only — a caller MUST NOT branch on it.
     */
    public final Optional<String> serverMessage() {
        return Optional.ofNullable(serverMessage);
    }
}
