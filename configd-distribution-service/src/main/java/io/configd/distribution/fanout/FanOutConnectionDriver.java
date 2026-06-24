package io.configd.distribution.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;

import java.util.Objects;
import java.util.Queue;
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
 *   <li><b>Inbound routing</b> ({@link #onInboundFrame}): SUBSCRIBE-must-come-first, CURSOR_ACK →
 *       session command, anything else → protocol-violation teardown. Frames are decoded by the
 *       transport and handed here already typed.</li>
 *   <li><b>Cert-identity binding</b> ({@link #bindIdentity}, the review-condition security
 *       decision): over mTLS the verified client-cert principal is authoritative and the wire
 *       {@code edgeId} is advisory; plaintext uses the wire {@code edgeId}. The transport supplies
 *       only the verified principal.</li>
 *   <li><b>C4 admission</b> at SUBSCRIBE (quarantine/unhealthy REFUSE → teardown with the on-wire
 *       {@link ErrorCode#QUARANTINED}; post-cooldown ALLOW_FORCE_SNAPSHOT rebinds the cursor to 0 so
 *       the C3 {@code decideMode} forces the re-bootstrap) and the demotion → QUARANTINED/UNHEALTHY
 *       teardown arm ({@link #onDemotionEvent}).</li>
 *   <li><b>The session loop</b> ({@link #runSessionLoop}): drain commands → {@code tick} → the C4
 *       governor feed (ack-progress / queue-pressure edges / ≤1 Hz time-driven evaluation while
 *       warned) → adaptive idle backoff. Single-writer: every call into the (non-thread-safe)
 *       {@link FanOutSessionCore} happens only on the session-loop thread; the reader posts commands
 *       through a concurrent queue.</li>
 * </ul>
 *
 * <p>The transport provides a {@link TransportSink} (its bounded outbound), the verified edge
 * identity, the {@link SlowConsumerGovernor}, and a {@code teardownHook} that performs the
 * transport-specific close (best-effort final {@code ERROR_CLOSE}, socket/channel close, lifecycle
 * metrics). The hook MUST be idempotent — the loop's {@code finally} always requests a teardown, and
 * a QUARANTINED arm may have requested one already. Because both the JDK {@code FanOutServer} and
 * the Netty fan-out server delegate here, the JDK server's unchanged tests staying green prove the
 * extraction faithful (M1 {@code EdgeReadHandler} / M2 {@code AdminApiHandler} pattern).
 */
public final class FanOutConnectionDriver {

    private final FanOutSessionCore session;
    private final SlowConsumerGovernor governor;
    private final FanOutConfig config;
    private final Clock clock;
    private final String edgeIdentity;
    private final BiConsumer<ErrorCode, String> teardownHook;

    /**
     * Cadence (ms) for the governor's time-driven HEALTHY→SLOW evaluation while a session's queue is
     * at/above warn — coarse on purpose (the policy windows are tens of seconds; the busy loop pays
     * at most one {@code long} comparison per iteration). Capped below the warn window so a short
     * test window stays promotable. (Verbatim from {@code FanOutServer}.)
     */
    private final long governorEvalCadenceMs;
    private final int warnThreshold;

    private final Queue<Consumer<FanOutSessionCore>> sessionCommands = new ConcurrentLinkedQueue<>();

    /** Reader-thread-only: whether the one allowed SUBSCRIBE has been seen. */
    private boolean subscribed;

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

    public FanOutConnectionDriver(CommitNotificationSource source, ReplaySource replaySource,
                                  TransportSink sink, FanOutConfig config, FanOutSessionMetrics metrics,
                                  Clock clock, SlowConsumerGovernor governor, String edgeIdentity,
                                  BiConsumer<ErrorCode, String> teardownHook) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.governor = Objects.requireNonNull(governor, "governor");
        this.edgeIdentity = Objects.requireNonNull(edgeIdentity, "edgeIdentity");
        this.teardownHook = Objects.requireNonNull(teardownHook, "teardownHook");
        // The session uses the transport's sink and feeds demotions back to onDemotionEvent.
        this.session = new FanOutSessionCore(source, replaySource, sink, config, metrics, clock,
                this::onDemotionEvent);
        this.governorEvalCadenceMs = Math.max(1L,
                Math.min(1_000L, governor.config().queueWarnWindowMs() / 2));
        this.warnThreshold = config.queueWarnThresholdFrames();
        this.lastAckSeen = session.lastAckedSeq();
    }

    /** The session this driver owns (diagnostics / tests). */
    public FanOutSessionCore session() {
        return session;
    }

    // -----------------------------------------------------------------------
    // Inbound routing (reader thread) — NEVER touches the session directly
    // -----------------------------------------------------------------------

    /**
     * Routes one decoded inbound frame from the transport. SUBSCRIBE must come first (binds the
     * cert identity + runs C4 admission); subsequent frames may only be CURSOR_ACK. Anything else is
     * a protocol violation and tears the connection down. Called only on the transport's reader
     * thread (single-threaded inbound).
     */
    public void onInboundFrame(EdgeFrame frame) {
        if (!subscribed) {
            if (!(frame instanceof EdgeFrame.Subscribe sub)) {
                teardownHook.accept(ErrorCode.PROTOCOL_VIOLATION,
                        "expected SUBSCRIBE first, got " + frame.type());
                return;
            }
            subscribed = true;
            EdgeFrame.Subscribe bound = bindIdentity(sub);
            // C4 admission rules on the BOUND identity (the cert principal — a reconnect storm
            // cannot dodge it by re-dialing). REFUSE → QUARANTINED + teardown; post-cooldown
            // readmission rebinds resume cursor to 0 so the C3 decideMode forces the snapshot.
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
        } else {
            switch (frame) {
                case EdgeFrame.CursorAck ack -> sessionCommands.add(s -> s.onCursorAck(ack.seq()));
                default -> teardownHook.accept(ErrorCode.PROTOCOL_VIOLATION,
                        "unexpected frame for state: " + frame.type());
            }
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
    // Demotion listener (runs on the session thread, inside the core's demote)
    // -----------------------------------------------------------------------

    private void onDemotionEvent(DemotionEvent event) {
        String id = governorIdentity;
        if (id == null) {
            return; // demotion before SUBSCRIBE cannot happen; defensive
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
                // Drain inbound session commands FIRST so every session mutation happens on THIS
                // thread (single-writer; the session is not thread-safe). A posted command is progress.
                boolean drainedCommand = false;
                Consumer<FanOutSessionCore> cmd;
                while ((cmd = sessionCommands.poll()) != null) {
                    cmd.accept(session);
                    drainedCommand = true;
                }

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
}
