package io.configd.testkit;

import io.configd.distribution.CommitNotification;
import io.configd.store.ConfigSnapshot;

import java.util.Objects;

/**
 * Sim-internal message model for the CP-to-edge fan-out channel.
 * <p>
 * This is the minimal envelope the simulated edge network carries between a
 * {@link StreamDriver} (server side) and an {@link EdgeActor} (edge side). It is
 * <b>deliberately not</b> the production wire protocol - defining the real frames
 * (length-prefix, codec, flow-control) is the stream driver's job, on top of the
 * transport-agnostic {@link io.configd.distribution.CommitNotificationSource}
 * boundary. Keeping this sealed and tiny prevents the sim from
 * accidentally fixing wire decisions that belong to the stream driver.
 *
 * <p>Two variants mirror the two things a commit-notification consumer loop can receive:
 * <ul>
 *   <li>{@link Notify} - a single committed-mutation {@link CommitNotification}
 *       (the {@code Ok} tail path: {@code seq}, {@code commitTimestampMillis},
 *       {@code delta}).</li>
 *   <li>{@link Snapshot} - a snapshot-equivalent state at {@code seq} (the
 *       {@code Gap -> ReplaySource.replayFromSnapshot()} recovery path).</li>
 * </ul>
 */
sealed interface EdgeStream permits EdgeStream.Notify, EdgeStream.NotifyBatch,
        EdgeStream.Snapshot, EdgeStream.Heartbeat, EdgeStream.ErrorClose {

    /**
     * A single committed-mutation notification pushed over the edge channel
     * (the {@code Ok} tail path). Retained for the
     * {@link DirectInjectionDriver} (test-the-tester) which injects individual
     * notifications; the stream driver uses {@link NotifyBatch}.
     *
     * @param notification the committed-mutation notification (non-null)
     */
    record Notify(CommitNotification notification) implements EdgeStream {
        public Notify {
            Objects.requireNonNull(notification, "notification must not be null");
        }
    }

    /**
     * A frame-level NOTIFY batch (batched frame format): N verbatim, consecutive
     * {@link CommitNotification}s carried in one wire frame, chain intact, never merged.
     * This is what {@link io.configd.distribution.fanout.FanOutSessionCore} emits
     * (a {@code EdgeFrame.Notify}); the sim sink maps it onto this message so the edge
     * applies the batch in seq order and acks the highest applied seq.
     *
     * @param notifications the consecutive notifications, ascending seq order (non-empty)
     */
    record NotifyBatch(java.util.List<CommitNotification> notifications) implements EdgeStream {
        public NotifyBatch {
            Objects.requireNonNull(notifications, "notifications must not be null");
            notifications = java.util.List.copyOf(notifications);
            if (notifications.isEmpty()) {
                throw new IllegalArgumentException("NotifyBatch must carry at least one notification");
            }
        }
    }

    /**
     * A snapshot-equivalent recovery payload pushed over the edge channel after a
     * demotion/GAP. The stream driver-side sink reassembles the
     * {@code SNAPSHOT_BEGIN / SNAPSHOT_CHUNK* / SNAPSHOT_END} frame flow into this single
     * message <b>on the server side</b> (chosen over per-chunk edge messages so the
     * {@link EdgeActor} stays simple - it applies one wholesale snapshot via its existing
     * {@code loadSnapshot} path, exactly as it did under the {@code DirectInjectionDriver}).
     *
     * @param snapshot the cumulative committed state at {@code seq} (non-null)
     * @param seq       the applied-mutation sequence S the snapshot encodes
     */
    record Snapshot(ConfigSnapshot snapshot, long seq) implements EdgeStream {
        public Snapshot {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            if (seq < 0) {
                throw new IllegalArgumentException("seq must be non-negative: " + seq);
            }
        }
    }

    /**
     * A server-to-edge heartbeat: protocol carrier only - the idle-staleness frontier measure
     * is the edge client's job. The edge records {@code serverNowMillis}
     * as {@code lastHeartbeat} and counts it; staleness wiring is deliberately NOT done
     * here (done by the edge client).
     *
     * @param latestSeq       the server's highest applied-mutation seq at emit time
     * @param serverNowMillis the server's clock at emit time
     */
    record Heartbeat(long latestSeq, long serverNowMillis) implements EdgeStream {
        public Heartbeat {
            if (serverNowMillis < 0) {
                throw new IllegalArgumentException("serverNowMillis must be non-negative: " + serverNowMillis);
            }
        }
    }

    /**
     * A server-initiated {@code ERROR_CLOSE} (governor disconnect): what the wire shows when the
     * slow-consumer policy disconnects a subscriber
     * ({@link io.configd.distribution.wire.ErrorCode#QUARANTINED}, code 8). The
     * {@link EdgeActor} maps it onto the real core's
     * {@code onFrame(EdgeFrame.ErrorClose)} so the edge-side reaction (the existing
     * reconnect directive) is the PRODUCTION code path, not a sim re-implementation.
     * Only the opt-in governor wiring in {@link C1StreamDriver} ever sends this -
     * the gate path never does, so existing seeds are byte-identical.
     *
     * @param code    the wire error code
     * @param message diagnostic close message
     */
    record ErrorClose(io.configd.distribution.wire.ErrorCode code, String message)
            implements EdgeStream {
        public ErrorClose {
            Objects.requireNonNull(code, "code must not be null");
        }
    }
}
