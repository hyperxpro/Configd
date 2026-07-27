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

    record Fatal(ConfigdException exception) implements Reaction {
    }

    record PerWatch(ConfigdException exception) implements Reaction {
    }

    record CatchUp() implements Reaction {
    }

    record CancelAck() implements Reaction {
    }
}
