package io.configd.client;

/**
 * The terminal frame that carries an {@link io.configd.distribution.wire.ErrorCode} — the <b>scope</b> half
 * of the {@code (code, carrier)} reaction key (§07 E3-3). A pure code-byte switch is insufficient because
 * several codes are scope-overloaded: an {@code ERROR_CLOSE} is connection-fatal (except the non-fatal
 * {@code DEMOTED_TO_CATCHUP}), whereas a {@code WATCH_CANCELED} is per-watch (the connection and sibling
 * watches survive).
 */
public enum Carrier {

    /** {@code ERROR_CLOSE} (0x09): connection-scope and terminal for every code except DEMOTED_TO_CATCHUP. */
    ERROR_CLOSE,

    /** {@code WATCH_CANCELED} (0x0F): per-watch scope; carries NOT_AUTHORIZED, GAP, STALE_TOPOLOGY, and the
     *  SERVER_SHUTDOWN cancel-ack. */
    WATCH_CANCELED
}
