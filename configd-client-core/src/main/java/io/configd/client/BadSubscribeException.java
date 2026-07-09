package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * A subscription-level reject: {@link ErrorCode#BAD_SUBSCRIBE} (5) — either (a) a malformed subscription
 * spec / cursor, or (b) a per-connection resource cap (live-watch cap, {@code watch_id} budget, target
 * length; §06 F10-2).
 *
 * <p><b>§07 reaction:</b> case (a) is <b>terminal</b> — fix the {@code SUBSCRIBE}/cursor. Case (b) recovers
 * by a resource action: the live-watch cap (1024) by cancelling a watch to free a slot, the {@code watch_id}
 * budget (16384) by reconnecting (a fresh connection resets it, §06 F10-1a). Defined here in Gate 1 for a
 * complete classifier; the subscribe path that raises it is Gate 2.
 */
public final class BadSubscribeException extends ConfigdException {

    public BadSubscribeException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }
}
