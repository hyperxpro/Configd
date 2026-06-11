package io.configd.distribution.fanout;

import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;

/**
 * The single transport boundary out of a {@link FanOutSessionCore} (ADR-0037
 * TransportSink-seam contingency). The session emits {@link EdgeFrame}s through this sink
 * and never touches a socket, TLS, or {@code java.net} type directly — the live server
 * (configd-server, part (b)) and the simulator each provide an implementation.
 *
 * <p>Implementations are responsible for the actual bounded outbound queue at the
 * transport layer (e.g. a per-connection write buffer). The session does its OWN bounded
 * accounting on top of the {@code offer} return value (unacked-NOTIFY-frames ≤
 * {@code queueFrames}), so {@code offer} returning {@code false} is an additional, lower
 * backpressure signal — a would-block at the transport — that the session also treats as
 * a demotion trigger (never an unbounded buffer, never a silent drop; CT-26).
 */
public interface TransportSink {

    /**
     * Offers a frame to the transport for delivery to the edge.
     *
     * @param frame the frame to send (non-null)
     * @return {@code true} if accepted; {@code false} if the transport would block / its
     *         queue is full (a backpressure signal the session reacts to)
     */
    boolean offer(EdgeFrame frame);

    /**
     * Closes the session's transport with a structured reason. After {@code close} the
     * sink accepts no further frames. Implementations should attempt to deliver a final
     * {@link EdgeFrame.ErrorClose} carrying {@code code}/{@code message} where the
     * transport state permits.
     *
     * @param code    the taxonomy close reason
     * @param message a human-readable diagnostic (never null)
     */
    void close(ErrorCode code, String message);
}
