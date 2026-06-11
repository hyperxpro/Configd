package io.configd.edge;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeSnapshotCodec;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigSnapshot;
import io.configd.store.ReadResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * The transport-agnostic edge client session engine (C2 design §2; the client-side analogue
 * of {@code FanOutSessionCore}). It owns ALL protocol handling for one fan-out connection:
 * frame decode-effects, the signed-chain apply, snapshot reassembly + cutover, the ADR-0039
 * frontier staleness measure, periodic cursor acknowledgement, and reconnect directives on
 * heartbeat silence. It is:
 * <ul>
 *   <li><b>transport-free</b> — no socket / TLS / {@code java.net} type appears here; the
 *       only boundary is the {@link FrameSink} (outbound) and the {@link ConnectionDirective}
 *       queue (the shell/sim obeys both). The simulator drives this REAL code so the gate
 *       seeds exercise production C2 logic.</li>
 *   <li><b>clock-injected + deterministic</b> — every time read is via the injected
 *       {@link Clock}; no wall clock, no {@code System.nanoTime}.</li>
 *   <li><b>single-threaded</b> — {@link #onFrame} and {@link #tick} must be called by one
 *       thread (or virtual thread); not internally synchronized (the C1 single-writer
 *       discipline, mirrored client-side).</li>
 * </ul>
 *
 * <h2>Composition</h2>
 * <ul>
 *   <li>{@link EdgeConfigClient} — the authoritative apply target (its internal store +
 *       the ADR-0039 {@link StalenessTracker} + the ADR-0038 prefix storage filter);</li>
 *   <li>{@link DeltaApplier} over that client — the real gap/stale/signature
 *       {@link DeltaApplier.ApplyResult} semantics;</li>
 *   <li>a monitor-wired read {@link LocalConfigStore} kept byte-identical to the client's
 *       internal store by feeding it the SAME (ADR-0038-filtered) delta — so all
 *       cursor-bound reads route through the real INV-M1 ({@code monotonic_read}) seam.
 *       This preserves the V1 read seam: {@link EdgeConfigClient} builds its internal store
 *       with no monitor and exposes no injection seam, so a second instance of the same
 *       production class, fed identical input, is the established way to wire INV-M1
 *       (NOT a fork — deterministic lockstep from the same empty start + same clock).</li>
 * </ul>
 *
 * <h2>Hot-path law (charter §6 rule 3 / CT-34)</h2>
 * NOTHING in the read path here allocates or branches on session state: {@link #get(String)}
 * / {@link #get(String, VersionCursor)} go straight to the lock-free {@link LocalConfigStore}
 * exactly as in V1. The apply/snapshot/heartbeat machinery is the single-writer path, off
 * the read hot path.
 *
 * <h2>Frame handling ({@link #onFrame})</h2>
 * <ul>
 *   <li><b>SUBSCRIBE_OK</b> — records the server's chosen {@link EdgeFrame.Mode} and latest
 *       seq; informational (a SNAPSHOT_FIRST mode means a snapshot flow follows).</li>
 *   <li><b>NOTIFY</b> — each verbatim notification in seq order through the real
 *       {@link DeltaApplier} (verify → ADR-0038 storage filter → apply); a single
 *       {@code CURSOR_ACK} for the highest applied seq is emitted per batch.</li>
 *   <li><b>SNAPSHOT_BEGIN / SNAPSHOT_CHUNK / SNAPSHOT_END</b> — reassemble via
 *       {@link EdgeSnapshotCodec}; REFUSE a backward snapshot ({@code seq < cursor},
 *       re-acking the real position — the C1(a) monotonicity fix); else atomic
 *       {@code loadSnapshot} + {@code resetGap}, {@code cursor = snapshotSeq}, ack.</li>
 *   <li><b>HEARTBEAT</b> — ADR-0039 frontier: advance the staleness frontier only when
 *       {@code latestSeq == cursor}; always record cursor-lag = {@code latestSeq − cursor}.</li>
 *   <li><b>ERROR_CLOSE</b> — recorded; {@code DEMOTED_TO_CATCHUP} is informational (the
 *       snapshot follows); a fatal close queues a reconnect directive at the current cursor.</li>
 * </ul>
 *
 * <h2>{@link #tick(long)}</h2>
 * Periodic {@code CURSOR_ACK} if the cursor advanced since the last ack; staleness state is
 * available via {@link #stalenessState()}; on heartbeat silence longer than
 * {@code silenceFactor × heartbeatMs} a {@link ConnectionDirective.ReconnectNextEndpoint}
 * is queued carrying the resume cursor (the shell/sim reconnects to the next endpoint).
 */
public final class EdgeClientCore {

    /**
     * Outbound frame seam (edge→server). The shell/sim encodes these to the wire (or maps
     * them onto sim messages). {@code offer} returns {@code false} if the transport would
     * block; the core treats a refused {@code CURSOR_ACK} as "retry next tick" (acks are
     * idempotent — the highest cursor is re-sent), never as data loss.
     */
    @FunctionalInterface
    public interface FrameSink {
        /** Offers a frame for transmission; {@code false} = would-block (retry later). */
        boolean offer(EdgeFrame frame);

        /** A sink that drops everything (tests that ignore the outbound channel). */
        FrameSink NONE = frame -> true;
    }

    /**
     * A directive the core asks the shell/sim to act on (the shell owns sockets/reconnect;
     * the core owns the policy). Sealed and tiny.
     */
    public sealed interface ConnectionDirective {
        /**
         * Reconnect to the next configured fan-out endpoint and re-SUBSCRIBE carrying
         * {@code resumeCursor} as the failover resume cursor (contract §3 failover; the
         * edge keeps refusing cursor-behind reads during catch-up — consistent refusal).
         *
         * @param resumeCursor the applied-mutation seq to resume from (the current cursor)
         * @param reason       why the reconnect was triggered (diagnostic)
         */
        record ReconnectNextEndpoint(long resumeCursor, String reason)
                implements ConnectionDirective {
            public ReconnectNextEndpoint {
                if (resumeCursor < 0) {
                    throw new IllegalArgumentException("resumeCursor must be >= 0: " + resumeCursor);
                }
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }
    }

    // --- configuration ---------------------------------------------------------------

    /** Default heartbeat cadence assumed by the silence detector (C1 {@code heartbeatMs}). */
    public static final long DEFAULT_HEARTBEAT_MS = 250L;
    /** Default silence factor: reconnect after this many missed heartbeat intervals. */
    public static final int DEFAULT_SILENCE_FACTOR = 8;

    private final long heartbeatMs;
    private final int silenceFactor;

    // --- collaborators ---------------------------------------------------------------

    private final Clock clock;
    private final EdgeConfigClient client;
    private final DeltaApplier applier;
    private final LocalConfigStore readStore;
    private final FrameSink sink;

    // --- session state (single-writer) -----------------------------------------------

    /** Last applied applied-mutation seq S (the cursor; 0 = nothing applied yet). */
    private long cursor;

    /** The highest cursor we have acked to the server (so tick acks only on advance). */
    private long lastAckedSeq;

    /** The last heartbeat's {@code latestSeq} (server's highest seq), -1 until first heartbeat. */
    private long lastHeartbeatLatestSeq = -1L;

    /** Cursor lag from the most recent heartbeat ({@code latestSeq − cursor}, clamped ≥0). */
    private long cursorLag;

    /** Wall time (injected clock) of the last heartbeat received, -1 until first. */
    private long lastHeartbeatAtMillis = -1L;

    /** Server-chosen subscription mode from SUBSCRIBE_OK; null until subscribed. */
    private EdgeFrame.Mode mode;

    /** Snapshot reassembly state (between SNAPSHOT_BEGIN and SNAPSHOT_END). */
    private final List<EdgeFrame.SnapshotChunk> pendingChunks = new ArrayList<>();
    private long pendingSnapshotSeq = -1L;
    private boolean inSnapshot;

    /** Pending connection directives for the shell/sim to drain. */
    private final Deque<ConnectionDirective> directives = new ArrayDeque<>();

    /** True once a fatal ERROR_CLOSE / reconnect was queued, so we do not spam directives. */
    private boolean reconnectPending;

    // --- diagnostic counters (read by tests / sim digest folding) --------------------

    private long appliedCount;
    private int gapsDetected;
    private int snapshotsApplied;
    private int backwardSnapshotsRefused;
    private int heartbeatsObserved;
    private int frontierAdvances;

    /**
     * Full constructor.
     *
     * @param clock              the injected clock (non-null)
     * @param invariantMonitor   the INV-M1 ({@code monotonic_read}) + INV-S1 monitor wired
     *                           into the read store and staleness tracker (may be null in
     *                           tests that do not assert the seam)
     * @param implausibleCounter the ADR-0039 implausible-frontier counter (may be null)
     * @param strongReadKeyClass the ADR-0038 strong-read key class (always-store; non-null)
     * @param sink               the outbound frame sink (non-null; use {@link FrameSink#NONE})
     * @param heartbeatMs        the assumed heartbeat cadence for silence detection (&gt;0)
     * @param silenceFactor      reconnect after {@code silenceFactor × heartbeatMs} silence (&gt;0)
     */
    public EdgeClientCore(Clock clock, InvariantMonitor invariantMonitor,
                          MetricsRegistry.Counter implausibleCounter,
                          StrongReadKeyClass strongReadKeyClass, FrameSink sink,
                          long heartbeatMs, int silenceFactor) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(strongReadKeyClass, "strongReadKeyClass must not be null");
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
        if (heartbeatMs <= 0) {
            throw new IllegalArgumentException("heartbeatMs must be > 0: " + heartbeatMs);
        }
        if (silenceFactor <= 0) {
            throw new IllegalArgumentException("silenceFactor must be > 0: " + silenceFactor);
        }
        this.heartbeatMs = heartbeatMs;
        this.silenceFactor = silenceFactor;
        this.client = new EdgeConfigClient(clock, invariantMonitor, implausibleCounter,
                strongReadKeyClass);
        // V1/C2 sim path: no signature verifier (signature rows are exercised by the C2
        // integration test with a real key, not this core's sim). Real gap/stale logic.
        this.applier = new DeltaApplier(client);
        this.readStore = new LocalConfigStore(ConfigSnapshot.EMPTY, clock, invariantMonitor);
        this.cursor = 0L;
        this.lastAckedSeq = 0L;
    }

    /**
     * Convenience constructor with default heartbeat cadence + silence factor and the
     * default strong-read key class.
     */
    public EdgeClientCore(Clock clock, InvariantMonitor invariantMonitor,
                          MetricsRegistry.Counter implausibleCounter, FrameSink sink) {
        this(clock, invariantMonitor, implausibleCounter, StrongReadKeyClass.DEFAULT, sink,
                DEFAULT_HEARTBEAT_MS, DEFAULT_SILENCE_FACTOR);
    }

    // -----------------------------------------------------------------------
    // Subscriptions (storage filter, ADR-0038)
    // -----------------------------------------------------------------------

    /** Adds a storage-filter prefix subscription (ADR-0038). Empty set = full store. */
    public void addSubscription(String prefix) {
        client.addSubscription(prefix);
    }

    // -----------------------------------------------------------------------
    // Inbound frame handling (server→edge)
    // -----------------------------------------------------------------------

    /**
     * Handles one inbound {@link EdgeFrame} (server→edge). The single entry point for all
     * protocol effects; the shell/sim decodes the wire and calls this. Edge→server frames
     * ({@code SUBSCRIBE}, {@code CURSOR_ACK}) are never passed here.
     *
     * @param frame the inbound frame (non-null)
     */
    public void onFrame(EdgeFrame frame) {
        Objects.requireNonNull(frame, "frame must not be null");
        switch (frame) {
            case EdgeFrame.SubscribeOk ok -> onSubscribeOk(ok);
            case EdgeFrame.Notify n -> onNotify(n);
            case EdgeFrame.SnapshotBegin b -> onSnapshotBegin(b);
            case EdgeFrame.SnapshotChunk c -> onSnapshotChunk(c);
            case EdgeFrame.SnapshotEnd e -> onSnapshotEnd(e);
            case EdgeFrame.Heartbeat h -> onHeartbeat(h);
            case EdgeFrame.ErrorClose err -> onErrorClose(err);
            // Edge→server frames: never delivered inbound. Reject loudly (a mis-wired shell
            // delivering our own outbound frame back is a bug, not silently ignorable).
            case EdgeFrame.Subscribe ignored ->
                    throw new IllegalArgumentException("SUBSCRIBE is edge→server, not inbound");
            case EdgeFrame.CursorAck ignored ->
                    throw new IllegalArgumentException("CURSOR_ACK is edge→server, not inbound");
        }
    }

    private void onSubscribeOk(EdgeFrame.SubscribeOk ok) {
        this.mode = ok.mode();
        // latestSeq from the handshake seeds the cursor-lag view until the first heartbeat.
        this.lastHeartbeatLatestSeq = ok.latestSeq();
        this.cursorLag = Math.max(0L, ok.latestSeq() - cursor);
    }

    private void onNotify(EdgeFrame.Notify n) {
        for (CommitNotification notification : n.notifications()) {
            applyNotification(notification);
        }
        // One CURSOR_ACK per batch for the highest applied seq (the design's per-batch ack).
        // If nothing applied (all gap/stale) the cursor is unchanged and the ack is benign.
        ackCursor();
    }

    /**
     * Applies one verbatim notification through the real {@link DeltaApplier} (gap/stale
     * semantics), mirroring the ADR-0038-filtered delta into the monitor-wired read store.
     * <p>
     * The apply-exception surface is left clean for C3 (ADR-0040 poison-pill): a
     * {@link DeltaApplier#offer} that throws on an apply-time decode defect propagates here
     * (C3 wraps this site with bounded-retry + re-bootstrap). C2 does not catch it.
     */
    private void applyNotification(CommitNotification notification) {
        // The applier applies to the client's internal store (ADR-0038 filter + ADR-0039
        // frontier from the leader commit timestamp) on APPLIED — a single atomic apply.
        DeltaApplier.ApplyResult result =
                applier.offer(notification.delta(), notification.commitTimestampMillis());
        switch (result) {
            case APPLIED -> {
                // Mirror the SAME ADR-0038-filtered delta into the monitor-wired read store
                // so it stays byte-identical to the client's internal store and reads route
                // through the real INV-M1 seam. (filterForStorage is the lockstep contract;
                // a pure function of the current subscription, so both stores agree.)
                readStore.applyDelta(client.filterForStorage(notification.delta()));
                cursor = notification.seq();
                appliedCount++;
                refreshCursorLag();
            }
            case GAP_DETECTED -> gapsDetected++;
            case STALE_DELTA -> {
                // Re-delivered/older notification: recorded, not applied. Cursor unchanged
                // — never a stale overwrite (contract §3 INV-M1 / §4 monotonicity).
            }
            // No verifier on this core's apply path, so the signature/replay results cannot
            // occur here; count defensively (a future wiring change that produced one must
            // be visible, never silently dropped).
            case UNSIGNED_REJECTED, SIGNATURE_INVALID, REPLAY_REJECTED -> gapsDetected++;
        }
    }

    private void onSnapshotBegin(EdgeFrame.SnapshotBegin b) {
        inSnapshot = true;
        pendingChunks.clear();
        pendingSnapshotSeq = b.snapshotSeq();
    }

    private void onSnapshotChunk(EdgeFrame.SnapshotChunk c) {
        if (!inSnapshot) {
            // A chunk with no preceding BEGIN is a protocol error; refuse to reassemble a
            // partial snapshot (silent partial application is the divergence we forbid).
            throw new IllegalStateException("SNAPSHOT_CHUNK received outside a snapshot transfer");
        }
        pendingChunks.add(c);
    }

    private void onSnapshotEnd(EdgeFrame.SnapshotEnd e) {
        if (!inSnapshot) {
            throw new IllegalStateException("SNAPSHOT_END received outside a snapshot transfer");
        }
        long seq = e.snapshotSeq();
        // Reassemble + deserialize the ADR-0028 body (bounds-checked by the codec).
        byte[] body = EdgeSnapshotCodec.reassemble(pendingChunks);
        ConfigSnapshot snapshot = EdgeSnapshotCodec.deserialize(body);
        inSnapshot = false;
        pendingChunks.clear();
        pendingSnapshotSeq = -1L;

        // C1(a) fix: REFUSE a backward snapshot (seq < cursor) — the edge never regresses.
        // Re-ack the real (higher) cursor so the server's ack-lag clears and it stops
        // re-sending the stale snapshot.
        if (seq < cursor) {
            backwardSnapshotsRefused++;
            ackCursor();
            return;
        }

        // Atomic cutover: loadSnapshot wholesale + resetGap; cursor = snapshot seq.
        snapshotsApplied++;
        client.loadSnapshot(snapshot);
        readStore.loadSnapshot(snapshot);
        applier.resetGap();
        cursor = seq;
        refreshCursorLag();
        ackCursor();
    }

    private void onHeartbeat(EdgeFrame.Heartbeat h) {
        heartbeatsObserved++;
        lastHeartbeatLatestSeq = h.latestSeq();
        lastHeartbeatAtMillis = clock.currentTimeMillis();
        // ADR-0039: advance the frontier ONLY when latestSeq == cursor (cursor-matched).
        boolean advanced = client.recordHeartbeatFrontier(h.latestSeq(), cursor, h.serverNowMillis());
        if (advanced) {
            frontierAdvances++;
        }
        // Always record cursor lag (latestSeq − cursor): the cursor-lag signal, the catch-up
        // decision input (clamped ≥0 — a latestSeq < cursor would be a behind/skewed relay).
        refreshCursorLag();
    }

    private void onErrorClose(EdgeFrame.ErrorClose err) {
        switch (err.code()) {
            case DEMOTED_TO_CATCHUP -> {
                // Informational: a snapshot flow follows (the server demoted us to catch-up).
                // No reconnect — the session continues; the snapshot heals the cursor.
            }
            default -> queueReconnect("error-close:" + err.code());
        }
    }

    // -----------------------------------------------------------------------
    // Periodic tick (single-writer)
    // -----------------------------------------------------------------------

    /**
     * Periodic maintenance: re-ack the cursor if it advanced since the last ack, and emit a
     * reconnect directive if the server has gone silent (no heartbeat for
     * {@code silenceFactor × heartbeatMs}). Staleness state is computed lazily on read
     * ({@link #stalenessState()} / {@link #stalenessMs()}) against the injected clock, so
     * {@code tick} need not recompute it.
     *
     * @param nowMillis the current wall time (must equal {@code clock.currentTimeMillis()}
     *                  on the caller's clock; passed explicitly so the silence window is a
     *                  pure function of the argument and deterministic in the sim)
     */
    public void tick(long nowMillis) {
        // Re-ack on advance (idempotent; covers an earlier would-block ack).
        if (cursor > lastAckedSeq) {
            ackCursor();
        }

        // Heartbeat-silence reconnect: only once we have seen a heartbeat (a never-connected
        // session is the shell's connect concern, not a silence reconnect).
        if (!reconnectPending && lastHeartbeatAtMillis >= 0) {
            long silentFor = nowMillis - lastHeartbeatAtMillis;
            if (silentFor > silenceFactor * heartbeatMs) {
                queueReconnect("heartbeat-silence:" + silentFor + "ms");
            }
        }
    }

    private void ackCursor() {
        boolean sent = sink.offer(new EdgeFrame.CursorAck(cursor));
        if (sent) {
            lastAckedSeq = cursor;
        }
        // else: would-block; tick() retries on the next pass (the ack is idempotent).
    }

    private void refreshCursorLag() {
        cursorLag = Math.max(0L, lastHeartbeatLatestSeq - cursor);
    }

    private void queueReconnect(String reason) {
        directives.add(new ConnectionDirective.ReconnectNextEndpoint(cursor, reason));
        reconnectPending = true;
    }

    // -----------------------------------------------------------------------
    // Connection directives (the shell/sim drains these)
    // -----------------------------------------------------------------------

    /**
     * Removes and returns the next pending {@link ConnectionDirective}, or {@code null} if
     * none. The shell/sim drains this each loop and acts on it (reconnect to next endpoint).
     */
    public ConnectionDirective pollDirective() {
        return directives.pollFirst();
    }

    /** True if any directive is pending. */
    public boolean hasDirective() {
        return !directives.isEmpty();
    }

    /**
     * Clears the reconnect-pending latch after the shell has acted on the reconnect
     * directive and re-subscribed (so a subsequent silence can re-trigger).
     */
    public void onReconnected() {
        reconnectPending = false;
        lastHeartbeatAtMillis = -1L; // fresh connection: silence window restarts after first hb
    }

    /**
     * Loads a snapshot wholesale BYPASSING the backward-snapshot monotonicity guard in
     * {@link #onSnapshotEnd} and sets {@code cursor = seq}. Production never calls this — the
     * protocol path always routes through {@code onFrame}/{@code onSnapshotEnd} (which refuses
     * a backward snapshot). It exists so the simulator's invariant test-the-tester can
     * manufacture a deliberate store regression to prove the per-edge version-monotonicity
     * checker is non-vacuous (the real path can no longer regress, so a bug must be injected).
     *
     * @param snapshot the snapshot to load unconditionally (non-null)
     * @param seq      the cursor to set (may be below the current cursor — a forced regression)
     */
    public void loadSnapshotForced(ConfigSnapshot snapshot, long seq) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        client.loadSnapshot(snapshot);
        readStore.loadSnapshot(snapshot);
        applier.resetGap();
        cursor = seq;
        refreshCursorLag();
    }

    // -----------------------------------------------------------------------
    // Reads — real LocalConfigStore cursor path (contract §3 INV-M1). Hot path.
    // -----------------------------------------------------------------------

    /** Serves a cursor-bound read through the real INV-M1 seam (hot path; no session state). */
    public ReadResult get(String key, VersionCursor readCursor) {
        return readStore.get(key, readCursor);
    }

    /** Cursorless read (no INV-M1 gate). Hot path. */
    public ReadResult get(String key) {
        return readStore.get(key);
    }

    // -----------------------------------------------------------------------
    // Accessors (diagnostics / sim digest / tests)
    // -----------------------------------------------------------------------

    /** The applied-mutation seq the edge has reached (its cursor). */
    public long cursor() {
        return cursor;
    }

    /** The current store version (== cursor on the steady state). */
    public long currentVersion() {
        return readStore.currentVersion();
    }

    /** The current read-store snapshot (immutable; safe to hold). */
    public ConfigSnapshot snapshot() {
        return readStore.snapshot();
    }

    /** The ADR-0039 frontier staleness state. */
    public StalenessTracker.State stalenessState() {
        return client.staleness();
    }

    /** The ADR-0039 frontier staleness in millis ({@code wall_now − frontier}). */
    public long stalenessMs() {
        return client.stalenessMs();
    }

    /** The cursor lag from the most recent heartbeat ({@code latestSeq − cursor}, ≥0). */
    public long cursorLag() {
        return cursorLag;
    }

    /** The server-chosen subscription mode (from SUBSCRIBE_OK), or null if not subscribed. */
    public EdgeFrame.Mode mode() {
        return mode;
    }

    /** Number of notifications applied. */
    public long appliedCount() {
        return appliedCount;
    }

    /** Number of GAP results observed. */
    public int gapsDetected() {
        return gapsDetected;
    }

    /** Number of snapshots applied (cutover). */
    public int snapshotsApplied() {
        return snapshotsApplied;
    }

    /** Number of backward snapshots refused (C1(a) monotonicity guard). */
    public int backwardSnapshotsRefused() {
        return backwardSnapshotsRefused;
    }

    /** Number of heartbeats observed. */
    public int heartbeatsObserved() {
        return heartbeatsObserved;
    }

    /** Number of heartbeats that advanced the frontier (cursor-matched). */
    public int frontierAdvances() {
        return frontierAdvances;
    }

    /** True if a snapshot transfer is in progress (between BEGIN and END). */
    public boolean inSnapshot() {
        return inSnapshot;
    }
}
