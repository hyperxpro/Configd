package io.configd.distribution.fanout;

import java.util.Set;

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
 * @param serverSidePrefixFilter whether the drain filters whole signed deltas to the
 *                              subscribed prefix set server-side. The library default
 *                              is {@code false} (full-chain, byte-identical); the product default
 *                              is set at the deployment boundary ({@code ConfigdServer}).
 * @param strongReadPrefixes    the strong-read (always-shipped) key prefixes: a delta touching
 *                              any such key is never filtered, regardless of the subscribed
 *                              prefixes, so the edge holds them for suppression-detectability.
 *                              Empty when {@code serverSidePrefixFilter} is off (unused).
 * @param allowPartialShardView whether a legacy whole-store SUBSCRIBE connection is admitted at
 *                              N>1. The legacy SUBSCRIBE plane serves the PRIMARY shard only, so at
 *                              N>1 it is a partial keyspace view; the driver refuses it per-connection
 *                              ({@code BAD_SUBSCRIBE}) unless this is set. Gates the legacy SUBSCRIBE
 *                              plane ONLY - multi-shard WATCH is served at N>1 regardless. Default
 *                              {@code false} (fail-closed); never consulted at N=1 (one shard is the
 *                              whole keyspace).
 */
public record FanOutConfig(
        int queueFrames,
        int queueWarnPct,
        int batchMaxNotifications,
        int batchMaxBytes,
        long ackLagDemoteSeqs,
        long heartbeatMs,
        long idlePollMs,
        int snapshotChunkBytes,
        boolean serverSidePrefixFilter,
        Set<String> strongReadPrefixes,
        boolean allowPartialShardView
) {

    public FanOutConfig {
        strongReadPrefixes = strongReadPrefixes == null ? Set.of() : Set.copyOf(strongReadPrefixes);
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

    /**
     * The eight-arg convenience policy: server-side prefix filtering OFF, no strong-read prefixes,
     * and the legacy-SUBSCRIBE partial-view fail-closed ({@code allowPartialShardView=false}).
     * Existing callers use this arity unchanged and stay byte-identical.
     */
    public FanOutConfig(int queueFrames, int queueWarnPct, int batchMaxNotifications,
                        int batchMaxBytes, long ackLagDemoteSeqs, long heartbeatMs,
                        long idlePollMs, int snapshotChunkBytes) {
        this(queueFrames, queueWarnPct, batchMaxNotifications, batchMaxBytes, ackLagDemoteSeqs,
                heartbeatMs, idlePollMs, snapshotChunkBytes, false, Set.of(), false);
    }

    public static FanOutConfig defaults() {
        return new FanOutConfig(
                256,
                80,
                64,
                262_144,
                8_192L,
                250L,
                5L,
                1_048_576);
    }

    /**
     * The deployment boundary ({@code ConfigdServer}) uses this to flip the product default ON
     * with the resolved strong-read prefixes, leaving the library {@link #defaults()}
     * conservative.
     */
    public FanOutConfig withServerSidePrefixFilter(boolean on, Set<String> strongReadPrefixes) {
        return new FanOutConfig(queueFrames, queueWarnPct, batchMaxNotifications, batchMaxBytes,
                ackLagDemoteSeqs, heartbeatMs, idlePollMs, snapshotChunkBytes, on, strongReadPrefixes,
                allowPartialShardView);
    }

    /**
     * The deployment boundary ({@code ConfigdServer}) flips this ON from
     * {@code -Dconfigd.edge.allowPartialShardView} so a legacy whole-store SUBSCRIBE is admitted at
     * N>1 (accepting the primary-shard-only view); left {@code false} it is refused per-connection.
     * Gates the legacy SUBSCRIBE plane only - multi-shard WATCH is served at N>1 regardless.
     */
    public FanOutConfig withAllowPartialShardView(boolean on) {
        return new FanOutConfig(queueFrames, queueWarnPct, batchMaxNotifications, batchMaxBytes,
                ackLagDemoteSeqs, heartbeatMs, idlePollMs, snapshotChunkBytes, serverSidePrefixFilter,
                strongReadPrefixes, on);
    }

    public int queueWarnThresholdFrames() {
        return (int) ((long) queueFrames * queueWarnPct / 100L);
    }
}
