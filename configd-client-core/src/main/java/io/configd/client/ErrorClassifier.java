package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * Maps a binary-edge terminal signal — an {@link ErrorCode} together with its {@link Carrier} frame — to the
 * normative {@link Reaction}. This is the single place the client encodes the "each type IS its reaction"
 * rule: the code names the <b>reason</b>, the carrier names the <b>scope</b>, and several codes
 * (4, 6, 7, 9, 11, 12) are scope-overloaded so a pure code-byte switch is insufficient.
 *
 * <p>The untrusted server diagnostic is sanitized before it is attached to the raised exception; a caller
 * branches on the exception <b>type</b>, never on the text.
 */
public final class ErrorClassifier {

    private ErrorClassifier() {
    }

    /**
     * Classifies a terminal edge signal.
     *
     * @param code             the wire {@link ErrorCode}
     * @param carrier          the frame that carried it ({@code ERROR_CLOSE} or {@code WATCH_CANCELED})
     * @param rawServerMessage the untrusted server diagnostic (sanitized here; never machine-parsed)
     * @return the reaction
     */
    public static Reaction classify(ErrorCode code, Carrier carrier, String rawServerMessage) {
        String msg = Sanitize.message(rawServerMessage);

        // DEMOTED_TO_CATCHUP is the sole non-fatal code: it rides an ERROR_CLOSE frame but does NOT close
        // (§07 E3-2). It is a mode switch, not an exception, regardless of carrier.
        if (code == ErrorCode.DEMOTED_TO_CATCHUP) {
            return new Reaction.CatchUp();
        }

        // SERVER_SHUTDOWN on a WATCH_CANCELED is the expected acknowledgement of the driver's own
        // WATCH_CANCEL — do NOT reconnect. On an ERROR_CLOSE it is a genuine server-side close: reconnect.
        if (code == ErrorCode.SERVER_SHUTDOWN) {
            return carrier == Carrier.WATCH_CANCELED
                    ? new Reaction.CancelAck()
                    : new Reaction.Fatal(new UnavailableException(
                            "server closed the connection (SERVER_SHUTDOWN)", code, msg));
        }

        ConfigdException ex = exceptionFor(code, msg);
        // ERROR_CLOSE is connection-scope; WATCH_CANCELED is per-watch (siblings survive).
        return carrier == Carrier.WATCH_CANCELED ? new Reaction.PerWatch(ex) : new Reaction.Fatal(ex);
    }

    /** The exception type that IS the reaction for {@code code}; the sanitized diagnostic rides along. */
    private static ConfigdException exceptionFor(ErrorCode code, String sanitizedMessage) {
        return switch (code) {
            case BAD_WIRE_VERSION, FRAME_TOO_LARGE, FRAME_CORRUPT, PROTOCOL_VIOLATION ->
                    new ProtocolViolationException(reason(code, sanitizedMessage), code, sanitizedMessage);
            case AUTH_FAIL ->
                    new AuthFailedException(reason(code, sanitizedMessage), code, sanitizedMessage);
            case CREDENTIAL_EXPIRED ->
                    new CredentialExpiredException(reason(code, sanitizedMessage), code, sanitizedMessage);
            case NOT_AUTHORIZED ->
                    new ForbiddenException(reason(code, sanitizedMessage), code, sanitizedMessage);
            case QUARANTINED ->
                    new QuarantinedException(reason(code, sanitizedMessage), code, sanitizedMessage);
            case BAD_SUBSCRIBE ->
                    new BadSubscribeException(reason(code, sanitizedMessage), code, sanitizedMessage);
            case GAP_UNRECOVERABLE ->
                    new GapUnrecoverableException(reason(code, sanitizedMessage), code, sanitizedMessage);
            case STALE_TOPOLOGY ->
                    new StaleTopologyException(reason(code, sanitizedMessage), code, sanitizedMessage);
            // DEMOTED_TO_CATCHUP and SERVER_SHUTDOWN are handled by the caller (non-exception reactions).
            case DEMOTED_TO_CATCHUP, SERVER_SHUTDOWN ->
                    throw new IllegalStateException("non-exception code reached exceptionFor: " + code);
        };
    }

    private static String reason(ErrorCode code, String sanitizedMessage) {
        return sanitizedMessage.isEmpty()
                ? "edge terminal: " + code
                : "edge terminal: " + code + " (" + sanitizedMessage + ")";
    }
}
