package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * The subscriber was <b>quarantined</b>: {@link ErrorCode#QUARANTINED} (8) — the session ended after
 * repeated ack-lag / slow-consumer pressure (the escalation past {@link ErrorCode#DEMOTED_TO_CATCHUP}), or
 * the escalated UNHEALTHY tier, which shares this code.
 *
 * <p><b>§07 reaction:</b> back off with the driver's <b>own bounded backoff</b>, then reconnect and
 * re-bootstrap. The cooldown is identity-stateful (keyed to the certificate DN) and persists across
 * connections, so an early reconnect is <b>refused</b> with another {@code QUARANTINED} (§06 F10-4). The
 * cooldown duration lives only in the (untrusted) diagnostic message — a driver <b>MUST NOT</b> machine-parse
 * it (§07 E6); it uses its own backoff.
 */
public final class QuarantinedException extends ConfigdException {

    public QuarantinedException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }
}
