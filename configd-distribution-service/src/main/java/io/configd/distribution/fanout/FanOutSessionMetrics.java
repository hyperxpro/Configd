package io.configd.distribution.fanout;

/**
 * Per-session metrics sink for a {@link FanOutSessionCore} (C1 design §4 metric table;
 * charter §6 rule 8 — every policy decision is observable). Follows the codebase's
 * leaf-module metrics-sink idiom (a small SAM-style interface with a {@link #NOOP}
 * sentinel, e.g. {@code FanOutMetrics}/{@code StateMachineMetrics}) so this module needs
 * no {@code configd-observability} dependency; the live server bridges each method to a
 * real {@code MetricsRegistry} counter/histogram via method references.
 *
 * <p>Each method maps to a design §4 {@code edge_fanout_*} series. The Prometheus name is
 * documented per method; a registry name of {@code "edge.fanout.x"} exports as
 * {@code edge_fanout_x_total} (counters) / {@code _count}+percentiles (histograms).
 */
public interface FanOutSessionMetrics {

    /**
     * A NOTIFY batch was offered to the transport.
     * Series {@code edge_fanout_notify_batch_size} (histogram; count + the bytes variant).
     *
     * @param n     the number of notifications in the batch
     * @param bytes the encoded batch payload size in bytes
     */
    void onNotifyBatch(int n, int bytes);

    /**
     * The current outbound queue depth (unacked NOTIFY frames).
     * Series {@code edge_fanout_queue_depth} (per-session gauge).
     *
     * @param depth the current unacked-frame count
     */
    void onQueueDepth(int depth);

    /**
     * The queue crossed {@code queueWarnPct} of {@code queueFrames} — a slow consumer.
     * Series {@code edge_fanout_slow_consumer_warnings_total} (counter).
     */
    void onSlowConsumerWarning();

    /**
     * The session was demoted from streaming to catch-up (snapshot) mode.
     * Series {@code edge_fanout_demotions_total{reason=...}} (counter, labelled by reason).
     *
     * @param reason the demotion reason label (e.g. {@code queue_overflow}, {@code ack_lag},
     *               {@code gap}, {@code transport_block})
     */
    void onDemotion(String reason);

    /**
     * A snapshot transfer (BEGIN + chunks + END) was emitted.
     * Series {@code edge_fanout_snapshot_transfers_total} (counter).
     */
    void onSnapshotTransfer();

    /**
     * A HEARTBEAT frame was emitted.
     * Series {@code edge_fanout_heartbeats_total} (counter).
     */
    void onHeartbeat();

    /**
     * The session closed.
     * Series {@code edge_fanout_sessions_closed_total{reason=...}} (counter).
     *
     * @param reason the close reason label
     */
    void onSessionClosed(String reason);

    /**
     * The subscribe-time replay-vs-re-bootstrap decision (C3; charter §6 rule 8 — the
     * decision AND its input are observable). Series
     * {@code edge_fanout_subscribe_tail_total} /
     * {@code edge_fanout_subscribe_snapshot_first_total} (per-reason-suffix convention; the
     * registry has no label support) and {@code edge_fanout_subscribe_horizon_distance}
     * (gauge: the last decision's distance).
     * <p>
     * {@code horizonDistance = cursor − (oldestRetainedSeq − 1)}: how far ABOVE the replay
     * horizon's edge the subscriber's cursor sits. {@code >= 0} ⇒ tail-recoverable (replay
     * from the boundary ring); {@code < 0} ⇒ beyond the horizon (snapshot re-bootstrap).
     * An empty ring reports {@code cursor + 1} (nothing evicted — trivially recoverable).
     * <p>
     * A {@code default} no-op so existing sinks ({@link #NOOP}, the sim) are unaffected.
     *
     * @param snapshotFirst   true ⇒ SNAPSHOT_FIRST was chosen, false ⇒ TAIL
     * @param horizonDistance the cursor's distance above the replay-horizon edge
     */
    default void onSubscribeMode(boolean snapshotFirst, long horizonDistance) { }

    /** No-op sink — the default for tests and any wiring that does not export metrics. */
    FanOutSessionMetrics NOOP = new FanOutSessionMetrics() {
        @Override public void onNotifyBatch(int n, int bytes) { }
        @Override public void onQueueDepth(int depth) { }
        @Override public void onSlowConsumerWarning() { }
        @Override public void onDemotion(String reason) { }
        @Override public void onSnapshotTransfer() { }
        @Override public void onHeartbeat() { }
        @Override public void onSessionClosed(String reason) { }
    };
}
