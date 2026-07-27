package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * Maps binary-edge terminal signal (ErrorCode + Carrier frame) to normative Reaction. Single place that
 * encodes "each type IS its reaction": code names reason, carrier names scope. Several codes are
 * scope-overloaded so pure code-byte switch insufficient. Server diagnostic sanitized before attachment.
 */
public final class ErrorClassifier {

    private ErrorClassifier() {
    }

    public static Reaction classify(ErrorCode code, Carrier carrier, String rawServerMessage) {
        String msg = Sanitize.message(rawServerMessage);

        // DEMOTED_TO_CATCHUP is the sole non-fatal code: rides ERROR_CLOSE but does NOT close. Mode switch, not exception.
        if (code == ErrorCode.DEMOTED_TO_CATCHUP) {
            return new Reaction.CatchUp();
        }

        // SERVER_SHUTDOWN on WATCH_CANCELED is cancel-ack from driver's own WATCH_CANCEL — do not reconnect.
        // On ERROR_CLOSE it is genuine server-side close — reconnect.
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
