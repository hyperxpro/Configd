package io.configd.distribution.fanout;

/**
 * Named, validated configuration for a {@link FanOutSessionCore}. Every policy
 * threshold is a named config with a metric. Defaults: see {@link #defaults()}.
 *
 * @param queueFrames           bounded outbound queue depth: max NOTIFY frames offered
 *                              but not yet acknowledged before overflow demotion
 *                              ({@code edge.fanout.session.queueFrames}, default 256;
 *                              metric {@code edge_fanout_queue_depth})
 * @param queueWarnPct          slow-consumer warning threshold as a percent of
 *                              {@code queueFrames} (default 80; metric
 *                              {@code edge_fanout_slow_consumer_warnings_total})
 * @param batchMaxNotifications max notifications per NOTIFY frame
 *                              ({@code edge.fanout.notify.batchMaxNotifications}, default 64;
 *                              metric {@code edge_fanout_notify_batch_size})
 * @param batchMaxBytes         max encoded NOTIFY payload bytes
 *                              ({@code edge.fanout.notify.batchMaxBytes}, default 256 KiB)
 * @param ackLagDemoteSeqs      ack-lag demotion threshold: demote when
 *                              {@code latestOffered - lastAcked} exceeds this
 *                              ({@code edge.fanout.session.ackLagDemoteSeqs}, default 8192;
 *                              metric {@code edge_fanout_demotions_total{reason=ack_lag}})
 * @param heartbeatMs           heartbeat cadence when otherwise idle
 *                              ({@code edge.fanout.heartbeatMs}, default 250;
 *                              metric {@code edge_fanout_heartbeats_total})
 * @param idlePollMs            adaptive idle-poll backoff cap (default 5)
 * @param snapshotChunkBytes    snapshot chunk payload size (default 1 MiB; bounded at
 *                              {@link io.configd.distribution.wire.EdgeFrameCodec#MAX_SNAPSHOT_CHUNK_BYTES})
 */
public record FanOutConfig(
        int queueFrames,
        int queueWarnPct,
        int batchMaxNotifications,
        int batchMaxBytes,
        long ackLagDemoteSeqs,
        long heartbeatMs,
        long idlePollMs,
        int snapshotChunkBytes
) {

    public FanOutConfig {
        if (queueFrames <= 0) {
            throw new IllegalArgumentException("queueFrames must be positive: " + queueFrames);
        }
        if (queueWarnPct < 0 || queueWarnPct > 100) {
            throw new IllegalArgumentException("queueWarnPct must be in [0, 100]: " + queueWarnPct);
        }
        if (batchMaxNotifications <= 0) {
            throw new IllegalArgumentException(
                    "batchMaxNotifications must be positive: " + batchMaxNotifications);
        }
        if (batchMaxNotifications > io.configd.distribution.wire.EdgeFrameCodec.MAX_NOTIFY_BATCH) {
            throw new IllegalArgumentException("batchMaxNotifications " + batchMaxNotifications
                    + " exceeds codec cap " + io.configd.distribution.wire.EdgeFrameCodec.MAX_NOTIFY_BATCH);
        }
        if (batchMaxBytes <= 0) {
            throw new IllegalArgumentException("batchMaxBytes must be positive: " + batchMaxBytes);
        }
        if (batchMaxBytes > io.configd.distribution.wire.EdgeFrameCodec.MAX_NOTIFY_BATCH_BYTES) {
            throw new IllegalArgumentException("batchMaxBytes " + batchMaxBytes
                    + " exceeds codec cap " + io.configd.distribution.wire.EdgeFrameCodec.MAX_NOTIFY_BATCH_BYTES);
        }
        if (ackLagDemoteSeqs <= 0) {
            throw new IllegalArgumentException("ackLagDemoteSeqs must be positive: " + ackLagDemoteSeqs);
        }
        if (heartbeatMs <= 0) {
            throw new IllegalArgumentException("heartbeatMs must be positive: " + heartbeatMs);
        }
        if (idlePollMs <= 0) {
            throw new IllegalArgumentException("idlePollMs must be positive: " + idlePollMs);
        }
        if (snapshotChunkBytes <= 0) {
            throw new IllegalArgumentException("snapshotChunkBytes must be positive: " + snapshotChunkBytes);
        }
        if (snapshotChunkBytes > io.configd.distribution.wire.EdgeFrameCodec.MAX_SNAPSHOT_CHUNK_BYTES) {
            throw new IllegalArgumentException("snapshotChunkBytes " + snapshotChunkBytes
                    + " exceeds codec cap " + io.configd.distribution.wire.EdgeFrameCodec.MAX_SNAPSHOT_CHUNK_BYTES);
        }
    }

    /** Defaults: 256 / 80% / 64 / 256 KiB / 8192 / 250 ms / 5 ms / 1 MiB. */
    public static FanOutConfig defaults() {
        return new FanOutConfig(
                256,        // queueFrames
                80,         // queueWarnPct
                64,         // batchMaxNotifications
                262_144,    // batchMaxBytes (256 KiB)
                8_192L,     // ackLagDemoteSeqs
                250L,       // heartbeatMs
                5L,         // idlePollMs
                1_048_576); // snapshotChunkBytes (1 MiB)
    }

    /** The queue depth (in frames) at which a slow-consumer warning fires. */
    public int queueWarnThresholdFrames() {
        return (int) ((long) queueFrames * queueWarnPct / 100L);
    }
}
