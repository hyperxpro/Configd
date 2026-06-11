package io.configd.testkit;

import io.configd.distribution.CommitNotification;
import io.configd.store.ConfigSnapshot;

import java.util.Objects;

/**
 * Sim-internal message model for the CP→edge fan-out channel (Phase V1).
 * <p>
 * This is the minimal envelope the simulated edge network carries between a
 * {@link StreamDriver} (server side) and an {@link EdgeActor} (edge side). It is
 * <b>deliberately not</b> the production wire protocol — defining the real frames
 * (length-prefix, codec, flow-control) is component <b>C1</b>'s job, on top of the
 * transport-agnostic {@link io.configd.distribution.CommitNotificationSource}
 * boundary (ADR-0034). Keeping this sealed and tiny prevents the sim from
 * accidentally fixing wire decisions that belong to C1.
 *
 * <p>Two variants mirror the two things the ADR-0034 §"Handoff to Session 3"
 * consumer loop can receive:
 * <ul>
 *   <li>{@link Notify} — a single committed-mutation {@link CommitNotification}
 *       (the {@code Ok} tail path: {@code seq}, {@code commitTimestampMillis},
 *       {@code delta}).</li>
 *   <li>{@link Snapshot} — a snapshot-equivalent state at {@code seq} (the
 *       {@code Gap → ReplaySource.replayFromSnapshot()} recovery path).</li>
 * </ul>
 */
sealed interface EdgeStream permits EdgeStream.Notify, EdgeStream.Snapshot {

    /**
     * A single committed-mutation notification pushed over the edge channel
     * (ADR-0034 §"Handoff" step 1, the {@code Ok} tail path).
     *
     * @param notification the committed-mutation notification (non-null)
     */
    record Notify(CommitNotification notification) implements EdgeStream {
        public Notify {
            Objects.requireNonNull(notification, "notification must not be null");
        }
    }

    /**
     * A snapshot-equivalent recovery payload pushed over the edge channel after a
     * GAP (ADR-0034 §"Handoff" step 2: {@code ReplaySource.replayFromSnapshot()}
     * → apply wholesale, set cursor to {@code seq}, resume tailing).
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
}
