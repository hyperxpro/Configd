package io.configd.distribution.wire;

/**
 * The fixed ERROR/CLOSE code taxonomy for the edge streaming protocol (C1 design
 * §3, review condition 3; ADR-0037). The set is closed and the numeric codes are
 * pinned by the {@code EdgeFrameCodecGoldenFixtureTest} golden fixture — C2/C3/C4
 * map their close reasons onto these codes; no free-form error strings ride the
 * wire as a structured cause.
 *
 * <p>The numeric {@link #code()} (1..11) is the byte that goes on the wire in an
 * {@link EdgeFrame.ErrorClose} payload (and a {@link EdgeFrame.WatchCanceled} per-watch
 * terminal); the human-readable {@code message} field of the frame is diagnostic only.
 * Changing any code value is a wire-format change and MUST bump
 * {@link EdgeFrameCodec#EDGE_WIRE_VERSION}.
 */
public enum ErrorCode {

    /** Version byte != {@link EdgeFrameCodec#EDGE_WIRE_VERSION}. */
    BAD_WIRE_VERSION(1),

    /** Declared frame length exceeds the frame cap ({@link EdgeFrameCodec#MAX_EDGE_FRAME_SIZE}). */
    FRAME_TOO_LARGE(2),

    /** CRC32C mismatch or otherwise malformed payload. */
    FRAME_CORRUPT(3),

    /** mTLS identity rejected / not authorized (server-side, C2 boundary). */
    AUTH_FAIL(4),

    /** Malformed subscription spec or cursor. */
    BAD_SUBSCRIBE(5),

    /** Replay source unavailable for a needed range. */
    GAP_UNRECOVERABLE(6),

    /**
     * Session overflow / ack-lag demotion notice (non-fatal): the session is
     * switched from streaming to catch-up (snapshot) mode. Carries the cursor
     * evidence in the accompanying structured demotion event (charter §4 C1 / CT-26).
     */
    DEMOTED_TO_CATCHUP(7),

    /**
     * C4 policy: subscriber quarantined — or UNHEALTHY, the escalated tier, which SHARES
     * this wire code (the taxonomy is closed and golden-pinned; the escalation is
     * distinguished by the diagnostic message and the governor state, not a new code).
     * The subscriber must re-bootstrap after its cooldown.
     */
    QUARANTINED(8),

    /** Orderly server-initiated close. */
    SERVER_SHUTDOWN(9),

    /** Unexpected frame for the current session state. */
    PROTOCOL_VIOLATION(10),

    /**
     * Authenticated but not authorized — the 403-class streaming authorization reject for
     * a watch subscription (RFC §2 W7-5a). Distinct from {@link #AUTH_FAIL} (the 401-class
     * authentication failure): the identity is acceptable but lacks {@code READ ∧ WATCH}
     * over the whole watch target (over-broad target, non-root {@code full_chain_verify} /
     * {@code FULL}, an intersecting {@code DENY}, or a missing capability). It surfaces as a
     * {@link EdgeFrame.WatchCanceled} per-watch terminal with <b>no data frame emitted
     * first</b> (W7-5). Code {@code 11} is the next free value after the built {@code 1..10}
     * taxonomy and rides the {@code 0x02} edge wire-version bump (W1-2 / W7-5a).
     */
    NOT_AUTHORIZED(11);

    private final int code;

    ErrorCode(int code) {
        this.code = code;
    }

    /** The on-wire numeric code (1..11). */
    public int code() {
        return code;
    }

    /**
     * Resolves an on-wire code back to its {@link ErrorCode}.
     *
     * @param code the numeric code read from the wire
     * @return the matching {@link ErrorCode}
     * @throws IllegalArgumentException if {@code code} is not a defined taxonomy value
     */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode ec : VALUES) {
            if (ec.code == code) {
                return ec;
            }
        }
        throw new IllegalArgumentException("Unknown edge error code: " + code);
    }

    /** Cached values array — avoids the defensive copy {@link #values()} allocates per call. */
    private static final ErrorCode[] VALUES = values();
}
