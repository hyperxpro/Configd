package io.configd.testkit;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.fanout.DemotionEvent;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.FanOutSessionCore;
import io.configd.distribution.fanout.FanOutSessionMetrics;
import io.configd.distribution.fanout.SlowConsumerGovernor;
import io.configd.distribution.fanout.TransportSink;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeSnapshotCodec;
import io.configd.distribution.wire.ErrorCode;
import io.configd.store.ConfigSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * The REAL stream driver ({@link StreamDriver}): drives the production
 * {@link io.configd.distribution.fanout.FanOutSessionCore} - the same code the live
 * {@code FanOutServer} runs - once per subscribed edge each sim tick. This is
 * what replaces {@link StreamDriver#NONE}: with it, committed mutations actually reach the
 * edges over the simulated edge network, so {@link EdgePropagationBacklogTest} converges
 * and the seed sweep exercises the live drain logic.
 *
 * <h2>Wiring (per edge)</h2>
 * Each edge gets one {@link FanOutSessionCore} bound to its subscribed CP node's
 * {@link CommitNotificationSource} / {@link ReplaySource} (the sim's per-node FanOutBuffer
 * and SnapshotReplaySource - the commit-notification handoff seams). A {@link SimSink} maps the session's
 * {@link EdgeFrame}s onto {@link EdgeStream} messages over the edge {@link AdversarialNetwork}:
 * <ul>
 *   <li>{@code NOTIFY} -> {@link EdgeStream.NotifyBatch} (verbatim chain, batched frame format);</li>
 *   <li>{@code SNAPSHOT_BEGIN} / {@code SNAPSHOT_CHUNK} / {@code SNAPSHOT_END} -> reassembled
 *       <b>driver-side</b> into one
 *       {@link EdgeStream.Snapshot} (so {@link EdgeActor} stays simple - it applies one
 *       wholesale snapshot, as it did under the DirectInjectionDriver);</li>
 *   <li>{@code HEARTBEAT} -> {@link EdgeStream.Heartbeat};</li>
 *   <li>{@code SUBSCRIBE_OK} and the {@code DEMOTED_TO_CATCHUP} notice are consumed by the
 *       driver (server-internal); a fatal {@code ERROR_CLOSE} (e.g. GAP_UNRECOVERABLE) is
 *       recorded so a test can see it.</li>
 * </ul>
 * The edge-to-server {@code CURSOR_ACK} is routed back synchronously by wiring
 * {@link EdgeActor#setCursorAckSink} to {@code session.onCursorAck} (the sim has no return
 * channel latency model for acks; the design treats the ack as the edge's applied-cursor
 * signal, delivered when the edge applies - deterministic and sufficient for the
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

    /**
     * Opt-in slow-consumer governor. Null (the default) preserves the historical behavior
     * byte-for-byte: no admission, no demotion feed, no policy disconnects. With a governor:
     * each session's demotions feed it, queue-pressure edges and ack progress are reported per
     * tick, a QUARANTINED/UNHEALTHY verdict kicks the connection (dead sink + on-wire
     * {@link EdgeStream.ErrorClose} code 8 - the real edge core reaction runs), and every
     * (re)subscribe routes through {@code admit} - refusals are retried each tick, modelling the
     * production edge's bounded reconnect loop.
     */
    private final SlowConsumerGovernor governor;

    /** One session per edge id, created lazily on first drive (deterministic order). */
    private final Map<Integer, FanOutSessionCore> sessions = new LinkedHashMap<>();
    private final Map<Integer, SimSink> sinks = new LinkedHashMap<>();

    /** Fatal close events observed (e.g. GAP_UNRECOVERABLE) - exposed for tests. */
    private final List<String> fatalCloses = new ArrayList<>();

    /** Reconnect recovery resubscribes performed (per test assertions). */
    private int resubscribes;

    /** (re)subscribes refused by admission, retried each tick (deterministic order). */
    private final Map<Integer, PendingResubscribe> pendingResubscribes = new LinkedHashMap<>();

    /** Edges whose queue is currently at/above the warn threshold (edge detection). */
    private final Set<Integer> aboveWarnEdges = new HashSet<>();

    /** The nowMs of the current/most recent drive tick (the governor's time source). */
    private long lastDriveNowMs;

    private record PendingResubscribe(EdgeActor edge, long cursor) { }

    /**
     * Sim-tuned config. The production {@link FanOutConfig#defaults()} ack-lag threshold is
     * 8192 seqs (tuned for 10k writes/s); the sim commits only tens of seqs per run, so a
     * production threshold would never trigger ack-lag demotion. We scale the ack-lag and
     * queue thresholds DOWN so the demotion->snapshot->recovery path actually exercises at sim
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
                2L,        // ackLagDemoteSeqs - sim-scaled (prod 8192). The sim commits only
                           // tens of seqs per run, so the threshold must be SMALL relative to
                           // the workload or a behind edge never accrues enough lag to trigger
                           // the demotion->snapshot recovery and is stranded (incl. the common
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
        this(config, null);
    }

    /** A driver with the slow-consumer governor live (opt-in; null = historical). */
    C1StreamDriver(FanOutConfig config, SlowConsumerGovernor governor) {
        this.config = config;
        this.governor = governor;
        // The session core only reads the clock via tick(now); a trivial clock suffices.
        this.clock = new Clock() {
            @Override public long currentTimeMillis() { return 0L; }
            @Override public long nanoTime() { return 0L; }
        };
    }

    @Override
    public void drive(Context ctx) {
        long now = ctx.nowMs();
        lastDriveNowMs = now;
        if (governor != null) {
            retryRefusedResubscribes(ctx);
        }
        for (EdgeActor edge : ctx.edges()) {
            FanOutSessionCore session = sessions.get(edge.edgeId());
            if (session == null) {
                if (governor != null && pendingResubscribes.containsKey(edge.edgeId())) {
                    continue; // refused by admission; the per-tick retry loop owns it
                }
                session = subscribe(ctx, edge);
                if (session == null) {
                    continue; // admission refused the initial subscribe (now pending)
                }
            }
            session.tick(now);
            // Governor: queue-pressure edges + the time-driven SLOW evaluation (skipped if the
            // demotion listener kicked this session during the tick).
            if (governor != null && sessions.get(edge.edgeId()) == session) {
                feedQueuePressure(edge, session, now);
            }
        }
    }

    /**
     * Lazily creates + subscribes a session for {@code edge} on first sight. With the
     * governor live the subscribe routes through admission (a refusal goes to the per-tick
     * retry loop and this returns null) - the sim analogue of the production server
     * refusing the SUBSCRIBE and the edge's connect loop retrying.
     */
    private FanOutSessionCore subscribe(Context ctx, EdgeActor edge) {
        // SUBSCRIBE: full-store, fresh resume cursor 0 (a fresh edge cache bootstraps from 0).
        return subscribeWithAdmission(ctx, edge, 0L, false);
    }

    /**
     * The single (re)subscribe path: admission (when the governor is live), session + sink
     * creation, ack-sink wiring, SUBSCRIBE. Returns null when admission refused (the
     * attempt is parked in {@link #pendingResubscribes} and retried each tick).
     */
    private FanOutSessionCore subscribeWithAdmission(Context ctx, EdgeActor edge,
                                                     long resumeCursor, boolean reconnect) {
        long cursor = resumeCursor;
        if (governor != null) {
            SlowConsumerGovernor.Admission admission =
                    governor.admit(identity(edge), lastDriveNowMs);
            switch (admission.decision()) {
                case REFUSE -> {
                    pendingResubscribes.put(edge.edgeId(), new PendingResubscribe(edge, cursor));
                    return null;
                }
                // Forced re-bootstrap: cursor rebound to 0 so the decideMode cursor-0
                // rule yields SNAPSHOT_FIRST - exactly the FanOutServer admission rewrite.
                case ALLOW_FORCE_SNAPSHOT -> cursor = 0L;
                case ALLOW -> { }
            }
        }
        int cpNode = edge.subscribedCpNode();
        CommitNotificationSource source = ctx.source(cpNode);
        ReplaySource replay = ctx.replaySource(cpNode);
        SimSink sink = new SimSink(ctx, edge);
        FanOutSessionCore session = new FanOutSessionCore(
                source, replay, sink, config, FanOutSessionMetrics.NOOP, clock,
                governor == null ? null : event -> onDemotion(ctx, edge, event));

        // Wire the edge's CURSOR_ACK back to this session (synchronous ack channel).
        edge.setCursorAckSink(ackSink(edge, session));

        session.onSubscribe(new EdgeFrame.Subscribe(
                true, List.of(), cursor, -1L, identity(edge)));

        sessions.put(edge.edgeId(), session);
        sinks.put(edge.edgeId(), sink);
        if (reconnect) {
            resubscribes++;
            edge.onResubscribed();
        }
        return session;
    }

    private static String identity(EdgeActor edge) {
        return "edge-" + edge.edgeId();
    }

    /** The ack channel; with the governor live, an advancing ack also reports progress. */
    private LongConsumer ackSink(EdgeActor edge, FanOutSessionCore session) {
        if (governor == null) {
            return session::onCursorAck;
        }
        String identity = identity(edge);
        return seq -> {
            long before = session.lastAckedSeq();
            session.onCursorAck(seq);
            if (session.lastAckedSeq() > before) {
                governor.onAckProgress(identity, session.cursor(), session.lastAckedSeq(),
                        lastDriveNowMs);
            }
        };
    }

    List<String> fatalCloses() {
        return List.copyOf(fatalCloses);
    }

    /** Reconnect recovery resubscribes performed via {@link #resubscribe}. */
    int resubscribes() {
        return resubscribes;
    }

    /**
     * Recovery seam: re-subscribes {@code edge} at {@code resumeCursor} - the sim analogue
     * of the edge process tearing down its connection and re-subscribing. The old session is
     * neutralized (its sink goes dead - frames from a torn-down connection never reach the
     * edge) and a fresh {@link FanOutSessionCore} runs the server's already-tested
     * TAIL/SNAPSHOT_FIRST decision for the carried cursor: the recovery path is the
     * subscription path, with zero new wire surface. Deterministic: single sim thread,
     * invoked from the edge's directive drain.
     */
    void resubscribe(Context ctx, EdgeActor edge, long resumeCursor) {
        SimSink oldSink = sinks.get(edge.edgeId());
        if (oldSink != null) {
            oldSink.dead = true;
        }
        sessions.remove(edge.edgeId());
        subscribeWithAdmission(ctx, edge, resumeCursor, true);
    }

    // Governor plumbing (all of it conditional on a non-null governor).

    /**
     * Demotion-listener seam (mirrors the FanOutServer connection): every demotion feeds
     * the governor; a QUARANTINED/UNHEALTHY verdict disconnects the subscriber - the dead
     * sink models the closed socket, and the on-wire {@link EdgeStream.ErrorClose}
     * ({@code ErrorCode.QUARANTINED}, code 8) reaches the edge so the REAL core reaction
     * (the reconnect directive) runs.
     */
    private void onDemotion(Context ctx, EdgeActor edge, DemotionEvent event) {
        SlowConsumerGovernor.ConsumerState state =
                governor.onDemotion(identity(edge), event, lastDriveNowMs);
        if (state == SlowConsumerGovernor.ConsumerState.QUARANTINED
                || state == SlowConsumerGovernor.ConsumerState.UNHEALTHY) {
            policyKick(ctx, edge, state, event);
        }
    }

    private void policyKick(Context ctx, EdgeActor edge, SlowConsumerGovernor.ConsumerState state,
                            DemotionEvent event) {
        SimSink sink = sinks.remove(edge.edgeId());
        if (sink != null) {
            sink.dead = true; // the closed socket: orphaned session frames go nowhere
        }
        sessions.remove(edge.edgeId());
        edge.setCursorAckSink(null); // a closed socket carries no acks
        aboveWarnEdges.remove(edge.edgeId());
        ctx.send(edge, new EdgeStream.ErrorClose(ErrorCode.QUARANTINED,
                "slow-consumer policy: " + state + " (" + event.reason() + ")"));
    }

    /** Per-tick queue-pressure edge detection + the time-driven SLOW evaluation. */
    private void feedQueuePressure(EdgeActor edge, FanOutSessionCore session, long now) {
        int warnThreshold = config.queueWarnThresholdFrames();
        boolean above = warnThreshold > 0 && session.inFlightFrames() >= warnThreshold;
        boolean wasAbove = aboveWarnEdges.contains(edge.edgeId());
        if (above != wasAbove) {
            if (above) {
                aboveWarnEdges.add(edge.edgeId());
            } else {
                aboveWarnEdges.remove(edge.edgeId());
            }
            governor.onQueuePressure(identity(edge), above,
                    session.cursor(), session.lastAckedSeq(), now);
        }
        if (above) {
            governor.evaluate(identity(edge), now); // sim: per-tick is fine
        }
    }

    /**
     * Retries admission-refused (re)subscribes once per tick - the sim analogue of the
     * production edge's bounded reconnect loop hitting the SUBSCRIBE refusal until the
     * cooldown readmits (each refusal is counted on
     * {@code edge_fanout_reconnects_refused_total}).
     */
    private void retryRefusedResubscribes(Context ctx) {
        if (pendingResubscribes.isEmpty()) {
            return;
        }
        // Snapshot-then-clear: a still-refused attempt re-parks itself into the map via
        // subscribeWithAdmission, which would otherwise be a concurrent modification.
        List<PendingResubscribe> retries = new ArrayList<>(pendingResubscribes.values());
        pendingResubscribes.clear();
        for (PendingResubscribe pending : retries) {
            if (!pending.edge().alive()) {
                continue; // a crashed edge re-enters via the auto-subscribe path on restart
            }
            subscribeWithAdmission(ctx, pending.edge(), pending.cursor(), true);
        }
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
                // The edge tore this connection down via a resubscribe; a dead transport
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
                // CursorAck / Subscribe are edge->server (never offered by the session here).
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
