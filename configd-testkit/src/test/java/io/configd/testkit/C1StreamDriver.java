package io.configd.testkit;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.FanOutSessionCore;
import io.configd.distribution.fanout.FanOutSessionMetrics;
import io.configd.distribution.fanout.TransportSink;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeSnapshotCodec;
import io.configd.distribution.wire.ErrorCode;
import io.configd.store.ConfigSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The REAL C1 {@link StreamDriver}: drives the production
 * {@link io.configd.distribution.fanout.FanOutSessionCore} — the same code the live
 * {@code FanOutServer} (part b) runs — once per subscribed edge each sim tick. This is
 * what replaces {@link StreamDriver#NONE}: with it, committed mutations actually reach the
 * edges over the simulated edge network, so {@link EdgePropagationBacklogTest} converges
 * and the 507-seed gate exercises the live drain logic.
 *
 * <h2>Wiring (per edge)</h2>
 * Each edge gets one {@link FanOutSessionCore} bound to its subscribed CP node's
 * {@link CommitNotificationSource} / {@link ReplaySource} (the sim's per-node FanOutBuffer
 * and SnapshotReplaySource — the ADR-0034 seams). A {@link SimSink} maps the session's
 * {@link EdgeFrame}s onto {@link EdgeStream} messages over the edge {@link AdversarialNetwork}:
 * <ul>
 *   <li>{@code NOTIFY} → {@link EdgeStream.NotifyBatch} (verbatim chain, ADR-0038);</li>
 *   <li>{@code SNAPSHOT_BEGIN} / {@code SNAPSHOT_CHUNK} / {@code SNAPSHOT_END} → reassembled
 *       <b>driver-side</b> into one
 *       {@link EdgeStream.Snapshot} (so {@link EdgeActor} stays simple — it applies one
 *       wholesale snapshot, as it did under the V1 DirectInjectionDriver);</li>
 *   <li>{@code HEARTBEAT} → {@link EdgeStream.Heartbeat};</li>
 *   <li>{@code SUBSCRIBE_OK} and the {@code DEMOTED_TO_CATCHUP} notice are consumed by the
 *       driver (server-internal); a fatal {@code ERROR_CLOSE} (e.g. GAP_UNRECOVERABLE) is
 *       recorded so a test can see it.</li>
 * </ul>
 * The edge→server {@code CURSOR_ACK} is routed back synchronously by wiring
 * {@link EdgeActor#setCursorAckSink} to {@code session.onCursorAck} (the sim has no return
 * channel latency model for acks; the design treats the ack as the edge's applied-cursor
 * signal, delivered when the edge applies — deterministic and sufficient for the V1
 * mechanism check).
 *
 * <h2>Determinism</h2>
 * Sessions are created and driven in a fixed order: edges in roster order; on the first
 * {@code drive} each edge is subscribed (full-store, resume cursor 0) and its ack sink
 * wired. {@link #drive} ticks every session in roster order. No randomness, no wall clock.
 */
final class C1StreamDriver implements StreamDriver {

    private final FanOutConfig config;
    private final Clock clock;

    /** One session per edge id, created lazily on first drive (deterministic order). */
    private final Map<Integer, FanOutSessionCore> sessions = new LinkedHashMap<>();
    private final Map<Integer, SimSink> sinks = new LinkedHashMap<>();

    /** Fatal close events observed (e.g. GAP_UNRECOVERABLE) — exposed for tests. */
    private final List<String> fatalCloses = new ArrayList<>();

    /** C3 recovery resubscribes performed (per test assertions). */
    private int resubscribes;

    /**
     * Sim-tuned config. The production {@link FanOutConfig#defaults()} ack-lag threshold is
     * 8192 seqs (tuned for 10k writes/s); the sim commits only tens of seqs per run, so a
     * production threshold would never trigger ack-lag demotion. We scale the ack-lag and
     * queue thresholds DOWN so the demotion→snapshot→recovery path actually exercises at sim
     * scale (the design's named thresholds are exactly the right knob): when the edge network
     * reorders/drops a delta the edge stops acking forward, the server's ack-lag fires, and a
     * snapshot heals the edge. Everything else stays at production defaults.
     */
    static FanOutConfig simConfig() {
        return new FanOutConfig(
                64,        // queueFrames
                80,        // queueWarnPct
                64,        // batchMaxNotifications
                262_144,   // batchMaxBytes
                2L,        // ackLagDemoteSeqs — sim-scaled (prod 8192). The sim commits only
                           // tens of seqs per run, so the threshold must be SMALL relative to
                           // the workload or a behind edge never accrues enough lag to trigger
                           // the demotion→snapshot recovery and is stranded (incl. the common
                           // case of an edge that gapped on the final one or two deltas of the
                           // stream). 2 heals those while staying above the single-tick steady-
                           // state lag of one in-flight batch (the snapshot recovery is
                           // idempotent, so an occasional extra demote is harmless).
                250L,      // heartbeatMs
                5L,        // idlePollMs
                1_048_576);// snapshotChunkBytes
    }

    C1StreamDriver() {
        this(simConfig());
    }

    C1StreamDriver(FanOutConfig config) {
        this.config = config;
        // The session core only reads the clock via tick(now); a trivial clock suffices.
        this.clock = new Clock() {
            @Override public long currentTimeMillis() { return 0L; }
            @Override public long nanoTime() { return 0L; }
        };
    }

    @Override
    public void drive(Context ctx) {
        long now = ctx.nowMs();
        for (EdgeActor edge : ctx.edges()) {
            FanOutSessionCore session = sessions.get(edge.edgeId());
            if (session == null) {
                session = subscribe(ctx, edge);
            }
            session.tick(now);
        }
    }

    /** Lazily creates + subscribes a session for {@code edge} on first sight. */
    private FanOutSessionCore subscribe(Context ctx, EdgeActor edge) {
        int cpNode = edge.subscribedCpNode();
        CommitNotificationSource source = ctx.source(cpNode);
        ReplaySource replay = ctx.replaySource(cpNode);
        SimSink sink = new SimSink(ctx, edge);
        FanOutSessionCore session = new FanOutSessionCore(
                source, replay, sink, config, FanOutSessionMetrics.NOOP, clock);

        // Wire the edge's CURSOR_ACK back to this session (synchronous ack channel).
        edge.setCursorAckSink(session::onCursorAck);

        // SUBSCRIBE: full-store, fresh resume cursor 0 (a fresh edge cache bootstraps from 0).
        session.onSubscribe(new EdgeFrame.Subscribe(
                true, List.of(), 0L, -1L, "edge-" + edge.edgeId()));

        sessions.put(edge.edgeId(), session);
        sinks.put(edge.edgeId(), sink);
        return session;
    }

    List<String> fatalCloses() {
        return List.copyOf(fatalCloses);
    }

    /** C3 recovery resubscribes performed via {@link #resubscribe}. */
    int resubscribes() {
        return resubscribes;
    }

    /**
     * C3 recovery seam: re-subscribes {@code edge} at {@code resumeCursor} — the sim
     * analogue of the edge process tearing down its connection and re-SUBSCRIBE-ing. The
     * OLD session is neutralized (its sink goes dead — frames from a torn-down connection
     * never reach the edge) and a FRESH {@link FanOutSessionCore} runs the server's
     * already-tested TAIL/SNAPSHOT_FIRST decision for the carried cursor (screen C3-1: the
     * recovery path IS the subscription path; zero new wire surface). Deterministic:
     * single sim thread, invoked from the edge's directive drain.
     */
    void resubscribe(Context ctx, EdgeActor edge, long resumeCursor) {
        SimSink oldSink = sinks.get(edge.edgeId());
        if (oldSink != null) {
            oldSink.dead = true;
        }
        int cpNode = edge.subscribedCpNode();
        SimSink sink = new SimSink(ctx, edge);
        FanOutSessionCore session = new FanOutSessionCore(
                ctx.source(cpNode), ctx.replaySource(cpNode), sink, config,
                FanOutSessionMetrics.NOOP, clock);
        edge.setCursorAckSink(session::onCursorAck);
        session.onSubscribe(new EdgeFrame.Subscribe(
                true, List.of(), resumeCursor, -1L, "edge-" + edge.edgeId()));
        sessions.put(edge.edgeId(), session);
        sinks.put(edge.edgeId(), sink);
        resubscribes++;
        edge.onResubscribed();
    }

    /**
     * Maps a session's outbound {@link EdgeFrame}s onto {@link EdgeStream} messages over the
     * edge network. Snapshot chunks are buffered and reassembled into one
     * {@link EdgeStream.Snapshot} at {@code SNAPSHOT_END}.
     */
    private final class SimSink implements TransportSink {
        private final Context ctx;
        private final EdgeActor edge;

        /** Set when the edge re-subscribed away from this session (old frames are dropped). */
        boolean dead;

        // In-progress snapshot reassembly state.
        private final List<EdgeFrame.SnapshotChunk> pendingChunks = new ArrayList<>();
        private long pendingSnapshotSeq = -1;
        private boolean inSnapshot;

        SimSink(Context ctx, EdgeActor edge) {
            this.ctx = ctx;
            this.edge = edge;
        }

        @Override
        public boolean offer(EdgeFrame frame) {
            if (dead) {
                // The edge tore this connection down (C3 resubscribe); a dead transport
                // swallows frames exactly like a closed socket. Returning true keeps the
                // orphaned session silent rather than self-closing on every emit.
                return true;
            }
            switch (frame) {
                case EdgeFrame.SubscribeOk ignored -> { /* server-internal handshake ack */ }
                case EdgeFrame.Notify n -> {
                    if (!n.notifications().isEmpty()) {
                        ctx.send(edge, new EdgeStream.NotifyBatch(n.notifications()));
                    }
                }
                case EdgeFrame.SnapshotBegin b -> {
                    inSnapshot = true;
                    pendingChunks.clear();
                    pendingSnapshotSeq = b.snapshotSeq();
                }
                case EdgeFrame.SnapshotChunk c -> pendingChunks.add(c);
                case EdgeFrame.SnapshotEnd e -> {
                    // Reassemble driver-side into one wholesale snapshot message.
                    byte[] body = EdgeSnapshotCodec.reassemble(pendingChunks);
                    ConfigSnapshot snap = EdgeSnapshotCodec.deserialize(body);
                    ctx.send(edge, new EdgeStream.Snapshot(snap, e.snapshotSeq()));
                    inSnapshot = false;
                    pendingChunks.clear();
                }
                case EdgeFrame.Heartbeat h ->
                        ctx.send(edge, new EdgeStream.Heartbeat(h.latestSeq(), h.serverNowMillis()));
                case EdgeFrame.ErrorClose err -> {
                    if (err.code() != ErrorCode.DEMOTED_TO_CATCHUP) {
                        // A fatal close (e.g. GAP_UNRECOVERABLE): record for tests.
                        fatalCloses.add("edge " + edge.edgeId() + ": " + err.code() + " " + err.message());
                    }
                    // DEMOTED_TO_CATCHUP is a non-fatal server-internal notice (the snapshot
                    // flow follows); the edge does not need a separate wire message for it.
                }
                // CursorAck / Subscribe are edge→server (never offered by the session here).
                default -> { /* unreachable for server-emitted frames */ }
            }
            return true; // the sim transport never blocks (latency/drops are the network's job)
        }

        @Override
        public void close(ErrorCode code, String message) {
            fatalCloses.add("edge " + edge.edgeId() + ": CLOSE " + code + " " + message);
        }
    }
}
