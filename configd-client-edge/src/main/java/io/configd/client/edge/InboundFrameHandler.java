package io.configd.client.edge;

import io.configd.client.ConfigdException;
import io.configd.distribution.wire.EdgeFrame;

/**
 * The demultiplexer seam the reader thread dispatches decoded server frames to. The connection + auth phase
 * uses the heartbeat and terminal callbacks; the subscribe / hydrate and watch surfaces fill {@link #onFrame}
 * and the catch-up / per-watch callbacks. Every method has a no-op default so a consumer overrides only what
 * it needs.
 *
 * <p><b>Threading:</b> all callbacks are invoked on the connection's single reader thread, in frame-arrival
 * order. An implementation MUST NOT block it (that stalls draining and risks a slow-consumer demotion); hand
 * off to another executor if work is heavy.
 */
public interface InboundFrameHandler {

    /** A {@code HEARTBEAT} (0x08) — the liveness/staleness clock. */
    default void onHeartbeat(EdgeFrame.Heartbeat heartbeat) {
    }

    /** Any business / watch frame ({@code SUBSCRIBE_OK}, {@code NOTIFY}, {@code SNAPSHOT_*}, {@code WATCH_*}). */
    default void onFrame(EdgeFrame frame) {
    }

    /** {@code DEMOTED_TO_CATCHUP} (7): non-fatal — switch to catch-up mode, keep draining and acking. */
    default void onCatchUp() {
    }

    /** A per-watch terminal ({@code WATCH_CANCELED}, 0x0F): one watch ended; the connection survives. */
    default void onPerWatch(ConfigdException watchError) {
    }

    /**
     * A per-watch terminal carrying the {@code watch_id}, so a connection multiplexing several watches can
     * terminate <b>only</b> that watch and keep its siblings streaming. The default routes to the
     * {@code watch_id}-agnostic {@link #onPerWatch(ConfigdException)}, so a single-watch handler is unaffected.
     */
    default void onPerWatch(long watchId, ConfigdException watchError) {
        onPerWatch(watchError);
    }

    /** {@code SERVER_SHUTDOWN} (9) on a {@code WATCH_CANCELED}: the expected cancel-ack — not an error. */
    default void onCancelAck() {
    }

    /** The {@code watch_id}-carrying cancel-ack, for a multiplexed connection. Defaults to {@link #onCancelAck()}. */
    default void onCancelAck(long watchId) {
        onCancelAck();
    }

    /** A connection-fatal terminal, delivered just before the connection closes. */
    default void onTerminal(ConfigdException terminal) {
    }

    /**
     * Whether the handler currently wants more frames read from the socket. Returning {@code false} parks the
     * reader (reactive backpressure) until the handler regains demand and calls
     * {@link io.configd.client.edge.session.EdgeConnection#wakeReader()}. The default always wants frames (no
     * backpressure) — the auth path and any drain-promptly consumer.
     */
    default boolean wantsMoreFrames() {
        return true;
    }
}

