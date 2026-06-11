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
                case EdgeStream.Snapshot snap -> applySnapshot(snap.snapshot(), snap.seq());
            }
        }
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
        snapshotsApplied++;
        // GAP recovery (ADR-0034 §"Handoff" step 2): apply wholesale, reset cursor.
        client.loadSnapshot(snapshot);
        readStore.loadSnapshot(snapshot);
        applier.resetGap();
        cursor = seq;
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
}
