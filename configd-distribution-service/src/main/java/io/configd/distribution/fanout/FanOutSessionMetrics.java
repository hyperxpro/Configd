package io.configd.distribution.fanout;

/**
 * Per-session metrics sink for a {@link FanOutSessionCore}. Follows the codebase's
 * leaf-module metrics-sink idiom (a small SAM-style interface with a {@link #NOOP}
 * sentinel, e.g. {@code FanOutMetrics}/{@code StateMachineMetrics}) so this module needs
 * no {@code configd-observability} dependency; the live server bridges each method to a
 * real {@code MetricsRegistry} counter/histogram via method references.
 *
 * <p>Each method maps to an {@code edge_fanout_*} series. The Prometheus name is
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
     * The queue crossed {@code queueWarnPct} of {@code queueFrames} - a slow consumer.
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
     * The subscribe-time replay-vs-re-bootstrap decision. Series
     * {@code edge_fanout_subscribe_tail_total} /
     * {@code edge_fanout_subscribe_snapshot_first_total} and
     * {@code edge_fanout_subscribe_horizon_distance} (gauge: the last decision's distance).
     * <p>
     * {@code horizonDistance = cursor - (oldestRetainedSeq - 1)}: how far ABOVE the replay
     * horizon the subscriber's cursor sits. {@code >= 0} => tail-recoverable (replay from
     * the boundary ring); {@code < 0} => beyond the horizon (snapshot re-bootstrap). An
     * empty ring reports {@code cursor + 1} (nothing evicted, trivially recoverable).
     * <p>
     * A {@code default} no-op so existing sinks ({@link #NOOP}, the sim) are unaffected.
     *
     * @param snapshotFirst   true => SNAPSHOT_FIRST was chosen, false => TAIL
     * @param horizonDistance the cursor's distance above the replay-horizon edge
     */
    default void onSubscribeMode(boolean snapshotFirst, long horizonDistance) { }

    // ------------------------------------------------------------------
    // Slow-consumer policy series (SlowConsumerGovernor). All default no-ops
    // so existing sinks (NOOP, the sim) are unaffected.
    // ------------------------------------------------------------------

    /**
     * HEALTHY-to-SLOW: the queue stayed at/above the warn threshold for
     * {@code edge.fanout.policy.queueWarnWindowMs}.
     * Series {@code edge_fanout_slow_transitions_total} (counter).
     */
    default void onSlowTransition() { }

    /**
     * An identity tripped a demotion-window limit and was quarantined
     * ({@code edge.fanout.policy.demoteLimit} / {@code gapDemoteLimit}).
     * Series {@code edge_fanout_quarantines_total} (counter).
     */
    default void onQuarantine() { }

    /**
     * An identity hit {@code edge.fanout.policy.quarantineLimit} quarantines within
     * {@code unhealthyWindowMs} and was marked UNHEALTHY (alert-grade).
     * Series {@code edge_fanout_unhealthy_total} (counter).
     */
    default void onUnhealthy() { }

    /**
     * A SUBSCRIBE from a QUARANTINED/UNHEALTHY identity was refused inside its cooldown
     * (a flapping edge in cooldown is observable, never silently dark).
     * Series {@code edge_fanout_reconnects_refused_total} (counter).
     */
    default void onReconnectRefused() { }

    /**
     * A QUARANTINED/UNHEALTHY identity passed its cooldown and was readmitted with the
     * snapshot-first re-bootstrap forced (the automatic time-based exit).
     * Series {@code edge_fanout_readmissions_total} (counter).
     */
    default void onReadmission() { }

    /**
     * The per-state tracked-identity tallies after a transition. The gauge
     * {@code edge_fanout_consumer_state{state}} becomes one gauge per state
     * ({@code edge_fanout_consumer_state_healthy} ... {@code _unhealthy}),
     * the per-suffix encoding for the label-free registry.
     */
    default void onConsumerStates(int healthy, int slow, int catchup,
                                  int quarantined, int unhealthy) { }

    // ------------------------------------------------------------------
    // Server-side prefix-filtering series (ADR-0044). All default no-ops
    // so existing sinks (NOOP, the sim) are unaffected.
    // ------------------------------------------------------------------

    /**
     * {@code n} whole signed deltas were dropped by the server-side prefix filter (egress
     * saved). Series {@code edge_fanout_filtered_deltas_total} (counter).
     *
     * @param n the number of deltas dropped in this drain pass
     */
    default void onFilteredDeltas(int n) { }

    /**
     * {@code n} deltas were delivered to a filtered edge post-filter. Series
     * {@code edge_fanout_delivered_deltas_total} (counter). {@code delivered / (delivered +
     * filtered)} is the measured keyspace fraction the edge subscribes to.
     *
     * @param n the number of deltas delivered in this NOTIFY batch
     */
    default void onDeliveredDeltas(int n) { }

    /**
     * A cursor-advance HEARTBEAT (carrying the drained-through covered-S) was emitted on a
     * filtered session after the drain skipped deltas. Series
     * {@code edge_fanout_cursor_advances_total} (counter).
     */
    default void onCursorAdvance() { }

    /**
     * The session's server-side-filtering posture at subscribe time. A session that filters
     * increments {@code edge_fanout_filtered_sessions_total} (counter). A monotonic count of
     * filtered-session subscribes rather than a current-active gauge, because the session
     * lifecycle offers no filter-aware teardown hook to decrement a gauge honestly.
     *
     * @param active true when this session filters server-side
     */
    default void onFilterActive(boolean active) { }

    /** No-op sink - the default for tests and any wiring that does not export metrics. */
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
