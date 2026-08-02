package io.configd.distribution;

import java.util.List;
import java.util.Objects;

/**
 * The commit-notification boundary: the interface the data-plane fan-out builds
 * against. It exposes the stream of committed mutations as cursor-based,
 * replayable {@link CommitNotification}s, with an explicit overflow contract.
 *
 * <p><b>This interface is transport-agnostic.</b> It defines NO wire protocol
 * and performs NO fan-out - the edge path owns transport and subscriptions.
 * The implementation ({@link FanOutBuffer}) is a bounded, hot-path cache in
 * front of the durable log+snapshot; it is NOT the source of truth.
 *
 * <h2>Cursor semantics</h2>
 * A consumer holds the applied-mutation sequence S of the last notification it
 * processed (its <b>cursor</b>) and calls {@link #readSince(long)} with it.
 * {@code readSince(c)} returns the contiguous run of notifications with
 * {@code seq > c}, in ascending seq order. A fresh consumer starts at cursor 0
 * (or any S {@code <=} the oldest retained seq) to receive everything still
 * buffered.
 *
 * <h2>Overflow and gap contract</h2>
 * The buffer is a bounded ring (drop-oldest on overflow - see {@link FanOutBuffer}).
 * If a consumer's cursor has fallen behind the oldest retained notification, the
 * contiguous run from {@code cursor} is no longer reconstructable from the buffer.
 * In that case {@link #readSince(long)} returns a {@link Result#gap(long)} signal -
 * it NEVER returns a partial or duplicated run that silently skips evicted
 * notifications. On a GAP the consumer replays from the {@link ReplaySource}
 * (snapshot + tail) and resumes cursor-based tailing from the replay floor.
 *
 * <p>The durability argument that makes drop-oldest safe: the log+snapshot durable
 * prefix reconstructs ALL committed state, so an evicted notification is never
 * lost data - only evicted from the cache. The {@link ReplaySource} is the
 * authoritative recovery path; {@code readSince} is the fast tail.
 */
public interface CommitNotificationSource {

    /**
     * Returns the contiguous run of notifications with {@code seq > cursor}, or a
     * GAP signal if that run is no longer fully retained.
     *
     * @param cursor the consumer's last-applied sequence S (>= 0); 0 requests
     *               everything still retained
     * @return an {@link Result} that is either {@link Result#ok(List)} (possibly
     *         empty when the consumer is caught up) or {@link Result#gap(long)}
     * @throws IllegalArgumentException if {@code cursor < 0}
     */
    Result readSince(long cursor);

    /**
     * The highest applied-mutation sequence S currently retained, or {@code -1}
     * if the buffer is empty. A consumer is caught up when its cursor equals this.
     */
    long latestSeq();

    /**
     * The lowest applied-mutation sequence S currently retained, or {@code -1} if
     * the buffer is empty. A cursor strictly below this minus one cannot be
     * served contiguously and will receive a GAP.
     */
    long oldestSeq();

    /**
     * Total notifications dropped (evicted by overflow) over the lifetime of this
     * source. Mirrors the {@code fanout_buffer_dropped_total} metric; exposed here
     * so tests can observe eviction without a metrics registry.
     */
    long droppedTotal();

    sealed interface Result permits Result.Ok, Result.Gap {

        record Ok(List<CommitNotification> notifications) implements Result {
            public Ok {
                Objects.requireNonNull(notifications, "notifications must not be null");
                notifications = List.copyOf(notifications);
            }
        }

        /**
         * The requested cursor has been overtaken by eviction; the contiguous run
         * cannot be served from the buffer. The consumer must replay from the
         * {@link ReplaySource}, then resume tailing.
         *
         * @param oldestRetainedSeq the lowest seq still in the buffer (the cache
         *                          floor); the consumer's replay must land at or
         *                          below this so tailing resumes without a hole.
         *                          {@code -1} when the buffer is empty.
         */
        record Gap(long oldestRetainedSeq) implements Result {}

        static Result ok(List<CommitNotification> notifications) {
            return new Ok(notifications);
        }

        static Result gap(long oldestRetainedSeq) {
            return new Gap(oldestRetainedSeq);
        }

        default boolean isGap() {
            return this instanceof Gap;
        }
    }
}
