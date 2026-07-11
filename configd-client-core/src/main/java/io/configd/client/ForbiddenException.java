package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * The <b>403-class</b> authorization failure: authenticated, but the principal lacks the capability over the
 * target — {@link ErrorCode#NOT_AUTHORIZED} (11) on the edge (a {@code WATCH_CANCELED} per-watch reject), or
 * HTTP {@code 403}.
 *
 * <p><b>Reaction:</b> <b>permanently forbidden</b> for this principal and target — <b>do not retry the
 * same target unchanged</b>. Request a narrower target instead. On the edge it is per-watch (the connection
 * and sibling watches survive) and MAY also arrive mid-stream as a bounded revocation — same reaction: drop
 * that watch.
 */
public final class ForbiddenException extends ConfigdException {

    public ForbiddenException(String message) {
        super(message);
    }

    /** An HTTP {@code 403} carrying the server's sanitized (diagnostic-only) reason. */
    public ForbiddenException(String message, String sanitizedServerMessage) {
        super(message, null, null, sanitizedServerMessage);
    }

    public ForbiddenException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }
}
