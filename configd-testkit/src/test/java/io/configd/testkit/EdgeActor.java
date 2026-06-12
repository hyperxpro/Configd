package io.configd.testkit;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeSnapshotCodec;
import io.configd.edge.EdgeClientCore;
import io.configd.edge.StalenessTracker;
import io.configd.edge.StrongReadKeyClass;
import io.configd.edge.VersionCursor;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigSnapshot;
import io.configd.store.ReadResult;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * A deterministic simulation actor modelling one eventually-consistent edge cache node.
 * It drives the <b>real production {@link EdgeClientCore}</b> for ALL protocol handling — the
 * apply chain, snapshot reassembly + cutover, the ADR-0039 frontier staleness measure, the
 * per-batch CURSOR_ACK, and the INV-M1 monitor-wired read seam all live inside the core, so the
 * 507-seed gate exercises real C2 code. The actor retains ONLY the sim-actor concerns:
 * <ul>
 *   <li>the deterministic FIFO {@link #inbox} fed by the edge network;</li>
 *   <li>crash/restart incarnations, lag/unlag (a cache has no durable state);</li>
 *   <li>the {@link EdgeStream} ↔ {@link EdgeFrame} network adapter (mapping the sim's
 *       message model onto the core's wire-frame {@code onFrame} surface).</li>
 * </ul>
 *
 * <p><b>Clock.</b> The core is driven by a {@link Clock} backed by the sim's logical time
 * ({@code timeSource}); there is no wall-clock or {@code System.nanoTime} on any path.
 * {@code nanoTime} is {@code currentTimeMillis * 1_000_000} for any nanos-based collaborator.
 *
 * <p><b>Cursor-ack channel.</b> The core emits {@code CURSOR_ACK} frames through its
 * {@link EdgeClientCore.FrameSink}; this actor forwards the ack seq to {@link #cursorAckSink}
 * (wired by the C1 driver to the owning session's {@code onCursorAck}). The C1(a) backward-
 * snapshot refusal lives in the core, which re-acks the real cursor.
 *
 * <p><b>Reconnect directives.</b> The core may queue a reconnect directive on heartbeat
 * silence (the C2 failover policy). The V1/C1 sim has no second fan-out endpoint to fail over
 * to (multi-endpoint failover is the C2 edge <i>process</i>'s job, part b), so this actor
 * <b>drains</b> directives and ignores them — the sim's own server-side ack-lag→snapshot heal
 * path (driven by {@link C1StreamDriver}) is what reconverges a stranded edge here.
 *
 * <p>Not thread-safe; one actor per node, mutated on the single sim thread (R-01).
 */
final class EdgeActor {

    /** Edge node ids start at 100 so they never collide with CP node ids 0..n-1. */
    static final int EDGE_ID_BASE = 100;

    private final int edgeId;
    /** The CP node id this edge subscribes to for its fan-out stream. */
    private final int subscribedCpNode;
    private final LongSupplier timeSource;
    private final Clock clock;

    /**
     * The INV-M1 monitor wired into the core's read store. Test-mode ({@code testMode = true})
     * so a {@code monotonic_read} violation throws an {@link AssertionError} that fails the
     * seed. One registry per actor (counts are diagnostic only).
     */
    private final InvariantMonitor monitor;

    /** Diagnostic registry shared by the monitor and the core's implausible-frontier counter. */
    private final MetricsRegistry metrics;

    /** The real production C2 client engine (rebuilt on every incarnation). */
    private EdgeClientCore core;

    /** Deterministic FIFO inbox; messages are delivered by the edge network. */
    private final Deque<EdgeStream> inbox = new ArrayDeque<>();

    /** Incarnation counter — bumped on every crash (models a cache reincarnation). */
    private int incarnation;

    private boolean alive = true;
    private boolean lagging;

    /**
     * OBSERVER-ONLY apply seam (Phase V2). Fired on every {@code APPLIED} notification so the
     * {@link io.configd.probe.PropagationProbe} can sample logical visibility time. Defaults
     * to {@link EdgeApplyObserver#NONE}. Survives crash/restart — it is a harness binding, not
     * edge state, so {@link #freshState()} does not touch it.
     */
    private EdgeApplyObserver applyObserver = EdgeApplyObserver.NONE;

    // Lifetime counters surfaced to EdgeActivity and the invariants.
    private long deliveredCount;

    /**
     * Edge→server CURSOR_ACK sink: invoked with the highest applied seq when the core emits a
     * {@code CURSOR_ACK} frame. The C1 driver wires this to {@code FanOutSessionCore.onCursorAck}.
     * {@code NONE} by default (the V1 DirectInjectionDriver path does not ack).
     */
    private LongConsumer cursorAckSink = NONE_ACK;

    private static final LongConsumer NONE_ACK = seq -> { };

    /**
     * OPT-IN C3 recovery seam: when non-null, drained {@link EdgeClientCore.ConnectionDirective}s
     * are forwarded here (the sim wires {@link EdgeFanOutSim#enableEdgeRecovery} to a real
     * {@link C1StreamDriver} resubscribe). Null (the default) preserves the historical
     * drain-and-ignore behavior — the 507-seed gate path is byte-identical.
     */
    private Consumer<EdgeClientCore.ConnectionDirective> directiveSink;

    EdgeActor(int edgeId, int subscribedCpNode, LongSupplier timeSource) {
        if (edgeId < EDGE_ID_BASE) {
            throw new IllegalArgumentException(
                    "edge id must be >= " + EDGE_ID_BASE + " (distinct from CP ids): " + edgeId);
        }
        this.edgeId = edgeId;
        this.subscribedCpNode = subscribedCpNode;
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource must not be null");
        this.clock = new Clock() {
            @Override public long currentTimeMillis() { return timeSource.getAsLong(); }
            @Override public long nanoTime() { return timeSource.getAsLong() * 1_000_000L; }
        };
        // testMode=true → monotonic_read violations throw AssertionError (fail the seed).
        this.metrics = new MetricsRegistry();
        this.monitor = new InvariantMonitor(metrics, true);
        freshState();
    }

    /** Builds a fresh production {@link EdgeClientCore} at cursor 0. */
    private void freshState() {
        // The core's FrameSink forwards CURSOR_ACK seqs to the wired cursorAckSink; all other
        // edge→server frames are never emitted by the core. The implausible-frontier counter
        // shares the actor's diagnostic registry. The sim runs full-store (no subscription).
        EdgeClientCore.FrameSink sink = frame -> {
            if (frame instanceof EdgeFrame.CursorAck ack) {
                cursorAckSink.accept(ack.seq());
            }
            return true; // the sim transport never blocks
        };
        this.core = new EdgeClientCore(clock, monitor,
                metrics.counter(StalenessTracker.IMPLAUSIBLE_METRIC),
                StrongReadKeyClass.DEFAULT, sink,
                EdgeClientCore.DEFAULT_HEARTBEAT_MS, EdgeClientCore.DEFAULT_SILENCE_FACTOR);
    }

    /**
     * Wires the edge→server CURSOR_ACK sink (C1). The stream driver passes the owning
     * session's {@code onCursorAck}; passing null resets to the no-op (V1 path).
     */
    void setCursorAckSink(LongConsumer sink) {
        this.cursorAckSink = (sink == null) ? NONE_ACK : sink;
    }

    /**
     * OPT-IN (C3): forwards drained connection directives to {@code sink} instead of
     * discarding them. Never set on the gate path (the default null keeps the historical
     * drain-and-ignore, so existing seeds are byte-identical).
     */
    void setDirectiveSink(Consumer<EdgeClientCore.ConnectionDirective> sink) {
        this.directiveSink = sink;
    }

    /** C3 recovery: the sim driver re-subscribed this edge — complete the reconnect cycle. */
    void onResubscribed() {
        core.onReconnected();
    }

    /**
     * TEST-ONLY: forces a wholesale store load that BYPASSES the backward-snapshot guard in
     * the core, modelling a hypothetical regression bug. Used solely by the test-the-tester
     * ({@code EdgeInvariantsTestTheTesterTest}) to drive the version-monotonicity /
     * no-stale-overwrite checkers into firing, since the production core now correctly refuses
     * a backward snapshot. The production code never calls this.
     */
    void forceLoadSnapshotUnsafeForTest(ConfigSnapshot snapshot, long seq) {
        core.loadSnapshotForced(snapshot, seq);
    }

    // -----------------------------------------------------------------------
    // Inbox — fed by the edge network (deterministic FIFO)
    // -----------------------------------------------------------------------

    /** Enqueues a message delivered by the edge network. Queues even while lagging. */
    void deliver(EdgeStream message) {
        inbox.addLast(Objects.requireNonNull(message, "message must not be null"));
    }

    int inboxSize() {
        return inbox.size();
    }

    // -----------------------------------------------------------------------
    // Tick — drain the inbox into the core (mapping EdgeStream -> EdgeFrame)
    // -----------------------------------------------------------------------

    /**
     * Drains the inbox in FIFO order, mapping each {@link EdgeStream} message onto the core's
     * {@link EdgeFrame} {@code onFrame} surface, then ticks the core. A lagging or crashed edge
     * does not process its inbox (messages keep queueing).
     */
    void tick() {
        if (!alive || lagging) {
            return;
        }
        long cursorBefore;
        while (!inbox.isEmpty()) {
            EdgeStream message = inbox.pollFirst();
            cursorBefore = core.cursor();
            switch (message) {
                case EdgeStream.Notify notify -> {
                    deliveredCount++;
                    core.onFrame(new EdgeFrame.Notify(List.of(notify.notification())));
                    observeIfApplied(notify.notification(), cursorBefore);
                }
                case EdgeStream.NotifyBatch batch -> {
                    deliveredCount += batch.notifications().size();
                    core.onFrame(new EdgeFrame.Notify(batch.notifications()));
                    observeBatchIfApplied(batch.notifications(), cursorBefore);
                }
                case EdgeStream.Snapshot snap -> {
                    deliveredCount++;
                    feedSnapshot(snap.snapshot(), snap.seq());
                }
                case EdgeStream.Heartbeat hb ->
                        core.onFrame(new EdgeFrame.Heartbeat(hb.latestSeq(), hb.serverNowMillis()));
            }
        }
        // Run the real periodic tick (re-ack on advance; heartbeat-silence reconnect policy;
        // the C3 DISCONNECTED-entry re-bootstrap detector).
        core.tick(timeSource.getAsLong());
        // Directives: with no sink wired (the gate path), drain-and-ignore as ever (the sim
        // heals via the server's ack-lag → snapshot path). With the OPT-IN C3 recovery sink
        // wired, forward them — the sink performs a REAL resubscribe through the driver.
        EdgeClientCore.ConnectionDirective directive;
        while ((directive = core.pollDirective()) != null) {
            if (directiveSink != null) {
                directiveSink.accept(directive);
            }
        }
    }

    /**
     * Maps a wholesale {@link EdgeStream.Snapshot} onto the core's frame surface by
     * serializing + chunking it into the {@code SNAPSHOT_BEGIN / SNAPSHOT_CHUNK* /
     * SNAPSHOT_END} flow — exercising the REAL core reassembly + cutover path (incl. the
     * C1(a) backward-snapshot refusal). The driver-side {@link C1StreamDriver} reassembled
     * the chunks into one message for the edge network; here the actor re-chunks so the core
     * runs its production reassembly exactly as the C2 process will.
     */
    private void feedSnapshot(ConfigSnapshot snapshot, long seq) {
        byte[] body = EdgeSnapshotCodec.serialize(snapshot);
        List<EdgeFrame.SnapshotChunk> chunks =
                EdgeSnapshotCodec.chunk(body, EdgeSnapshotCodec.MAX_ENTRY_FIELD_BYTES);
        core.onFrame(new EdgeFrame.SnapshotBegin(seq, chunks.size(), body.length));
        for (EdgeFrame.SnapshotChunk c : chunks) {
            core.onFrame(c);
        }
        core.onFrame(new EdgeFrame.SnapshotEnd(seq));
    }

    /** Fires the Phase V2 observer if the single notification advanced the cursor (APPLIED). */
    private void observeIfApplied(CommitNotification n, long cursorBefore) {
        if (core.cursor() > cursorBefore && core.cursor() >= n.seq()) {
            applyObserver.onApplied(edgeId, n.seq(), n.commitTimestampMillis(), timeSource.getAsLong());
        }
    }

    /** Fires the observer for each notification the batch advanced the cursor past. */
    private void observeBatchIfApplied(List<CommitNotification> ns, long cursorBefore) {
        long after = core.cursor();
        for (CommitNotification n : ns) {
            if (n.seq() > cursorBefore && n.seq() <= after) {
                applyObserver.onApplied(edgeId, n.seq(), n.commitTimestampMillis(),
                        timeSource.getAsLong());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Reads — real LocalConfigStore cursor path through the core (INV-M1)
    // -----------------------------------------------------------------------

    /**
     * Serves a cursor-bound read through the core's read store (real INV-M1
     * {@code monotonic_read} seam); in test mode a monotonicity violation throws and fails
     * the seed.
     */
    ReadResult get(String key, VersionCursor cursor) {
        return core.get(key, cursor);
    }

    /** Convenience cursorless read (no INV-M1 gate). */
    ReadResult get(String key) {
        return core.get(key);
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /** Drops ALL in-memory state (an edge is a cache) and bumps the incarnation. */
    void crash() {
        alive = false;
        incarnation++;
        inbox.clear();
        // Null out the core so a use-after-crash is a loud NPE, not a quiet read of stale
        // state; restart() rebuilds it.
        core = null;
    }

    /** Restarts with a fresh empty store at cursor 0, awaiting bootstrap. */
    void restart() {
        alive = true;
        lagging = false;
        inbox.clear();
        freshState();
    }

    /**
     * Attaches the OBSERVER-ONLY Phase V2 apply seam. Passing {@link EdgeApplyObserver#NONE}
     * (the default) is a no-op. Never affects behavior or the determinism digest.
     */
    void setApplyObserver(EdgeApplyObserver observer) {
        this.applyObserver = (observer == null) ? EdgeApplyObserver.NONE : observer;
    }

    /** Stops inbox processing; the inbox keeps queueing (models a lagging consumer). */
    void lag() {
        lagging = true;
    }

    /** Resumes inbox processing. */
    void unlag() {
        lagging = false;
    }

    // -----------------------------------------------------------------------
    // Accessors (read-only; used by EdgeInvariants / EdgeActivity / digests)
    // -----------------------------------------------------------------------

    int edgeId() { return edgeId; }

    int subscribedCpNode() { return subscribedCpNode; }

    boolean alive() { return alive; }

    boolean lagging() { return lagging; }

    int incarnation() { return incarnation; }

    long cursor() { return alive ? core.cursor() : 0L; }

    long currentVersion() {
        return alive ? core.currentVersion() : -1L;
    }

    /** The current immutable read-store snapshot, or {@code null} when crashed. */
    ConfigSnapshot snapshot() {
        return alive ? core.snapshot() : null;
    }

    StalenessTracker.State staleness() {
        return alive ? core.stalenessState() : StalenessTracker.State.DISCONNECTED;
    }

    int gapsDetected() { return alive ? core.gapsDetected() : 0; }

    /** C3 / CT-06: DISCONNECTED-entry re-bootstrap directives the core queued. */
    int disconnectedRebootstraps() { return alive ? core.disconnectedRebootstraps() : 0; }

    int snapshotsApplied() { return alive ? core.snapshotsApplied() : 0; }

    long deliveredCount() { return deliveredCount; }

    /** Heartbeats observed in this incarnation (C1 carrier; the core wires the frontier). */
    int heartbeatsObserved() { return alive ? core.heartbeatsObserved() : 0; }
}
