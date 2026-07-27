package io.configd.client;

/**
 * The frame scope for an {@link io.configd.distribution.wire.ErrorCode}: {@code ERROR_CLOSE}
 * (connection-fatal) or {@code WATCH_CANCELED} (per-watch). Scope matters because several error codes are
 * overloaded: ERROR_CLOSE is always terminal; WATCH_CANCELED is per-watch (siblings survive).
 */
public enum Carrier {

    ERROR_CLOSE,
    WATCH_CANCELED
}
