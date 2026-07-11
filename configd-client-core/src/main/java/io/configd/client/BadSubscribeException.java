package io.configd.client;

import io.configd.distribution.wire.ErrorCode;

/**
 * A subscription-level reject: {@link ErrorCode#BAD_SUBSCRIBE} (5) — either (a) a malformed subscription
 * spec / cursor, or (b) a per-connection resource cap (live-watch cap, {@code watch_id} budget, target
 * length).
 *
 * <p><b>Reaction:</b> case (a) is <b>terminal</b> — fix the {@code SUBSCRIBE}/cursor. Case (b) recovers
 * by a resource action: the live-watch cap (1024) by cancelling a watch to free a slot, the {@code watch_id}
 * budget (16384) by reconnecting (a fresh connection resets it).
 */
public final class BadSubscribeException extends ConfigdException {

    public BadSubscribeException(String message, ErrorCode edgeCode, String sanitizedServerMessage) {
        super(message, null, edgeCode, sanitizedServerMessage);
    }
}
