package io.configd.testkit;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.edge.DeltaApplier;
import io.configd.edge.EdgeConfigClient;
import io.configd.edge.LocalConfigStore;
import io.configd.edge.StalenessTracker;
import io.configd.edge.VersionCursor;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigSnapshot;
import io.configd.store.ReadResult;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * A deterministic simulation actor modelling one eventually-consistent edge cache
 * node (Phase V1). It wraps the <b>real production edge classes</b> — no forked or
 * re-implemented logic:
 * <ul>
 *   <li>{@link EdgeConfigClient} (composing {@link LocalConfigStore} +
 *       {@link StalenessTracker}) — the authoritative apply target;</li>
 *   <li>{@link DeltaApplier} (no signature verifier in V1 — signature rows are C2)
 *       over that client, so {@link DeltaApplier.ApplyResult} gap/stale semantics
 *       are exercised exactly as in production;</li>
 *   <li>a second, monitor-wired read {@link LocalConfigStore} that serves
 *       cursor-bound reads through the real INV-M1 ({@code monotonic_read}) seam.
 *       It is kept byte-identical to the client's internal store by feeding it the
 *       SAME delta (both are the same production class started from the same empty
 *       snapshot under the same logical clock → deterministic lockstep). This is
 *       NOT a fork: it is two instances of the production class fed identical
 *       input, required only because {@link EdgeConfigClient} constructs its
 *       internal store with no monitor and exposes no seam to inject one.</li>
 * </ul>
 *
 * <p><b>Clock.</b> Both the client and the read store are driven by a {@link Clock}
 * backed by the sim's logical time ({@code timeSource}); there is no wall-clock or
 * {@code System.nanoTime} on any path (charter rule). {@code nanoTime} is
 * {@code currentTimeMillis * 1_000_000} so {@link StalenessTracker} measures
 * staleness in logical milliseconds.
 *
 * <p><b>Lifecycle (an edge is a cache).</b>
 * <ul>
 *   <li>{@link #tick()} — drain the inbox in FIFO order, applying each
 *       {@link EdgeStream} message via the real {@link DeltaApplier#offer}; every
 *       {@link DeltaApplier.ApplyResult} other than {@code APPLIED} is recorded
 *       (gaps, stale deltas) — never silently ignored.</li>
 *   <li>{@link #crash()} — drop ALL in-memory state (store + cursor; a cache has
 *       no durable state) and bump the incarnation counter.</li>
 *   <li>{@link #restart()} — fresh empty store at cursor 0, awaiting bootstrap.</li>
 *   <li>{@link #lag()}/{@link #unlag()} — stop/resume inbox processing; the inbox
 *       keeps queueing while lagging.</li>
 * </ul>
 *
 * <p><b>Contract clauses.</b> Reads serve {@code get(key, cursor)} through the
 * real {@link LocalConfigStore} cursor path (contract §3 INV-M1 monotonic reads).
 * The cursor is the applied-mutation seq S (contract §4 version semantics). Gap
 * detection follows contract §4 (received seq must equal {@code cursor + 1} over
 * the mutation stream).
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
     * The INV-M1 monitor wired into the read store. Test-mode ({@code testMode =
     * true}) so a {@code monotonic_read} violation throws an {@link AssertionError}
     * that fails the seed — the brief's requirement for invariant 4a. Shared one
     * registry per actor (counts are diagnostic only).
     */
    private final InvariantMonitor monitor;

    // Authoritative apply path (real production composition).
    private EdgeConfigClient client;
    private DeltaApplier applier;

    /**
     * Monitor-wired read store, kept in lockstep with {@code client}'s internal
     * store. All cursor-bound reads route here so INV-M1 is enforced through the
     * real {@link LocalConfigStore#get(String, VersionCursor)} seam.
     */
    private LocalConfigStore readStore;

    /** Deterministic FIFO inbox; messages are delivered by the edge network. */
    private final Deque<EdgeStream> inbox = new ArrayDeque<>();

    /** Last applied applied-mutation seq S (the cursor; 0 = nothing applied yet). */
    private long cursor;

    /** Incarnation counter — bumped on every crash (models a cache reincarnation). */
    private int incarnation;

    private boolean alive = true;
    private boolean lagging;

    /**
     * OBSERVER-ONLY apply seam (Phase V2). Fired on every {@code APPLIED} notification
     * so the {@link io.configd.probe.PropagationProbe} can sample logical visibility
     * time. Defaults to {@link EdgeApplyObserver#NONE} (no-op) so V1 behavior and the
     * determinism digest are unchanged. Survives crash/restart — it is a harness
     * binding, not edge state, so {@link #freshState()} does not touch it.
     */
    private EdgeApplyObserver applyObserver = EdgeApplyObserver.NONE;

    // Per-tick / lifetime counters surfaced to EdgeActivity and the invariants.
    private int gapsDetected;
    private int snapshotsApplied;
    private long deliveredCount;

    /**
     * The server-side commit-timestamp / latest-seq from the most recent HEARTBEAT, and a
     * count of heartbeats observed. C1 stores these as a carrier only — the idle-staleness
     * frontier wiring is C2 (ADR-0039). {@code lastHeartbeatServerNowMillis} is -1 until the
     * first heartbeat. Survives across the tick loop; reset on crash/restart (fresh actor).
     */
    private long lastHeartbeatServerNowMillis = -1L;
    private long lastHeartbeatLatestSeq = -1L;
    private int heartbeatsObserved;

    /**
     * Edge→server CURSOR_ACK sink: invoked with the highest applied seq after the edge
     * applies a {@link EdgeStream.NotifyBatch} or a {@link EdgeStream.Snapshot}. The C1
     * stream driver wires this to the owning {@code FanOutSessionCore.onCursorAck} so the
     * server's bounded-queue accounting is released. {@code NONE} by default (the
     * V1 DirectInjectionDriver path does not ack).
     */
    private LongConsumer cursorAckSink = NONE_ACK;

    private static final LongConsumer NONE_ACK = seq -> { };

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
        this.monitor = new InvariantMonitor(new MetricsRegistry(), true);
        freshState();
    }

    /** Builds a fresh empty client + applier + monitor-wired read store at cursor 0. */
    private void freshState() {
        this.client = new EdgeConfigClient(clock);
        // V1: no verifier (signature rows are C2). Real gap/stale ApplyResult logic.
        this.applier = new DeltaApplier(client);
        this.readStore = new LocalConfigStore(ConfigSnapshot.EMPTY, clock, monitor);
        this.cursor = 0L;
        // Heartbeat carrier state is per-incarnation (a fresh cache knows no server frontier).
        this.lastHeartbeatServerNowMillis = -1L;
        this.lastHeartbeatLatestSeq = -1L;
        this.heartbeatsObserved = 0;
    }

    /**
     * Wires the edge→server CURSOR_ACK sink (C1). The stream driver passes the owning
     * session's {@code onCursorAck}; passing null resets to the no-op (V1 path).
     */
    void setCursorAckSink(LongConsumer sink) {
        this.cursorAckSink = (sink == null) ? NONE_ACK : sink;
    }

    /**
     * TEST-ONLY: forces a wholesale store load that BYPASSES the backward-snapshot guard in
     * {@link #applySnapshot}, modelling a hypothetical regression bug. Used solely by the
     * test-the-tester ({@code EdgeInvariantsTestTheTesterTest}) to drive the version-
     * monotonicity / no-stale-overwrite checkers into firing, since the production
     * {@link #applySnapshot} now correctly refuses a backward snapshot and so can no longer
     * be used to manufacture a regression. The production code never calls this.
     */
    void forceLoadSnapshotUnsafeForTest(ConfigSnapshot snapshot, long seq) {
        client.loadSnapshot(snapshot);
        readStore.loadSnapshot(snapshot);
        applier.resetGap();
        cursor = seq;
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
    // Tick — drain + apply
    // -----------------------------------------------------------------------

    /**
     * Drains the inbox in FIFO order and applies each message. A lagging or crashed
     * edge does not process its inbox (messages keep queueing). Every
     * {@link DeltaApplier.ApplyResult} that is not {@code APPLIED} is recorded so a
     * gap/stale is never silently lost.
     */
    void tick() {
        if (!alive || lagging) {
            return;
        }
        while (!inbox.isEmpty()) {
            EdgeStream message = inbox.pollFirst();
            switch (message) {
                case EdgeStream.Notify notify -> applyNotify(notify.notification());
                case EdgeStream.NotifyBatch batch -> applyNotifyBatch(batch);
                case EdgeStream.Snapshot snap -> applySnapshot(snap.snapshot(), snap.seq());
                case EdgeStream.Heartbeat hb -> applyHeartbeat(hb);
            }
        }
    }

    /**
     * Applies a frame-level NOTIFY batch (ADR-0038): each verbatim notification in seq
     * order through the real {@link DeltaApplier}, then sends a single CURSOR_ACK for the
     * highest applied seq (one ack per batch — the design's "send CURSOR_ACK after applying
     * (per notify batch)"). A gap/stale mid-batch is recorded exactly as for a single
     * notify; the ack reflects the cursor actually reached.
     */
    private void applyNotifyBatch(EdgeStream.NotifyBatch batch) {
        for (CommitNotification n : batch.notifications()) {
            applyNotify(n);
        }
        // Ack the highest seq the edge has now applied (its cursor). If nothing applied
        // (all gap/stale), cursor is unchanged and the ack is the current cursor — a
        // benign no-op for the server's watermark (it ignores stale acks).
        cursorAckSink.accept(cursor);
    }

    /**
     * Records a HEARTBEAT (C1 design §3; carrier only). Stores the server's latest-seq and
     * clock and counts it; the idle-staleness frontier measure is C2 (ADR-0039), so no
     * staleness state is computed here.
     */
    private void applyHeartbeat(EdgeStream.Heartbeat hb) {
        lastHeartbeatServerNowMillis = hb.serverNowMillis();
        lastHeartbeatLatestSeq = hb.latestSeq();
        heartbeatsObserved++;
    }

    private void applyNotify(CommitNotification notification) {
        deliveredCount++;
        // Authoritative apply through the real DeltaApplier (gap/stale semantics).
        DeltaApplier.ApplyResult result = applier.offer(notification.delta());
        switch (result) {
            case APPLIED -> {
                // Mirror the same delta into the monitor-wired read store so reads
                // route through the real INV-M1 seam. Same production class + same
                // delta + same clock ⇒ byte-identical to the client's store.
                readStore.applyDelta(notification.delta());
                cursor = notification.seq();
                // OBSERVER-ONLY (Phase V2): sample logical visibility time. Reads only
                // already-computed values; NONE by default → no behavior/digest change.
                applyObserver.onApplied(edgeId, notification.seq(),
                        notification.commitTimestampMillis(), timeSource.getAsLong());
            }
            case GAP_DETECTED -> gapsDetected++;
            case STALE_DELTA -> {
                // Recorded, not applied: a re-delivered/older notification. The
                // cursor and stores are unchanged — never a stale overwrite.
            }
            // V1 has no verifier, so the signature/replay results cannot occur on
            // this path; record defensively rather than silently dropping (a
            // future C1/C2 wiring change that produced one must be visible).
            case UNSIGNED_REJECTED, SIGNATURE_INVALID, REPLAY_REJECTED ->
                    gapsDetected++; // counted as a non-apply; EdgeInvariants reads gapsDetected
        }
    }

    private void applySnapshot(ConfigSnapshot snapshot, long seq) {
        deliveredCount++;
        // A snapshot that would move the store BACKWARD is stale and must be rejected — the
        // edge never regresses (the per-edge version-monotonicity invariant / contract §3
        // INV-M1). This is the wholesale-load analogue of DeltaApplier's STALE_DELTA guard:
        // it can arise when the subscribed CP node the replay source reads is transiently
        // behind the edge (e.g. the edge applied committed deltas streamed earlier from a
        // node that was ahead, and the demotion snapshot is now taken from a node still
        // catching up). Re-ack the edge's CURRENT (higher) cursor so the server's ack-lag
        // clears against the real applied position and it stops re-sending the stale snapshot.
        if (seq < cursor) {
            cursorAckSink.accept(cursor);
            return;
        }
        snapshotsApplied++;
        // GAP recovery (ADR-0034 §"Handoff" step 2): apply wholesale, reset cursor.
        client.loadSnapshot(snapshot);
        readStore.loadSnapshot(snapshot);
        applier.resetGap();
        cursor = seq;
        // Ack the snapshot point so the server resumes tailing from a clean cursor.
        cursorAckSink.accept(cursor);
    }

    // -----------------------------------------------------------------------
    // Reads — real LocalConfigStore cursor path (contract §3 INV-M1)
    // -----------------------------------------------------------------------

    /**
     * Serves a cursor-bound read through the real {@link LocalConfigStore} cursor
     * path. A cursor ahead of the local version routes through the wired
     * {@link InvariantMonitor} (INV-M1 {@code monotonic_read}); in test mode an
     * actual monotonicity violation throws and fails the seed.
     */
    ReadResult get(String key, VersionCursor cursor) {
        return readStore.get(key, cursor);
    }

    /** Convenience cursorless read (no INV-M1 gate). */
    ReadResult get(String key) {
        return readStore.get(key);
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /** Drops ALL in-memory state (an edge is a cache) and bumps the incarnation. */
    void crash() {
        alive = false;
        incarnation++;
        inbox.clear();
        // Null out the heavy state so a use-after-crash is a loud NPE, not a quiet
        // read of stale state; restart() rebuilds it.
        client = null;
        applier = null;
        readStore = null;
        cursor = 0L;
    }

    /** Restarts with a fresh empty store at cursor 0, awaiting bootstrap. */
    void restart() {
        alive = true;
        lagging = false;
        inbox.clear();
        freshState();
    }

    /**
     * Attaches the OBSERVER-ONLY Phase V2 apply seam. Passing
     * {@link EdgeApplyObserver#NONE} (the default) is a no-op. Never affects behavior
     * or the determinism digest — see {@link EdgeApplyObserver}.
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

    long cursor() { return cursor; }

    long currentVersion() {
        return alive ? readStore.currentVersion() : -1L;
    }

    /** The current immutable read-store snapshot, or {@code null} when crashed. */
    ConfigSnapshot snapshot() {
        return alive ? readStore.snapshot() : null;
    }

    StalenessTracker.State staleness() {
        return alive ? client.staleness() : StalenessTracker.State.DISCONNECTED;
    }

    int gapsDetected() { return gapsDetected; }

    int snapshotsApplied() { return snapshotsApplied; }

    long deliveredCount() { return deliveredCount; }

    /** Heartbeats observed in this incarnation (C1 carrier; C2 wires staleness). */
    int heartbeatsObserved() { return heartbeatsObserved; }

    /** The {@code serverNowMillis} of the last HEARTBEAT, or -1 if none (C1 carrier). */
    long lastHeartbeatServerNowMillis() { return lastHeartbeatServerNowMillis; }

    /** The {@code latestSeq} of the last HEARTBEAT, or -1 if none (C1 carrier). */
    long lastHeartbeatLatestSeq() { return lastHeartbeatLatestSeq; }
}
