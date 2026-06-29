package io.configd.edge;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeSnapshotCodec;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigSigner;
import io.configd.store.ConfigSnapshot;
import io.configd.store.ReadResult;

import java.nio.file.Path;
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
         * C3 (resubscribe-with-cursor recovery, design §1): {@code resumeCursor} is
         * normally the current cursor; the ADR-0040 forced re-bootstrap is the ONE case
         * that carries {@code 0} — the shell must subscribe at the directive's cursor,
         * not the core's.
         *
         * @param resumeCursor the applied-mutation seq to resume from
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

        /**
         * ADR-0040 terminal fail-loud: the edge can neither advance (poison seq) nor
         * re-bootstrap (the forced snapshot failed or was never produced). The shell MUST
         * exit the process non-zero — an edge serving a state it cannot advance behind a
         * green health check is the lying-dashboard failure mode; an infinite reconnect
         * loop is the hot-loop failure mode. {@code configd.edge.poison_pill_terminal}
         * was already emitted by the policy before this directive was queued.
         *
         * @param reason the structured cause (diagnostic; the policy already logged SEVERE)
         */
        record TerminalFailure(String reason) implements ConnectionDirective {
            public TerminalFailure {
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
    private final PoisonPillPolicy poisonPolicy;

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

    /**
     * True while the server has told us a snapshot flow is coming (SUBSCRIBE_OK
     * SNAPSHOT_FIRST or a DEMOTED_TO_CATCHUP notice) or one is mid-transfer. Suppresses
     * the C3 gap-resubscribe and DISCONNECTED-rebootstrap directives: the in-flight
     * snapshot is the heal already in progress (C1's in-session recovery stays primary;
     * resubscribe-with-cursor recovers the genuinely orphaned cases). Cleared on
     * SNAPSHOT_END and on {@link #onReconnected()}.
     */
    private boolean snapshotExpected;

    /**
     * The staleness state observed by the previous {@link #tick(long)} on THIS connection
     * (CT-06): a live-connection transition INTO DISCONNECTED queues the re-bootstrap
     * resubscribe. Re-baselined in {@link #onReconnected()} so an entry that happened
     * while disconnected does not bounce a fresh connection before it can heal.
     */
    private StalenessTracker.State lastTickStalenessState;

    /** Latched once the poison policy decided TERMINAL: the core stops applying. */
    private boolean terminal;

    /**
     * TEST-ONLY apply-fault injector (the ADR-0040 test-the-tester seam; the
     * {@link #loadSnapshotForced} precedent). Configd stores opaque bytes, so a REAL
     * deterministic apply-throw cannot be manufactured through the production codec/applier
     * — which is exactly ADR-0040's point: the poison policy is a defensive net for
     * defects that do not exist yet. Tests inject the throw here so the PRODUCTION
     * catch/policy/directive path runs verbatim. Production never sets this.
     */
    public interface ApplyFaultInjector {
        /** Called inside the apply try-block before each notification apply. */
        default void beforeApply(long seq) { }

        /** Called inside the snapshot try-block before the cutover loads. */
        default void beforeSnapshotLoad(long snapshotSeq) { }
    }

    private ApplyFaultInjector applyFaultInjector;

    /**
     * TEST-ONLY: installs an {@link ApplyFaultInjector} ({@code null} = none). See the
     * interface javadoc; production never calls this.
     */
    public void setApplyFaultInjectorForTest(ApplyFaultInjector injector) {
        this.applyFaultInjector = injector;
    }

    // --- diagnostic counters (read by tests / sim digest folding) --------------------

    private long appliedCount;
    private int gapsDetected;
    private int snapshotsApplied;
    private int backwardSnapshotsRefused;
    private int heartbeatsObserved;
    private int frontierAdvances;
    private int verifyRejections;
    private int disconnectedRebootstraps;

    /**
     * Sim/test constructor: no signature verifier, no epoch persistence (signature rows
     * are exercised by the C2 integration test with a real key, not this core's sim).
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
        this(clock, invariantMonitor, implausibleCounter, strongReadKeyClass, sink,
                heartbeatMs, silenceFactor, null, null);
    }

    /**
     * Full (production / C2 process) constructor: wires the F-0052 signed-chain
     * verification and the SEC-017 epoch sidecar into the real {@link DeltaApplier}.
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
     * @param verifier           optional Ed25519 verifier (CT-23: signed-chain verification;
     *                           null = sim/unsigned mode — a SIGNED delta is then rejected
     *                           fail-closed by the applier, F-0052)
     * @param epochLockDir       optional directory for the SEC-017 {@code epoch.lock}
     *                           sidecar ({@code --data-dir}; null = no epoch persistence).
     *                           Only epoch metadata is written here — never values (RR-098:
     *                           {@code secure/} values stay in memory only)
     */
    public EdgeClientCore(Clock clock, InvariantMonitor invariantMonitor,
                          MetricsRegistry.Counter implausibleCounter,
                          StrongReadKeyClass strongReadKeyClass, FrameSink sink,
                          long heartbeatMs, int silenceFactor,
                          ConfigSigner verifier, Path epochLockDir) {
        this(clock, invariantMonitor, implausibleCounter, strongReadKeyClass, sink,
                heartbeatMs, silenceFactor, verifier, epochLockDir, new PoisonPillPolicy());
    }

    /**
     * Full constructor with an explicit ADR-0040 {@link PoisonPillPolicy} (C3). The other
     * constructors default to {@code new PoisonPillPolicy()} (bounded retries
     * {@value PoisonPillPolicy#DEFAULT_MAX_RETRIES}, no registry counters).
     *
     * @param poisonPolicy the apply-failure policy (non-null; the process wires registry
     *                     counters and the configured {@code edge.poisonpill.maxRetries})
     */
    public EdgeClientCore(Clock clock, InvariantMonitor invariantMonitor,
                          MetricsRegistry.Counter implausibleCounter,
                          StrongReadKeyClass strongReadKeyClass, FrameSink sink,
                          long heartbeatMs, int silenceFactor,
                          ConfigSigner verifier, Path epochLockDir,
                          PoisonPillPolicy poisonPolicy) {
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
        this.poisonPolicy = Objects.requireNonNull(poisonPolicy, "poisonPolicy must not be null");
        this.client = new EdgeConfigClient(clock, invariantMonitor, implausibleCounter,
                strongReadKeyClass);
        this.applier = new DeltaApplier(client, verifier, epochLockDir);
        this.readStore = new LocalConfigStore(ConfigSnapshot.EMPTY, clock, invariantMonitor);
        this.cursor = 0L;
        this.lastAckedSeq = 0L;
        // CT-06 baseline: the boot state IS DISCONNECTED (no frontier yet) — initializing
        // the transition baseline here means process start never "enters" DISCONNECTED
        // (bootstrap is C5's territory, not re-bootstrap).
        this.lastTickStalenessState = client.staleness();
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
        if (terminal) {
            // ADR-0040 terminal: the core is dead; the shell is about to exit the process.
            // Applying anything further could mask the wedge the terminal directive reports.
            return;
        }
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
            // The RFC §2 WATCH_* frames (0x0A..0x12) are the per-watch multiplexed watch protocol —
            // a SEPARATE client surface. This legacy edge fan-out client subscribes via SUBSCRIBE and
            // never opens a watch (0x02) connection, so the server never sends it a WATCH_* frame;
            // receiving one is a mis-wired shell (loud, not silently ignored), like the edge→server
            // frames above. A default keeps this consumer compiling as the §2 frame family grows.
            default -> throw new IllegalArgumentException(
                    frame.type() + " is not an edge fan-out client frame (the RFC §2 WATCH_* watch "
                            + "protocol is a separate client surface, not the SUBSCRIBE fan-out path)");
        }
    }

    private void onSubscribeOk(EdgeFrame.SubscribeOk ok) {
        this.mode = ok.mode();
        // SNAPSHOT_FIRST: the server owes us a snapshot — the heal is already in flight,
        // so the C3 gap/DISCONNECTED resubscribe directives stay suppressed until it lands.
        this.snapshotExpected = (ok.mode() == EdgeFrame.Mode.SNAPSHOT_FIRST);
        // latestSeq from the handshake seeds the cursor-lag view until the first heartbeat.
        this.lastHeartbeatLatestSeq = ok.latestSeq();
        this.cursorLag = Math.max(0L, ok.latestSeq() - cursor);
    }

    private void onNotify(EdgeFrame.Notify n) {
        for (CommitNotification notification : n.notifications()) {
            if (!applyNotification(notification)) {
                // Apply failure (ADR-0040): the policy decided the recovery; the rest of
                // the batch would only GAP against the unadvanced cursor — abort it.
                break;
            }
        }
        // One CURSOR_ACK per batch for the highest applied seq (the design's per-batch ack).
        // If nothing applied (all gap/stale) the cursor is unchanged and the ack is benign.
        ackCursor();
    }

    /**
     * Applies one verbatim notification through the real {@link DeltaApplier} (gap/stale
     * semantics), mirroring the ADR-0038-filtered delta into the monitor-wired read store.
     *
     * <h4>C3 recovery orchestration (design §1 / C3-1)</h4>
     * {@code GAP_DETECTED} queues a {@code RECONNECT_RESUBSCRIBE(cursor)} — the server's
     * already-tested TAIL/SNAPSHOT_FIRST decision then resolves replay-from-the-boundary
     * vs snapshot re-bootstrap; no new wire surface. Suppressed while a snapshot flow is
     * already in flight (the C1 in-session heal stays primary).
     *
     * <h4>ADR-0040 poison pill (CT-33)</h4>
     * An apply-time {@link RuntimeException} (the frame VERIFIED; the apply threw) is
     * routed through the {@link PoisonPillPolicy}: bounded retries via
     * resubscribe-at-cursor → forced snapshot re-bootstrap (resubscribe at cursor 0) →
     * terminal fail-loud. An invalid-signature delta is NOT a poison pill — it is
     * rejected fail-closed by the applier below and never throws.
     *
     * @return false if an apply failure occurred and the enclosing batch must be aborted
     */
    private boolean applyNotification(CommitNotification notification) {
        DeltaApplier.ApplyResult result;
        try {
            if (applyFaultInjector != null) {
                applyFaultInjector.beforeApply(notification.seq()); // TEST-ONLY (may throw)
            }
            // The applier applies to the client's internal store (ADR-0038 filter + ADR-0039
            // frontier from the leader commit timestamp) on APPLIED — a single atomic apply.
            result = applier.offer(notification.delta(), notification.commitTimestampMillis());
            if (result == DeltaApplier.ApplyResult.APPLIED) {
                // Mirror the SAME ADR-0038-filtered delta into the monitor-wired read store
                // so it stays byte-identical to the client's internal store and reads route
                // through the real INV-M1 seam. (filterForStorage is the lockstep contract;
                // a pure function of the current subscription, so both stores agree.)
                readStore.applyDelta(client.filterForStorage(notification.delta()));
            }
        } catch (RuntimeException e) {
            actOnPoison(poisonPolicy.onApplyFailure(notification.seq(), e),
                    "seq=" + notification.seq());
            return false;
        }
        switch (result) {
            case APPLIED -> {
                cursor = notification.seq();
                appliedCount++;
                refreshCursorLag();
                poisonPolicy.onProgress(cursor);
            }
            case GAP_DETECTED -> {
                gapsDetected++;
                // C3: resubscribe-with-cursor recovery (one directive per wedge; the
                // reconnectPending latch and an in-flight snapshot suppress spam).
                if (!reconnectPending && !snapshotExpected && !inSnapshot) {
                    queueReconnect(cursor,
                            "gap-detected:cursor=" + cursor + ",seq=" + notification.seq());
                }
            }
            case STALE_DELTA -> {
                // Re-delivered/older notification: recorded, not applied. Cursor unchanged
                // — never a stale overwrite (contract §3 INV-M1 / §4 monotonicity).
            }
            // F-0052 / CT-23: a rejected signature/replay is NOT a gap (the chain is
            // contiguous; the content failed verification). Counted on its own series so
            // edge_gaps_total stays an honest gap signal — the cursor does not advance, so
            // the server's ack-lag eventually demotes + re-snapshots a persistently
            // rejecting edge. Deliberately NOT a poison pill (ADR-0040: fail-closed halt
            // + the ADR-0039 staleness ladder surface it).
            case UNSIGNED_REJECTED, SIGNATURE_INVALID, REPLAY_REJECTED -> verifyRejections++;
        }
        return true;
    }

    /** Translates a {@link PoisonPillPolicy.Action} into the shell-visible directive. */
    private void actOnPoison(PoisonPillPolicy.Action action, String what) {
        switch (action) {
            // Bounded retry: re-subscribe at the CURRENT cursor; the server redelivers the
            // failing seq (heals a transient apply failure).
            case RESUBSCRIBE -> queueReconnect(cursor, "poison-retry:" + what);
            // Quarantined: forced snapshot re-bootstrap — resubscribe at cursor 0 (the ONE
            // use of cursor 0; ADR-0040). The server's decideMode sends SNAPSHOT_FIRST
            // whenever the ring has evicted or the backlog exceeds the bounded queue; the
            // snapshot's cumulative state covers the poison seq, so it is never re-applied.
            case REBOOTSTRAP -> queueReconnect(0L, "poison-rebootstrap:" + what);
            case TERMINAL -> {
                terminal = true;
                directives.add(new ConnectionDirective.TerminalFailure(
                        "poison-pill-terminal:" + what));
            }
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
        try {
            // Reassemble + deserialize the ADR-0028 body (bounds-checked by the codec).
            byte[] body = EdgeSnapshotCodec.reassemble(pendingChunks);
            ConfigSnapshot snapshot = EdgeSnapshotCodec.deserialize(body);
            inSnapshot = false;
            pendingChunks.clear();
            pendingSnapshotSeq = -1L;
            snapshotExpected = false;

            // C1(a) fix: REFUSE a backward snapshot (seq < cursor) — the edge never regresses.
            // Re-ack the real (higher) cursor so the server's ack-lag clears and it stops
            // re-sending the stale snapshot.
            if (seq < cursor) {
                backwardSnapshotsRefused++;
                ackCursor();
                return;
            }

            if (applyFaultInjector != null) {
                applyFaultInjector.beforeSnapshotLoad(seq); // TEST-ONLY (may throw)
            }
            // Atomic cutover: loadSnapshot wholesale + resetGap; cursor = snapshot seq.
            // (If the second load were ever to throw after the first succeeded, the two
            // stores would diverge transiently — the catch below routes that through the
            // ADR-0040 policy, whose recovery is a wholesale re-load of BOTH stores or a
            // terminal exit; divergence cannot survive a successful recovery.)
            snapshotsApplied++;
            client.loadSnapshot(snapshot);
            readStore.loadSnapshot(snapshot);
            applier.resetGap();
            cursor = seq;
            refreshCursorLag();
            poisonPolicy.onProgress(cursor);
        } catch (RuntimeException ex) {
            // ADR-0040: a snapshot that fails to reassemble/apply. During a forced
            // re-bootstrap this is the terminal condition verbatim; otherwise it gets the
            // bounded-retry ladder (the server re-sends — C1's self-healing transfer).
            inSnapshot = false;
            pendingChunks.clear();
            pendingSnapshotSeq = -1L;
            snapshotExpected = false;
            actOnPoison(poisonPolicy.onSnapshotApplyFailure(seq, ex), "snapshotSeq=" + seq);
            return;
        }
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
            case DEMOTED_TO_CATCHUP ->
                // Informational: a snapshot flow follows (the server demoted us to catch-up).
                // No reconnect — the session continues; the snapshot heals the cursor. The
                // owed snapshot suppresses the C3 gap/DISCONNECTED resubscribe directives.
                snapshotExpected = true;
            default -> queueReconnect(cursor, "error-close:" + err.code());
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
        if (terminal) {
            return; // ADR-0040 terminal: the shell is about to exit the process.
        }
        // Re-ack on advance (idempotent; covers an earlier would-block ack).
        if (cursor > lastAckedSeq) {
            ackCursor();
        }

        // Heartbeat-silence reconnect: only once we have seen a heartbeat (a never-connected
        // session is the shell's connect concern, not a silence reconnect).
        if (!reconnectPending && lastHeartbeatAtMillis >= 0) {
            long silentFor = nowMillis - lastHeartbeatAtMillis;
            if (silentFor > silenceFactor * heartbeatMs) {
                queueReconnect(cursor, "heartbeat-silence:" + silentFor + "ms");
            }
        }

        // CT-06 (C3): a live-connection transition INTO DISCONNECTED queues the
        // re-bootstrap resubscribe at the CURRENT cursor (NOT 0 — the server's
        // TAIL/SNAPSHOT_FIRST decision picks replay vs re-bootstrap; cursor 0 is reserved
        // for the poison terminal path, design §1.3). Entry-edge-triggered so a wedged
        // session fires once per entry, not per tick; suppressed while a snapshot flow is
        // already healing us. The boot state is DISCONNECTED and the baseline is seeded at
        // construction/reconnect, so a fresh bootstrap never fires this (that is C5's
        // bootstrap, not a re-bootstrap).
        StalenessTracker.State state = client.staleness();
        if (state == StalenessTracker.State.DISCONNECTED
                && lastTickStalenessState != StalenessTracker.State.DISCONNECTED
                && !reconnectPending && !snapshotExpected && !inSnapshot) {
            disconnectedRebootstraps++;
            queueReconnect(cursor, "disconnected-rebootstrap:stalenessMs=" + stalenessMs());
        }
        lastTickStalenessState = state;
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

    private void queueReconnect(long resumeCursor, String reason) {
        directives.add(new ConnectionDirective.ReconnectNextEndpoint(resumeCursor, reason));
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
        // A snapshot owed by the PREVIOUS session died with it; the new session decides anew.
        snapshotExpected = false;
        // CT-06: re-baseline the DISCONNECTED entry detector to the state at reconnect, so
        // an entry that happened while disconnected does not bounce the fresh connection
        // before it can heal — only a transition OBSERVED LIVE on this connection fires.
        lastTickStalenessState = client.staleness();
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

    /**
     * Number of notifications rejected by signed-chain verification (unsigned / invalid
     * signature / epoch replay — F-0052 / CT-23). Distinct from {@link #gapsDetected()}.
     */
    public int verifyRejections() {
        return verifyRejections;
    }

    /** True if a snapshot transfer is in progress (between BEGIN and END). */
    public boolean inSnapshot() {
        return inSnapshot;
    }

    /**
     * Number of CT-06 DISCONNECTED-entry re-bootstrap resubscribes this core has queued
     * (the live-connection trigger; the process-level trigger is the metrics pump's).
     */
    public int disconnectedRebootstraps() {
        return disconnectedRebootstraps;
    }

    /** The ADR-0040 poison-pill policy (counter reads for the metrics pump / tests). */
    public PoisonPillPolicy poisonPolicy() {
        return poisonPolicy;
    }

    /** True once the ADR-0040 policy decided TERMINAL (the core stops applying). */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * True iff this edge's store is authoritative for {@code key} (ADR-0040 §2 / ADR-0038):
     * the subscription is full-store, or the key matches a subscribed prefix. Within the
     * subscription a store miss IS authoritative non-existence (the negative-caching
     * descope's premise); outside it the read surface must refuse with
     * {@code X-Configd-Refused: not-subscribed} instead of consulting the store.
     * (Strong-read keys are always stored but never served — the serving surface checks
     * that FIRST; this predicate is the subscription slice only.)
     */
    public boolean servesKey(String key) {
        return client.servesKey(key);
    }
}
