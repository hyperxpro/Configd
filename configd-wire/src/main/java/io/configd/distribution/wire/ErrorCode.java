package io.configd.distribution.wire;

/**
 * Fixed ERROR/CLOSE code taxonomy (closed set, numeric codes pinned by
 * EdgeFrameCodecGoldenFixtureTest). No free-form error strings on wire. Changing any value
 * is wire-format change: MUST bump EdgeFrameCodec.EDGE_WIRE_VERSION.
 */
public enum ErrorCode {

    /** Version byte != {@link EdgeFrameCodec#EDGE_WIRE_VERSION}. */
    BAD_WIRE_VERSION(1),

    /** Declared frame length exceeds the frame cap ({@link EdgeFrameCodec#MAX_EDGE_FRAME_SIZE}). */
    FRAME_TOO_LARGE(2),

    /** CRC32C mismatch or otherwise malformed payload. */
    FRAME_CORRUPT(3),

    /** mTLS identity rejected / not authorized. */
    AUTH_FAIL(4),

    /** Malformed subscription spec or cursor. */
    BAD_SUBSCRIBE(5),

    /** Replay source unavailable for a needed range. */
    GAP_UNRECOVERABLE(6),

    /**
     * Session overflow / ack-lag demotion notice (non-fatal): the session is switched from
     * streaming to catch-up (snapshot) mode. The accompanying structured demotion event
     * carries cursor evidence.
     */
    DEMOTED_TO_CATCHUP(7),

    /**
     * Subscriber quarantined - or UNHEALTHY, the escalated tier, which shares this wire code
     * (the taxonomy is closed and golden-pinned; the escalation is distinguished by the
     * diagnostic message and the governor state, not a new code). The subscriber must
     * re-bootstrap after its cooldown.
     */
    QUARANTINED(8),

    /** Orderly server-initiated close. */
    SERVER_SHUTDOWN(9),

    /** Unexpected frame for the current session state. */
    PROTOCOL_VIOLATION(10),

    /**
     * Authenticated but not authorized - the 403-class streaming authorization reject for a
     * watch subscription (W7-5a). Distinct from {@link #AUTH_FAIL} (the 401-class
     * authentication failure): the identity is acceptable but lacks {@code READ} and
     * {@code WATCH} over the whole watch target (over-broad target, non-root
     * {@code full_chain_verify} / {@code FULL}, an intersecting {@code DENY}, or a missing
     * capability). Surfaces as a {@link EdgeFrame.WatchCanceled} per-watch terminal with
     * <b>no data frame emitted first</b> (W7-5).
     */
    NOT_AUTHORIZED(11),

    /**
     * The resume token's bound {@code topologyEpoch} does not match the server's current
     * {@code ShardMap.epoch()} - the whole topology generation the cursor/SUBSCRIBE belongs to is
     * superseded. Distinct from {@link #GAP_UNRECOVERABLE} ("data fell
     * off retention" - resume from an earlier position): {@code STALE_TOPOLOGY} means "drop the cursor
     * and fully re-hydrate from scratch" (the etcd {@code ErrCompacted} model). Delivered as a
     * {@link EdgeFrame.WatchCanceled} per-watch terminal for a watch, or an {@link EdgeFrame.ErrorClose}
     * for a legacy SUBSCRIBE. At static-N (one deploy-time epoch) it never fires - byte-identical
     * behavior.
     */
    STALE_TOPOLOGY(12),

    /**
     * The connection's authenticated credential has expired (the token's lifetime elapsed and no
     * {@link EdgeFrame.RefreshAuth} extended it, or a {@code REFRESH_AUTH} presented an unacceptable
     * fresh credential). Distinct from {@link #AUTH_FAIL} (the 401-class first-credential failure) so a
     * client can tell "your session aged out, re-authenticate" from "your credential was never valid".
     * Delivered as an {@link EdgeFrame.ErrorClose}. (Only the token path enforces expiry today, via a
     * default TTL; certificate {@code notAfter} enforcement is not yet wired.)
     */
    CREDENTIAL_EXPIRED(13);

    private final int code;

    ErrorCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** Resolve on-wire code back to ErrorCode; throws if not a defined value. */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode ec : VALUES) {
            if (ec.code == code) {
                return ec;
            }
        }
        throw new IllegalArgumentException("Unknown edge error code: " + code);
    }

    /** Cached values array: avoids defensive copy in values() per call. */
    private static final ErrorCode[] VALUES = values();
}
