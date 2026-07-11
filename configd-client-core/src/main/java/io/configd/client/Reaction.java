package io.configd.client;

/**
 * The classified reaction to a terminal edge frame — the output of {@link ErrorClassifier}. Encodes the
 * {@code (code, carrier)} scope so the connection state machine knows whether to tear the connection
 * down, end one watch, or keep streaming.
 *
 * <ul>
 *   <li>{@link Fatal} — the connection is dead; raise the carried exception.</li>
 *   <li>{@link PerWatch} — one watch ended; the connection and sibling watches survive.</li>
 *   <li>{@link CatchUp} — {@code DEMOTED_TO_CATCHUP} (7): non-fatal — the session stays open and switches to
 *       catch-up (snapshot) mode; drain and ack promptly.</li>
 *   <li>{@link CancelAck} — {@code SERVER_SHUTDOWN} (9) on a {@code WATCH_CANCELED}: the expected
 *       acknowledgement of the driver's own {@code WATCH_CANCEL}; not an error, do not reconnect.</li>
 * </ul>
 */
public sealed interface Reaction
        permits Reaction.Fatal, Reaction.PerWatch, Reaction.CatchUp, Reaction.CancelAck {

    /** Connection-fatal: raise {@link #exception()} and tear the connection down. */
    record Fatal(ConfigdException exception) implements Reaction {
    }

    /** Per-watch terminal: {@link #exception()} ends one watch; the connection survives. */
    record PerWatch(ConfigdException exception) implements Reaction {
    }

    /** Non-fatal catch-up demotion — the session continues in snapshot/catch-up mode. */
    record CatchUp() implements Reaction {
    }

    /** The expected acknowledgement of a client-initiated {@code WATCH_CANCEL}; not an error. */
    record CancelAck() implements Reaction {
    }
}
