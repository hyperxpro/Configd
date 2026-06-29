package io.configd.distribution.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;
import io.configd.distribution.wire.WatchCursor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The transport-agnostic brain of one edge subscriber connection (ADR-0043 M3, DR-N11). It owns the
 * single {@link FanOutSessionCore} plus the intricate, security-relevant logic that previously lived
 * inline in {@code FanOutServer.Connection} — and that the Netty fan-out transport must NOT
 * re-implement:
 * <ul>
 *   <li><b>Inbound routing</b> ({@link #onInboundFrame}): the first frame decides the connection
 *       type. A {@code SUBSCRIBE}-first connection is the legacy fan-out path (SUBSCRIBE-must-come-
 *       first, CURSOR_ACK → session command, anything else → protocol-violation teardown) — entirely
 *       unchanged. A {@code WATCH_CREATE}-first connection is the RFC §2 watch path (the multiplex/
 *       filter/authz veneer): many {@code WATCH_CREATE}/{@code WATCH_CANCEL} multiplexed by
 *       {@code watch_id}, each authorized before any data frame. Frames are decoded by the transport
 *       and handed here already typed.</li>
 *   <li><b>Cert-identity binding</b> ({@link #bindIdentity}, the review-condition security
 *       decision): over mTLS the verified client-cert principal is authoritative and the wire
 *       {@code edgeId} is advisory; plaintext uses the wire {@code edgeId}. The transport supplies
 *       only the verified principal. The same {@code edgeIdentity} is the watch-authorization
 *       principal (W8-6).</li>
 *   <li><b>C4 admission</b> at SUBSCRIBE (quarantine/unhealthy REFUSE → teardown with the on-wire
 *       {@link ErrorCode#QUARANTINED}; post-cooldown ALLOW_FORCE_SNAPSHOT rebinds the cursor to 0 so
 *       the C3 {@code decideMode} forces the re-bootstrap) and the demotion → QUARANTINED/UNHEALTHY
 *       teardown arm ({@link #onDemotionEvent}).</li>
 *   <li><b>The watch veneer</b> (RFC §2): a {@link WatchMultiplexSink} decorator wraps the transport
 *       sink (always installed; passthrough until the connection is a watch connection ⇒ legacy
 *       byte-identical), and a per-connection {@link WatchRegistry} tracks the multiplexed watches.
 *       {@code WATCH_CREATE} is validated (path grammar, W2-4), <b>authorized</b> via the optional
 *       {@link WatchAuthorizer} SPI <b>before any data frame</b> (the security crux; fail-closed when
 *       the authorizer is absent/unauthenticated/throwing), then registered. The first authorized
 *       watch drives the shared core drain; the decorator translates the core's structured output
 *       into per-watch {@code WATCH_*} frames.</li>
 *   <li><b>The session loop</b> ({@link #runSessionLoop}): drain commands → {@code tick} → the C4
 *       governor feed (ack-progress / queue-pressure edges / ≤1 Hz time-driven evaluation while
 *       warned) → adaptive idle backoff. Single-writer: every call into the (non-thread-safe)
 *       {@link FanOutSessionCore} <b>and</b> the (non-thread-safe) {@link WatchRegistry} happens only
 *       on the session-loop thread; the reader posts commands through a concurrent queue.</li>
 * </ul>
 *
 * <p>The transport provides a {@link TransportSink} (its bounded outbound), the verified edge
 * identity, the {@link SlowConsumerGovernor}, an optional {@link WatchAuthorizer}, and a
 * {@code teardownHook} that performs the transport-specific close. The hook MUST be idempotent — the
 * loop's {@code finally} always requests a teardown, and a QUARANTINED arm may have requested one
 * already. Because both the JDK {@code FanOutServer} and the Netty fan-out server delegate here, the
 * JDK server's unchanged tests staying green prove the extraction faithful (M1 {@code EdgeReadHandler}
 * / M2 {@code AdminApiHandler} pattern).
 */
public final class FanOutConnectionDriver {

    /** The connection type, decided by the first inbound frame (reader-thread-only). */
    private enum ConnType {
        /** No frame seen yet — the next frame decides. */
        UNDECIDED,
        /** {@code SUBSCRIBE}-first: the legacy connection-level fan-out path (unchanged). */
        LEGACY,
        /** {@code WATCH_CREATE}-first: the RFC §2 multiplexed watch path. */
        WATCH
    }

    private final FanOutSessionCore session;
    private final SlowConsumerGovernor governor;
    private final FanOutConfig config;
    private final Clock clock;
    private final String edgeIdentity;
    private final BiConsumer<ErrorCode, String> teardownHook;

    /** The commit-notification source — retained for {@code latestSeq()} when acking subsequent watches. */
    private final CommitNotificationSource source;

    /**
     * The watch-authorization gate (RFC §2 W7), or {@code null} when no watch capability is wired
     * (fail-closed: every {@code WATCH_CREATE} is rejected {@code NOT_AUTHORIZED}). The existing
     * 9-arg constructor passes {@code null}, so the JDK/Netty/sim callers compile and behave
     * unchanged until the server wiring increment threads a real authorizer.
     */
    private final WatchAuthorizer authorizer;

    /** The multiplex/filter decorator over the transport sink (always installed; passthrough until WATCH). */
    private final WatchMultiplexSink watchSink;

    /**
     * The replay source wrapped so a watch's catch-up <b>snapshot</b> is filtered to the drain-
     * owner's target (RFC §2 W5-10 / W7-4) — closing the read-authz hole where the full-store
     * snapshot would otherwise stream every key to a narrow watch. Legacy connections leave its
     * target {@code null} ⇒ whole-store passthrough ⇒ byte-identical.
     */
    private final FilteringReplaySource filteringReplaySource;

    /** The per-connection watch table (session-thread-confined). */
    private final WatchRegistry watchRegistry;

    /**
     * Max simultaneously-live watches per connection — bounds the live registry against a
     * {@code WATCH_CREATE} flood from one authenticated connection (W8-6 abuse control; redteam F4).
     */
    private static final int MAX_LIVE_WATCHES_PER_CONNECTION = 1024;

    /**
     * Max distinct {@code watch_id}s per connection lifetime — bounds the never-shrinking no-reuse
     * budget ({@code everUsed}; W2-8). A connection that exhausts it must reconnect to reset.
     */
    private static final int MAX_WATCH_IDS_PER_CONNECTION = 16_384;

    /**
     * Cadence (ms) for the governor's time-driven HEALTHY→SLOW evaluation while a session's queue is
     * at/above warn — coarse on purpose (the policy windows are tens of seconds; the busy loop pays
     * at most one {@code long} comparison per iteration). Capped below the warn window so a short
     * test window stays promotable. (Verbatim from {@code FanOutServer}.)
     */
    private final long governorEvalCadenceMs;
    private final int warnThreshold;

    private final Queue<Consumer<FanOutSessionCore>> sessionCommands = new ConcurrentLinkedQueue<>();

    /** Reader-thread-only: the connection type decided by the first inbound frame. */
    private ConnType connType = ConnType.UNDECIDED;

    /**
     * Session-thread-only: whether the shared core drain has been started (by the first authorized
     * watch's {@code onSubscribe}). A subsequent authorized watch rides the shared drain and is
     * acknowledged immediately instead of re-subscribing the core.
     */
    private boolean coreSubscribed;

    /**
     * Session-thread-only: the {@link WatchAuthorizer#policyVersion() policy version} the connection's
     * live watches were last (re-)authorized at — the bounded-revocation cursor (RFC §2 W7-7).
     * Initialized at the first watch create; the session loop re-authorizes all live watches whenever
     * the authorizer's version advances past this, then updates it. {@code Long.MIN_VALUE} until the
     * first watch (no watch connection ⇒ no re-auth).
     */
    private long lastReauthVersion = Long.MIN_VALUE;

    /**
     * The governor identity (cert principal over mTLS; wire edgeId over plaintext). Set by the
     * reader at SUBSCRIBE, read by the session thread — {@code null} until subscribed, and a
     * demotion is impossible before the subscribe command has run.
     */
    private volatile String governorIdentity;

    // Session-thread-only governor-feed state.
    private long lastAckSeen;
    private boolean aboveWarn;
    // MIN_VALUE sentinel compared with `>=` directly, NEVER by subtraction (the C4-A overflow bug).
    private long nextGovernorEvalMillis = Long.MIN_VALUE;

    /** Back-compat constructor (no watch capability): delegates with a {@code null} authorizer. */
    public FanOutConnectionDriver(CommitNotificationSource source, ReplaySource replaySource,
                                  TransportSink sink, FanOutConfig config, FanOutSessionMetrics metrics,
                                  Clock clock, SlowConsumerGovernor governor, String edgeIdentity,
                                  BiConsumer<ErrorCode, String> teardownHook) {
        this(source, replaySource, sink, config, metrics, clock, governor, edgeIdentity, teardownHook, null);
    }

    /**
     * @param authorizer the watch-authorization gate (RFC §2 W7), or {@code null} for no watch
     *                   capability — every {@code WATCH_CREATE} is then rejected {@code NOT_AUTHORIZED}
     *                   (fail-closed). The legacy {@code SUBSCRIBE} fan-out path is unaffected
     *                   regardless of this argument.
     */
    public FanOutConnectionDriver(CommitNotificationSource source, ReplaySource replaySource,
                                  TransportSink sink, FanOutConfig config, FanOutSessionMetrics metrics,
                                  Clock clock, SlowConsumerGovernor governor, String edgeIdentity,
                                  BiConsumer<ErrorCode, String> teardownHook, WatchAuthorizer authorizer) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.governor = Objects.requireNonNull(governor, "governor");
        this.edgeIdentity = Objects.requireNonNull(edgeIdentity, "edgeIdentity");
        this.teardownHook = Objects.requireNonNull(teardownHook, "teardownHook");
        this.source = Objects.requireNonNull(source, "source");
        this.authorizer = authorizer; // nullable ⇒ no watch capability ⇒ fail-closed
        // The veneer: a multiplex/filter decorator over the transport sink + a per-connection
        // registry. The core emits through the decorator; a legacy connection never flips it, so
        // its output passes through byte-identically.
        this.watchRegistry = new WatchRegistry();
        this.watchSink = new WatchMultiplexSink(Objects.requireNonNull(sink, "sink"), watchRegistry,
                this::drainedCursor);
        // Filter the catch-up snapshot to the drain-owner's target (W5-10/W7-4); target stays null
        // for a legacy connection ⇒ whole-store passthrough ⇒ byte-identical.
        this.filteringReplaySource =
                new FilteringReplaySource(Objects.requireNonNull(replaySource, "replaySource"));
        // The session uses the decorating sink + the filtering replay source and feeds demotions
        // back to onDemotionEvent.
        this.session = new FanOutSessionCore(source, filteringReplaySource, watchSink, config, metrics,
                clock, this::onDemotionEvent);
        this.governorEvalCadenceMs = Math.max(1L,
                Math.min(1_000L, governor.config().queueWarnWindowMs() / 2));
        this.warnThreshold = config.queueWarnThresholdFrames();
        this.lastAckSeen = session.lastAckedSeq();
    }

    /** The session this driver owns (diagnostics / tests). */
    public FanOutSessionCore session() {
        return session;
    }

    /** The core's drained cursor — the W5-7 clamp source for {@code WATCH_PROGRESS} bookmarks. */
    private long drainedCursor() {
        return session.cursor();
    }

    // -----------------------------------------------------------------------
    // Inbound routing (reader thread) — NEVER touches the session/registry directly
    // -----------------------------------------------------------------------

    /**
     * Routes one decoded inbound frame from the transport. The first frame decides the connection
     * type: {@code SUBSCRIBE} ⇒ the legacy fan-out path (unchanged); {@code WATCH_CREATE} ⇒ the RFC
     * §2 watch path (the one-SUBSCRIBE guard is lifted — many watches multiplex). Watch-table
     * mutation and authorization are posted as session commands so they run on the single session
     * thread (the {@link WatchRegistry} and {@link FanOutSessionCore} are not thread-safe). Called
     * only on the transport's reader thread (single-threaded inbound).
     */
    public void onInboundFrame(EdgeFrame frame) {
        switch (connType) {
            case UNDECIDED -> routeFirstFrame(frame);
            case LEGACY -> routeLegacy(frame);
            case WATCH -> routeWatch(frame);
        }
    }

    private void routeFirstFrame(EdgeFrame frame) {
        if (frame instanceof EdgeFrame.Subscribe sub) {
            connType = ConnType.LEGACY;
            admitLegacySubscribe(sub);
        } else if (frame instanceof EdgeFrame.WatchCreate create) {
            // A WATCH_CREATE-first connection is a watch connection regardless of whether an
            // authorizer is wired: a null authorizer fails CLOSED (every create rejected
            // NOT_AUTHORIZED) rather than tearing the connection down. Flip the decorator so the
            // outbound version is 0x02 and the core's frames are translated.
            connType = ConnType.WATCH;
            // C4 admission BEFORE any watch (W8-6 / redteam F3): refuse a QUARANTINED/UNHEALTHY
            // identity and set the governor identity so the slow-consumer ladder covers this
            // connection. A reconnect storm cannot dodge its cooldown by dialing WATCH_CREATE-first.
            if (!admitWatchConnection()) {
                return; // refused → torn down (QUARANTINED)
            }
            watchSink.setWatchConnection(true);
            sessionCommands.add(s -> handleWatchCreate(create));
        } else {
            teardownHook.accept(ErrorCode.PROTOCOL_VIOLATION,
                    "expected SUBSCRIBE or WATCH_CREATE first, got " + frame.type());
        }
    }

    /** The legacy SUBSCRIBE admission path — verbatim from the pre-watch driver (byte-identical). */
    private void admitLegacySubscribe(EdgeFrame.Subscribe sub) {
        EdgeFrame.Subscribe bound = bindIdentity(sub);
        // C4 admission rules on the BOUND identity (the cert principal — a reconnect storm cannot
        // dodge it by re-dialing). REFUSE → QUARANTINED + teardown; post-cooldown readmission
        // rebinds resume cursor to 0 so the C3 decideMode forces the snapshot.
        governorIdentity = bound.edgeId();
        SlowConsumerGovernor.Admission admission =
                governor.admit(bound.edgeId(), clock.currentTimeMillis());
        switch (admission.decision()) {
            case REFUSE -> {
                teardownHook.accept(ErrorCode.QUARANTINED, "subscribe refused: identity is "
                        + admission.state() + "; cooldown remaining "
                        + admission.cooldownRemainingMs() + " ms");
                return;
            }
            case ALLOW_FORCE_SNAPSHOT -> bound = new EdgeFrame.Subscribe(
                    bound.fullStore(), bound.prefixes(), 0L, -1L, bound.edgeId());
            case ALLOW -> { /* admit as requested */ }
        }
        EdgeFrame.Subscribe admitted = bound;
        sessionCommands.add(s -> s.onSubscribe(admitted));
    }

    /**
     * C4 admission for a WATCH-first connection (W8-6 / redteam F3): mirrors {@link
     * #admitLegacySubscribe}. Sets the governor identity (the cert-DN principal) so the
     * slow-consumer demotion→quarantine ladder ({@link #onDemotionEvent} and the session-loop feed)
     * covers watch connections, and refuses a QUARANTINED/UNHEALTHY identity before any watch is
     * created. ALLOW / ALLOW_FORCE_SNAPSHOT both proceed — a watch's catch-up is governed per-watch
     * by the cursor logic (a forced connection-level re-bootstrap is not a multiplex concept).
     *
     * @return true to proceed; false if the connection was refused and torn down.
     */
    private boolean admitWatchConnection() {
        governorIdentity = edgeIdentity;
        SlowConsumerGovernor.Admission admission =
                governor.admit(edgeIdentity, clock.currentTimeMillis());
        switch (admission.decision()) {
            case REFUSE -> {
                teardownHook.accept(ErrorCode.QUARANTINED, "watch subscribe refused: identity is "
                        + admission.state() + "; cooldown remaining "
                        + admission.cooldownRemainingMs() + " ms");
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    private void routeLegacy(EdgeFrame frame) {
        switch (frame) {
            case EdgeFrame.CursorAck ack -> sessionCommands.add(s -> s.onCursorAck(ack.seq()));
            default -> teardownHook.accept(ErrorCode.PROTOCOL_VIOLATION,
                    "unexpected frame for state: " + frame.type());
        }
    }

    private void routeWatch(EdgeFrame frame) {
        switch (frame) {
            case EdgeFrame.WatchCreate create -> sessionCommands.add(s -> handleWatchCreate(create));
            case EdgeFrame.WatchCancel cancel -> sessionCommands.add(s -> handleWatchCancel(cancel));
            // CURSOR_ACK is the connection-level flow-control scalar shared by all watches (W8-6).
            case EdgeFrame.CursorAck ack -> sessionCommands.add(s -> s.onCursorAck(ack.seq()));
            // SUBSCRIBE cannot be mixed onto a watch connection (W5-12 keeps the two distinct).
            case EdgeFrame.Subscribe ignored -> teardownHook.accept(ErrorCode.PROTOCOL_VIOLATION,
                    "cannot mix SUBSCRIBE on a watch connection");
            default -> teardownHook.accept(ErrorCode.PROTOCOL_VIOLATION,
                    "unexpected frame for watch connection: " + frame.type());
        }
    }

    /**
     * Binds the wire SUBSCRIBE's edgeId to the authoritative cert identity (mTLS) — the wire field
     * stays advisory. Over plaintext the wire edgeId is used as-is.
     */
    private EdgeFrame.Subscribe bindIdentity(EdgeFrame.Subscribe wire) {
        if ("plaintext".equals(edgeIdentity)) {
            return wire;
        }
        return new EdgeFrame.Subscribe(wire.fullStore(), wire.prefixes(), wire.resumeCursor(),
                wire.failoverResumeCursor(), edgeIdentity);
    }

    // -----------------------------------------------------------------------
    // Watch handling (session thread — via posted commands; registry-confining)
    // -----------------------------------------------------------------------

    /**
     * Handles one {@code WATCH_CREATE} on the session thread (RFC §2 §3.3 / §4). The order is
     * load-bearing for the security contract:
     * <ol>
     *   <li><b>validate</b> the target (path grammar / scope+kind range, W2-4) → {@code BAD_SUBSCRIBE}
     *       on a malformed target;</li>
     *   <li><b>authorize</b> the WHOLE target (W7) → {@code NOT_AUTHORIZED} on deny, fail-closed
     *       (null/unauthenticated/throwing authorizer all deny). <b>No data frame precedes a reject</b>
     *       (W7-5): the core {@code onSubscribe} (the only producer of {@code SUBSCRIBE_OK}/{@code NOTIFY}/
     *       snapshot frames) is driven ONLY after every check passes — so a denied {@code full_chain_verify}
     *       watch sees <b>zero</b> {@code NOTIFY} too;</li>
     *   <li><b>reject id-reuse</b> (W2-8) → {@code BAD_SUBSCRIBE}; a {@code watch_id} is never reused;</li>
     *   <li><b>register</b>, then either drive the shared core drain (first authorized watch) or
     *       synthesize the {@code WATCH_CREATED} ack from the current frontier (subsequent watch).</li>
     * </ol>
     */
    private void handleWatchCreate(EdgeFrame.WatchCreate create) {
        long watchId = create.watchId();
        byte[] pathBytes = create.path(); // defensive clone (pathUnsafe is wire-package-private)

        // (1) validate target grammar + scope/kind ranges + known flag bits → BAD_SUBSCRIBE
        String error = WatchTargetValidator.validate(
                create.scope(), create.targetKind(), pathBytes, create.flags());
        if (error != null) {
            rejectWatch(watchId, ErrorCode.BAD_SUBSCRIBE, error);
            return;
        }
        String path = new String(pathBytes, StandardCharsets.UTF_8);
        WatchTarget target = new WatchTarget(create.scope(), create.targetKind(), path,
                create.fullChainVerify());

        // Snapshot the policy version BEFORE the authorize gate (W7-7 seed correctness): the gate
        // below authorizes against a snapshot at version >= this, so seeding lastReauthVersion from
        // this read guarantees any LATER revoking reload (version > the gate's snapshot) is caught by
        // re-auth. Reading it AFTER authorize would let a reload that lands in the
        // authorize→seed window seed past its own revocation, so the first watch would never be
        // re-checked until the next reload (a narrow but unbounded W7-7 miss). 0 when no authorizer
        // (the create is then rejected below, so the value is unused).
        long versionAtCreate = (authorizer != null) ? authorizer.policyVersion() : 0L;

        // (2) AUTHORIZE the whole target — the security crux. Zero data frames precede a reject.
        if (!authorize(target)) {
            rejectWatch(watchId, ErrorCode.NOT_AUTHORIZED,
                    "watch not authorized: principal lacks READ ∧ WATCH over the whole target");
            return;
        }

        // (3) reject id-reuse (W2-8) — a watch_id is never reused (live OR canceled)
        if (watchRegistry.isUsed(watchId)) {
            rejectWatch(watchId, ErrorCode.BAD_SUBSCRIBE,
                    "watch_id already used on this connection (no reuse, W2-8): " + watchId);
            return;
        }

        // (3b) per-connection watch caps (W8-6 abuse control; redteam F4) — bound the live registry
        // and the never-shrinking no-reuse budget so one authenticated connection cannot exhaust
        // edge memory with a WATCH_CREATE flood.
        if (watchRegistry.liveCount() >= MAX_LIVE_WATCHES_PER_CONNECTION) {
            rejectWatch(watchId, ErrorCode.BAD_SUBSCRIBE,
                    "too many live watches on this connection (max " + MAX_LIVE_WATCHES_PER_CONNECTION + ")");
            return;
        }
        if (watchRegistry.totalUsed() >= MAX_WATCH_IDS_PER_CONNECTION) {
            rejectWatch(watchId, ErrorCode.BAD_SUBSCRIBE,
                    "watch_id budget exhausted on this connection (max " + MAX_WATCH_IDS_PER_CONNECTION
                            + "); reconnect to reset");
            return;
        }

        // (4) register + drive the shared drain (first watch) or ack a subsequent watch.
        long requested = startCursorS(create.cursor());
        watchRegistry.register(new WatchRegistry.WatchEntry(
                watchId, edgeIdentity, Set.of(), target, requested, create.flags()));
        if (!coreSubscribed) {
            coreSubscribed = true;
            // Seed the bounded-revocation cursor (W7-7) from the version read BEFORE the authorize
            // gate (<= the version the gate authorized against), so any later revoking reload advances
            // past it and triggers re-auth. The authorizer is non-null here (the watch passed the gate).
            lastReauthVersion = versionAtCreate;
            // The drain-owner's target is BOTH the snapshot content filter (FilteringReplaySource,
            // W5-10/W7-4 — a narrow watch never receives the whole store) AND the fixed
            // WATCH_SNAPSHOT_* tag (F2). Set both before driving onSubscribe.
            filteringReplaySource.setTarget(target);
            watchSink.setSnapshotOwner(watchId);
            // Map the requested resume to the core subscribe cursor (W3-4 / F1):
            //  - with_initial_snapshot ⇒ cursor 0 ⇒ the core SNAPSHOT_FIRSTs and the snapshot is
            //    filtered to this watch's target (the v1-mandated snapshot-then-tail, W5-4a);
            //  - from-now (requested 0, no flag) ⇒ TAIL from the current frontier — cursor 0 means
            //    "from now per shard", NOT "replay all" (W3-4), so NO snapshot is delivered;
            //  - resume (requested > 0) ⇒ TAIL if in-window, else a target-filtered SNAPSHOT_FIRST.
            long coreCursor;
            if (create.withInitialSnapshot()) {
                coreCursor = 0L;
            } else if (requested == 0L) {
                coreCursor = Math.max(0L, source.latestSeq());
            } else {
                coreCursor = requested;
            }
            // Arm the SUBSCRIBE_OK → WATCH_CREATED mapping, then drive the shared full-store drain.
            // onSubscribe emits SUBSCRIBE_OK synchronously, which the decorator translates into
            // WATCH_CREATED(watchId) (the first frame for the watch).
            watchSink.expectWatchCreated(watchId);
            session.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), coreCursor, -1L, edgeIdentity));
        } else {
            // A subsequent watch rides the shared connection drain (W8-6): acknowledge immediately,
            // TAIL from the current frontier. Independent resume positions need a separate
            // connection (the v1 single-shared-drain boundary).
            long latest = Math.max(0L, source.latestSeq());
            watchSink.offerWatchFrame(new EdgeFrame.WatchCreated(watchId,
                    List.of(new EdgeFrame.ShardMode(0, latest, EdgeFrame.Mode.TAIL))));
        }
    }

    /**
     * Handles one {@code WATCH_CANCEL} on the session thread (W5-8). Deregisters the watch (its id
     * stays burned in {@code everUsed} — no reuse, W2-8) and acknowledges it with a per-watch
     * terminal. The connection and sibling watches are unaffected (multiplex isolation). Canceling
     * an unknown / already-canceled id is a no-op.
     */
    private void handleWatchCancel(EdgeFrame.WatchCancel cancel) {
        WatchRegistry.WatchEntry removed = watchRegistry.cancel(cancel.watchId());
        if (removed != null) {
            // Orderly per-watch close. The closed ErrorCode taxonomy has no dedicated "canceled"
            // code; SERVER_SHUTDOWN (9) is the orderly-close code (a CANCELED code is a v2 taxonomy
            // addition). The connection and other watches survive.
            watchSink.offerWatchFrame(new EdgeFrame.WatchCanceled(
                    cancel.watchId(), ErrorCode.SERVER_SHUTDOWN, null, "canceled"));
        }
    }

    /**
     * The bounded-revocation tick step (RFC §2 W7-7): re-authorizes the connection's live watches iff
     * the authorizer's {@link WatchAuthorizer#policyVersion() policy version} has advanced since the
     * last check, then records the new version. A single volatile-acquire comparison on the common
     * (unchanged-policy) path. Runs only for a watch connection ({@code coreSubscribed} ⇒ a non-null
     * authorizer). Extracted as the deterministic test seam (mirrors {@link #drainInboundCommands}):
     * the session loop calls it each iteration, and a test acting as the session thread calls it
     * directly after publishing a new policy version.
     */
    void maybeReauthorizeWatches() {
        if (coreSubscribed && authorizer != null) {
            long policyV = authorizer.policyVersion();
            if (policyV != lastReauthVersion) {
                reauthorizeLiveWatches();
                lastReauthVersion = policyV;
            }
        }
    }

    /**
     * Re-authorizes every live watch against the CURRENT ACL state and force-closes —
     * {@code WATCH_CANCELED(NOT_AUTHORIZED)}, which a driver treats as terminal and does not retry
     * (W7-6) — any whose principal no longer holds {@code READ ∧ WATCH} over its whole target. The
     * connection and the surviving watches are unaffected (multiplex isolation). Called by
     * {@link #maybeReauthorizeWatches} ONLY when the policy version has advanced. Bounded by the
     * live-watch count × the authorizer's per-call cost; non-blocking on the session thread.
     */
    private void reauthorizeLiveWatches() {
        // Snapshot the live entries — we cancel during iteration. Each entry's principal is
        // edgeIdentity and roles Set.of(), so authorize(target) re-runs the SAME whole-target gate the
        // create used, now against the reloaded ACL snapshot (fail-closed on any throwable).
        for (WatchRegistry.WatchEntry e : List.copyOf(watchRegistry.liveEntries())) {
            if (!authorize(e.target())) {
                watchSink.offerWatchFrame(new EdgeFrame.WatchCanceled(e.watchId(),
                        ErrorCode.NOT_AUTHORIZED, null,
                        "watch revoked: ACL policy no longer grants READ ∧ WATCH over the target"));
                watchRegistry.cancel(e.watchId());
            }
        }
    }

    /** Emits a per-watch terminal reject directly (bypassing translation) — no data frame precedes it. */
    private void rejectWatch(long watchId, ErrorCode code, String message) {
        watchSink.offerWatchFrame(new EdgeFrame.WatchCanceled(watchId, code, null, message));
    }

    /**
     * The watch-authorization gate (RFC §2 W7) — fail-closed on every doubt: a {@code null}
     * authorizer (no capability wired), an unauthenticated principal ({@code "plaintext"}), a
     * {@code false} verdict, or <b>any throwable</b> from the SPI all deny. The asserted-roles set
     * is empty on the cert-DN edge path; the adapter resolves ACL-static / config-bound roles
     * internally.
     */
    private boolean authorize(WatchTarget target) {
        if (authorizer == null || "plaintext".equals(edgeIdentity)) {
            return false;
        }
        try {
            return authorizer.authorizeWatch(edgeIdentity, Set.of(), target);
        } catch (Throwable t) {
            return false; // fail-closed on ANY throwable (W7 / SPI contract)
        }
    }

    /**
     * The resume seq the shared drain starts from: the {@code gid=0} cursor component, or 0 for the
     * empty "from now per shard" vector (W3-4). At N=1 the vector is either empty or the single
     * element {@code (gid=0, S)} (W3-5).
     */
    private static long startCursorS(WatchCursor cursor) {
        for (WatchCursor.Component c : cursor.components()) {
            if (c.gid() == 0) {
                return c.s();
            }
        }
        return 0L;
    }

    // -----------------------------------------------------------------------
    // Demotion listener (runs on the session thread, inside the core's demote)
    // -----------------------------------------------------------------------

    private void onDemotionEvent(DemotionEvent event) {
        String id = governorIdentity;
        if (id == null) {
            return; // defensive: governorIdentity is set on the first SUBSCRIBE / WATCH_CREATE, both
                    // of which precede any drain (and thus any demotion); it cannot be null here.
        }
        SlowConsumerGovernor.ConsumerState state =
                governor.onDemotion(id, event, clock.currentTimeMillis());
        if (state == SlowConsumerGovernor.ConsumerState.QUARANTINED
                || state == SlowConsumerGovernor.ConsumerState.UNHEALTHY) {
            teardownHook.accept(ErrorCode.QUARANTINED, "slow-consumer policy: " + state
                    + " (" + event.reason() + ", cursor=" + event.cursor()
                    + ", lastAckedSeq=" + event.lastAckedSeq() + ")");
        }
    }

    // -----------------------------------------------------------------------
    // Session loop (session thread)
    // -----------------------------------------------------------------------

    /**
     * Drives the session — drain inbound commands, {@code tick}, feed the C4 governor, back off when
     * idle — until {@code alive} goes false or the session reaches {@code CLOSED}. Requests a
     * teardown in its {@code finally} (idempotent on the transport side). Runs on the transport's
     * dedicated session thread.
     *
     * @param alive liveness gate composed by the transport (connection alive AND server running)
     */
    public void runSessionLoop(BooleanSupplier alive) {
        long idleParkNanos = 0;
        try {
            while (alive.getAsBoolean()) {
                // Drain inbound session commands FIRST so every session/registry mutation happens on
                // THIS thread (single-writer; neither is thread-safe). A posted command is progress.
                boolean drainedCommand = drainInboundCommands();

                int beforeDepth = session.inFlightFrames();
                long beforeCursor = session.cursor();
                long nowMillis = clock.currentTimeMillis();
                session.tick(nowMillis);
                if (session.state() == FanOutSessionCore.SessionState.CLOSED) {
                    return;
                }

                // C4 governor feed: ack progress, queue-pressure EDGES, and the ≤1 Hz time-driven
                // evaluation while warned — all edge/cadence-gated, never per-frame.
                String id = governorIdentity;
                if (id != null && alive.getAsBoolean()) {
                    long acked = session.lastAckedSeq();
                    if (acked > lastAckSeen) {
                        lastAckSeen = acked;
                        governor.onAckProgress(id, session.cursor(), acked, nowMillis);
                    }
                    boolean above = warnThreshold > 0 && session.inFlightFrames() >= warnThreshold;
                    if (above != aboveWarn) {
                        aboveWarn = above;
                        governor.onQueuePressure(id, above, session.cursor(), acked, nowMillis);
                    }
                    if (above && nowMillis >= nextGovernorEvalMillis) {
                        nextGovernorEvalMillis = nowMillis + governorEvalCadenceMs;
                        governor.evaluate(id, nowMillis);
                    }
                }

                // Bounded watch revocation (RFC §2 W7-7) — the version-gated re-auth tick step.
                maybeReauthorizeWatches();

                boolean madeProgress = drainedCommand
                        || session.cursor() != beforeCursor
                        || session.inFlightFrames() != beforeDepth;
                if (madeProgress) {
                    idleParkNanos = 0; // active: immediate re-poll
                } else {
                    long capNanos = config.idlePollMs() * 1_000_000L;
                    idleParkNanos = Math.min(capNanos,
                            idleParkNanos == 0 ? 100_000L : idleParkNanos * 2);
                    LockSupport.parkNanos(idleParkNanos);
                }
            }
        } finally {
            teardownHook.accept(ErrorCode.SERVER_SHUTDOWN, "session ended");
        }
    }

    /**
     * Drains every queued inbound command onto the calling (session) thread, running each against
     * the core. The session loop's first step; also the deterministic test seam (a test acting as
     * the session thread calls this, then {@link #session()}{@code .tick(...)}, to advance without
     * spinning up the real loop thread).
     *
     * @return true iff at least one command ran (the loop's progress signal)
     */
    boolean drainInboundCommands() {
        boolean drained = false;
        Consumer<FanOutSessionCore> cmd;
        while ((cmd = sessionCommands.poll()) != null) {
            cmd.accept(session);
            drained = true;
        }
        return drained;
    }
}
