package io.configd.client.edge;

import io.configd.client.ConfigdException;
import io.configd.distribution.wire.EdgeFrame;

/**
 * The demultiplexer seam the reader thread dispatches decoded server frames to. Gate 1 (connection + auth)
 * uses the heartbeat and terminal callbacks; the subscribe / hydrate (Gate 2) and watch (Gate 3) gates fill
 * {@link #onFrame} and the catch-up / per-watch callbacks. Every method has a no-op default so a gate
 * overrides only what it consumes.
 *
 * <p><b>Threading:</b> all callbacks are invoked on the connection's single reader thread, in frame-arrival
 * order. An implementation MUST NOT block it (that stalls draining and risks a slow-consumer demotion, §06
 * F10-3); hand off to another executor if work is heavy.
 */
public interface InboundFrameHandler {

    /** A {@code HEARTBEAT} (0x08) — the liveness/staleness clock (§06 F6-8). */
    default void onHeartbeat(EdgeFrame.Heartbeat heartbeat) {
    }

    /** Any business / watch frame ({@code SUBSCRIBE_OK}, {@code NOTIFY}, {@code SNAPSHOT_*}, {@code WATCH_*}). */
    default void onFrame(EdgeFrame frame) {
    }

    /** {@code DEMOTED_TO_CATCHUP} (7): non-fatal — switch to catch-up mode, keep draining and acking. */
    default void onCatchUp() {
    }

    /** A per-watch terminal ({@code WATCH_CANCELED}, 0x0F): one watch ended; the connection survives (Gate 3). */
    default void onPerWatch(ConfigdException watchError) {
    }

    /** {@code SERVER_SHUTDOWN} (9) on a {@code WATCH_CANCELED}: the expected cancel-ack — not an error. */
    default void onCancelAck() {
    }

    /** A connection-fatal terminal, delivered just before the connection closes. */
    default void onTerminal(ConfigdException terminal) {
    }

    /**
     * Whether the handler currently wants more frames read from the socket. Returning {@code false} parks the
     * reader (reactive backpressure) until the handler regains demand and calls
     * {@link io.configd.client.edge.session.EdgeConnection#wakeReader()}. The default always wants frames (no
     * backpressure) — the Gate-1 auth path and any drain-promptly consumer.
     */
    default boolean wantsMoreFrames() {
        return true;
    }
}

