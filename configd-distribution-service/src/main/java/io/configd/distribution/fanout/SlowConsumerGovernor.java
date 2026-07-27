package io.configd.distribution.fanout;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * The slow-consumer governance layer: turns per-session distress signals (queue-warn
 * pressure, {@link DemotionEvent}s, CURSOR_ACK progress) into per-<b>identity</b> state -
 * the mTLS cert identity, not the connection, so a reconnect storm cannot dodge the policy.
 *
 * <h2>State machine</h2>
 * <pre>
 * HEALTHY -- queue >= warn sustained queueWarnWindowMs --&gt; SLOW   (warn metric + log; still streaming)
 * SLOW -- ack progress drains the queue below warn --&gt; HEALTHY
 * any  -- demotion (overflow / ack-lag / gap / transport) --&gt; CATCHUP   (counted)
 * CATCHUP -- snapshot applied + CURSOR_ACK progress --&gt; HEALTHY
 * any  -- demoteLimit distress demotions (or gapDemoteLimit GAP demotions)
 *         within demoteWindowMs --&gt; QUARANTINED   (caller disconnects: ERROR_CLOSE code 8 + socket close;
 *                                                  SUBSCRIBEs refused for quarantineCooldownMs, then
 *                                                  readmitted with snapshot-first forced)
 * QUARANTINED -- quarantineLimit quarantines within unhealthyWindowMs --&gt; UNHEALTHY
 *                                                (alert-grade; refused until unhealthyCooldownMs
 *                                                 elapses or an operator reset - the cooldown alone
 *                                                 is a sufficient exit)
 * </pre>
 *
 * <h2>Reason weighting</h2>
 * GAP demotions are counted <b>separately</b> from distress demotions: a GAP is frequently
 * a network/eviction artifact (a lossy WAN, a ring lap) that the catch-up path heals - an
 * edge that gaps and recovers repeatedly must not walk to QUARANTINED as readily as one
 * that is genuinely too slow to ack. {@code gapDemoteLimit} (default 10 vs
 * {@code demoteLimit} 3, same window) remains the backstop for a real gap loop.
 *
 * <h2>Determinism and threading</h2>
 * No wall-clock reads, no threads, no sleeps: time enters every method as {@code nowMillis}
 * (the caller's {@code Clock} / sim clock), so the governor is fully deterministic - the
 * same testability shape as {@link FanOutSessionCore}. Unlike the session core it is shared
 * across connections, so its methods are {@code synchronized}; every call is a
 * policy-frequency event (a queue-pressure edge, a demotion, a subscribe, an ack advance,
 * a {@code <= 1 Hz} evaluation) - never per-frame work, so the lock is uncontended by
 * design and nothing here runs on the publish path.
 *
 * <h2>Observability</h2>
 * Every transition fires a {@link FanOutSessionMetrics} callback and a structured
 * {@link TransitionEvent} - emitted to the optional listener and logged as a single
 * structured INFO line with the cursor evidence (identity, from, to, reason, cursor,
 * lastAckedSeq, window counts). Transitions are rare by construction; logs are never
 * per-frame.
 */
public final class SlowConsumerGovernor {

    private static final Logger LOG = Logger.getLogger(SlowConsumerGovernor.class.getName());

    public enum ConsumerState {
        /** Keeping up (or unknown - an untracked identity is healthy by definition). */
        HEALTHY,
        SLOW,
        CATCHUP,
        QUARANTINED,
        UNHEALTHY
    }

    public enum AdmissionDecision {
        ALLOW,
        /** Admit, but force re-bootstrap: the resume cursor must be rebound to 0 so the
         *  {@code decideMode} cursor-0 rule yields SNAPSHOT_FIRST. */
        ALLOW_FORCE_SNAPSHOT,
        /** Refuse: close with {@code ErrorCode.QUARANTINED} (wire code 8). */
        REFUSE
    }

    /**
     * The admission result: the ruling, the identity's state at decision time, and - for
     * refusals - how long until the cooldown readmits (diagnostic, for the close message).
     */
    public record Admission(AdmissionDecision decision, ConsumerState state, long cooldownRemainingMs) { }

    /**
     * The structured per-transition event; every transition emits one. Cursor evidence is
     * carried where the triggering signal had it (demotions); {@code -1} where the signal
     * is cursor-free.
     *
     * @param identity                 the subscriber identity (mTLS cert principal)
     * @param from                     the state before the transition
     * @param to                       the state after the transition
     * @param reason                   the transition reason label
     * @param cursor                   session cursor at the triggering signal, or -1
     * @param lastAckedSeq             last acked seq at the triggering signal, or -1
     * @param distressDemotionsInWindow distress demotions inside {@code demoteWindowMs}
     * @param gapDemotionsInWindow     GAP demotions inside {@code demoteWindowMs}
     * @param quarantinesInWindow      quarantines inside {@code unhealthyWindowMs}
     * @param atMillis                 the caller-supplied time of the transition
     */
    public record TransitionEvent(String identity, ConsumerState from, ConsumerState to,
                                  String reason, long cursor, long lastAckedSeq,
                                  int distressDemotionsInWindow, int gapDemotionsInWindow,
                                  int quarantinesInWindow, long atMillis) { }

    // Transition reason labels (closed set; tests pin them).
    public static final String REASON_QUEUE_WARN_SUSTAINED = "queue_warn_sustained";
    public static final String REASON_ACK_PROGRESS = "ack_progress";
    public static final String REASON_CATCHUP_RESOLVED = "catchup_resolved";
    public static final String REASON_DEMOTE_LIMIT = "demote_limit";
    public static final String REASON_GAP_DEMOTE_LIMIT = "gap_demote_limit";
    public static final String REASON_REPEAT_QUARANTINE = "repeat_quarantine";
    public static final String REASON_READMITTED_QUARANTINE = "readmitted_after_quarantine_cooldown";
    public static final String REASON_READMITTED_UNHEALTHY = "readmitted_after_unhealthy_cooldown";
    public static final String REASON_OPERATOR_RESET = "operator_reset";

    private final SlowConsumerPolicyConfig config;
    private final FanOutSessionMetrics metrics;
    private final Consumer<TransitionEvent> transitionListener;

    /**
     * Per-identity records, access-ordered so the least-recently-touched HEALTHY identity
     * is evicted first when {@code maxTrackedIdentities} is exceeded. A
     * non-HEALTHY record is never evicted - forgetting a quarantine would be a policy
     * escape - so the map may exceed the bound by the (real-world-bounded) number of
     * simultaneously distressed identities; the eviction walk skips past them to the
     * first HEALTHY entry, so distressed records can never dam up healthy growth.
     */
    private final LinkedHashMap<String, ConsumerRecord> consumers;

    /** Running per-state tallies for the {@code edge_fanout_consumer_state_*} gauges. */
    private final int[] stateCounts = new int[ConsumerState.values().length];

    public SlowConsumerGovernor(SlowConsumerPolicyConfig config, FanOutSessionMetrics metrics) {
        this(config, metrics, null);
    }

    /**
     * @param transitionListener optional structured-event listener fired on every
     *                           transition (in addition to the structured INFO log);
     *                           may be null
     */
    public SlowConsumerGovernor(SlowConsumerPolicyConfig config, FanOutSessionMetrics metrics,
                                Consumer<TransitionEvent> transitionListener) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.transitionListener = transitionListener;
        this.consumers = new LinkedHashMap<>(16, 0.75f, true);
    }

    public SlowConsumerPolicyConfig config() {
        return config;
    }


    /**
     * Queue-pressure <b>edge</b>: the session's unacked-frame depth crossed the
     * {@code queueWarnPct} threshold ({@code above = true}) or dropped back below it
     * ({@code above = false}). Level-tolerant (repeating the same level is a no-op).
     * A drop below the threshold while SLOW is the "ack progress resumes" exit - the
     * queue only drains via CURSOR_ACK, so below-warn IS resumed progress.
     *
     * @param identity     the subscriber identity
     * @param above        whether the queue is at/above the warn threshold
     * @param cursor       the session cursor (evidence; -1 if unknown)
     * @param lastAckedSeq the session's last acked seq (evidence; -1 if unknown)
     * @param nowMillis    the caller's clock
     */
    public synchronized void onQueuePressure(String identity, boolean above,
                                             long cursor, long lastAckedSeq, long nowMillis) {
        ConsumerRecord c = record(identity);
        if (above) {
            if (c.queueWarnSinceMillis < 0) {
                c.queueWarnSinceMillis = nowMillis;
            }
            maybePromoteSlow(identity, c, cursor, lastAckedSeq, nowMillis);
        } else {
            c.queueWarnSinceMillis = -1;
            if (c.state == ConsumerState.SLOW) {
                transition(identity, c, ConsumerState.HEALTHY, REASON_ACK_PROGRESS,
                        cursor, lastAckedSeq, nowMillis);
            }
        }
    }

    /**
     * Time-driven evaluation (the only transition that needs elapsed time with no other
     * event): promotes HEALTHY-to-SLOW once the queue has been at/above warn for
     * {@code queueWarnWindowMs}. Called by the server at a {@code <= 1 Hz} cadence while
     * the queue is warned, and by the sim driver per tick.
     *
     * @return the identity's state after evaluation
     */
    public synchronized ConsumerState evaluate(String identity, long nowMillis) {
        ConsumerRecord c = consumers.get(identity);
        if (c == null) {
            return ConsumerState.HEALTHY;
        }
        maybePromoteSlow(identity, c, -1, -1, nowMillis);
        return c.state;
    }

    /**
     * A demotion for this identity (the {@link FanOutSessionCore} demotion-listener seam).
     * Records it in the reason-weighted sliding window, transitions to CATCHUP, and - when
     * a window limit trips - to QUARANTINED (or UNHEALTHY on the
     * {@code quarantineLimit}-th quarantine inside {@code unhealthyWindowMs}).
     *
     * <p>The caller (the server's connection / the sim driver) is responsible for the
     * disconnect when this returns QUARANTINED/UNHEALTHY: an {@code ERROR_CLOSE} with
     * {@code ErrorCode.QUARANTINED} (wire code 8) and a socket close.
     *
     * @return the identity's state after the demotion is applied
     */
    public synchronized ConsumerState onDemotion(String identity, DemotionEvent event, long nowMillis) {
        Objects.requireNonNull(event, "event must not be null");
        ConsumerRecord c = record(identity);
        if (c.state == ConsumerState.QUARANTINED || c.state == ConsumerState.UNHEALTHY) {
            // Already disconnected by policy; a straggler demotion from a dying session
            // must not double-count.
            return c.state;
        }
        boolean gap = DemotionEvent.REASON_GAP.equals(event.reason());
        Deque<Long> windowed = gap ? c.gapDemotions : c.distressDemotions;
        windowed.addLast(nowMillis);
        prune(windowed, nowMillis, config.demoteWindowMs());

        c.queueWarnSinceMillis = -1; // the demotion supersedes any pending warn window
        if (c.state != ConsumerState.CATCHUP) {
            transition(identity, c, ConsumerState.CATCHUP, event.reason(),
                    event.cursor(), event.lastAckedSeq(), nowMillis);
        }

        int limit = gap ? config.gapDemoteLimit() : config.demoteLimit();
        if (windowed.size() >= limit) {
            quarantine(identity, c, gap ? REASON_GAP_DEMOTE_LIMIT : REASON_DEMOTE_LIMIT,
                    event.cursor(), event.lastAckedSeq(), nowMillis);
        }
        return c.state;
    }

    /**
     * CURSOR_ACK progress for this identity (the session's {@code lastAckedSeq} advanced).
     * Resolves CATCHUP-to-HEALTHY - the edge demonstrably applied and acknowledged past its
     * re-bootstrap. A premature resolve (an ack for pre-demotion frames) is self-correcting:
     * a still-stuck consumer re-demotes via the ack-lag path and is re-counted.
     */
    public synchronized void onAckProgress(String identity, long cursor, long lastAckedSeq,
                                           long nowMillis) {
        ConsumerRecord c = consumers.get(identity);
        if (c != null && c.state == ConsumerState.CATCHUP) {
            transition(identity, c, ConsumerState.HEALTHY, REASON_CATCHUP_RESOLVED,
                    cursor, lastAckedSeq, nowMillis);
        }
    }


    /**
     * The SUBSCRIBE-time admission ruling for {@code identity}:
     * <ul>
     *   <li>QUARANTINED/UNHEALTHY inside its cooldown -> {@link AdmissionDecision#REFUSE}
     *       ({@code edge_fanout_reconnects_refused_total} + a structured log - a
     *       permanently-flapping edge is observable, never silently dark);</li>
     *   <li>QUARANTINED/UNHEALTHY past its cooldown ->
     *       {@link AdmissionDecision#ALLOW_FORCE_SNAPSHOT} (readmitted into CATCHUP; the
     *       caller rebinds the resume cursor to 0 so {@code decideMode} forces the snapshot
     *       re-bootstrap). The cooldown alone is a sufficient exit - operator reset is
     *       additional, not required;</li>
     *   <li>anything else -> {@link AdmissionDecision#ALLOW}.</li>
     * </ul>
     */
    public synchronized Admission admit(String identity, long nowMillis) {
        ConsumerRecord c = consumers.get(identity);
        if (c == null) {
            return new Admission(AdmissionDecision.ALLOW, ConsumerState.HEALTHY, 0L);
        }
        switch (c.state) {
            case QUARANTINED -> {
                long remaining = config.quarantineCooldownMs() - (nowMillis - c.stateEnteredMillis);
                if (remaining > 0) {
                    return refuse(identity, c, remaining, nowMillis);
                }
                return readmit(identity, c, REASON_READMITTED_QUARANTINE, nowMillis);
            }
            case UNHEALTHY -> {
                long remaining = config.unhealthyCooldownMs() - (nowMillis - c.stateEnteredMillis);
                if (remaining > 0) {
                    return refuse(identity, c, remaining, nowMillis);
                }
                return readmit(identity, c, REASON_READMITTED_UNHEALTHY, nowMillis);
            }
            default -> {
                // A fresh connection starts with an empty queue; any pending warn window
                // belonged to the previous connection.
                c.queueWarnSinceMillis = -1;
                return new Admission(AdmissionDecision.ALLOW, c.state, 0L);
            }
        }
    }

    /**
     * Operator reset: full amnesty for {@code identity} - record dropped, windows cleared.
     * An <b>additional</b> recovery path; the cooldowns alone readmit without it.
     */
    public synchronized void operatorReset(String identity, long nowMillis) {
        ConsumerRecord c = consumers.remove(identity);
        if (c != null) {
            stateCounts[c.state.ordinal()]--;
            publishStateCounts();
            emit(new TransitionEvent(identity, c.state, ConsumerState.HEALTHY,
                    REASON_OPERATOR_RESET, -1, -1, 0, 0, 0, nowMillis));
        }
    }


    /** The identity's current state (HEALTHY if untracked). */
    public synchronized ConsumerState state(String identity) {
        ConsumerRecord c = consumers.get(identity);
        return (c == null) ? ConsumerState.HEALTHY : c.state;
    }

    public synchronized int trackedIdentities() {
        return consumers.size();
    }


    private ConsumerRecord record(String identity) {
        Objects.requireNonNull(identity, "identity must not be null");
        ConsumerRecord c = consumers.get(identity);
        if (c == null) {
            evictIfAtBound(); // BEFORE the insert, so the new record is never the victim
            c = new ConsumerRecord();
            consumers.put(identity, c);
            stateCounts[ConsumerState.HEALTHY.ordinal()]++;
            publishStateCounts();
        }
        return c;
    }

    /**
     * Enforces {@code maxTrackedIdentities}: before a new identity is
     * inserted at the bound, evicts the least-recently-touched HEALTHY record, skipping
     * (never evicting) distressed records. The walk cost is bounded by the number of
     * consecutive distressed records at the access-order head - itself bounded by the
     * count of genuinely distressed identities - and runs only on a new-identity insert
     * at the bound, never on the per-event path.
     */
    private void evictIfAtBound() {
        if (consumers.size() < config.maxTrackedIdentities()) {
            return;
        }
        var it = consumers.entrySet().iterator();
        while (it.hasNext()) {
            ConsumerRecord candidate = it.next().getValue();
            if (candidate.state == ConsumerState.HEALTHY) {
                it.remove();
                stateCounts[ConsumerState.HEALTHY.ordinal()]--;
                return;
            }
        }
        // Every tracked identity is distressed: nothing evictable - the documented
        // honest overflow (bounded by real distinct certs in distress).
    }

    private void maybePromoteSlow(String identity, ConsumerRecord c,
                                  long cursor, long lastAckedSeq, long nowMillis) {
        if (c.state == ConsumerState.HEALTHY
                && c.queueWarnSinceMillis >= 0
                && nowMillis - c.queueWarnSinceMillis >= config.queueWarnWindowMs()) {
            metrics.onSlowTransition();
            transition(identity, c, ConsumerState.SLOW, REASON_QUEUE_WARN_SUSTAINED,
                    cursor, lastAckedSeq, nowMillis);
        }
    }

    private void quarantine(String identity, ConsumerRecord c, String reason,
                            long cursor, long lastAckedSeq, long nowMillis) {
        c.quarantines.addLast(nowMillis);
        prune(c.quarantines, nowMillis, config.unhealthyWindowMs());
        metrics.onQuarantine();

        ConsumerState target;
        if (c.quarantines.size() >= config.quarantineLimit()) {
            target = ConsumerState.UNHEALTHY;
            reason = REASON_REPEAT_QUARANTINE;
            metrics.onUnhealthy();
        } else {
            target = ConsumerState.QUARANTINED;
        }
        // Transition FIRST so the structured event carries the window counts that
        // actually tripped the limit (cursor evidence)...
        transition(identity, c, target, reason, cursor, lastAckedSeq, nowMillis);
        // ...then start the demotion windows fresh: the cooldown + forced re-bootstrap is
        // the clean slate; pre-quarantine demotions must not double-trip the next ladder.
        c.distressDemotions.clear();
        c.gapDemotions.clear();
        c.queueWarnSinceMillis = -1;
    }

    private Admission refuse(String identity, ConsumerRecord c, long remainingMs, long nowMillis) {
        metrics.onReconnectRefused();
        // Structured refusal log - a flapping edge in cooldown is observable, not silently
        // dark. Refusals are reconnect-paced (the edge's bounded backoff), never per-frame.
        LOG.info(() -> "edge_fanout_admission_refused identity=" + identity
                + " state=" + c.state + " cooldownRemainingMs=" + remainingMs
                + " atMillis=" + nowMillis);
        return new Admission(AdmissionDecision.REFUSE, c.state, remainingMs);
    }

    private Admission readmit(String identity, ConsumerRecord c, String reason, long nowMillis) {
        metrics.onReadmission();
        // Readmitted into CATCHUP: the forced snapshot re-bootstrap is in flight; the
        // CURSOR_ACK after the snapshot applies resolves it to HEALTHY.
        transition(identity, c, ConsumerState.CATCHUP, reason, -1, -1, nowMillis);
        return new Admission(AdmissionDecision.ALLOW_FORCE_SNAPSHOT, c.state, 0L);
    }

    private void transition(String identity, ConsumerRecord c, ConsumerState to, String reason,
                            long cursor, long lastAckedSeq, long nowMillis) {
        ConsumerState from = c.state;
        stateCounts[from.ordinal()]--;
        stateCounts[to.ordinal()]++;
        c.state = to;
        c.stateEnteredMillis = nowMillis;
        publishStateCounts();
        emit(new TransitionEvent(identity, from, to, reason, cursor, lastAckedSeq,
                c.distressDemotions.size(), c.gapDemotions.size(), c.quarantines.size(),
                nowMillis));
    }

    private void emit(TransitionEvent event) {
        // The structured log line; transitions are policy-rare so INFO is appropriate.
        LOG.info(() -> "edge_fanout_consumer_transition identity=" + event.identity()
                + " from=" + event.from() + " to=" + event.to()
                + " reason=" + event.reason()
                + " cursor=" + event.cursor() + " lastAckedSeq=" + event.lastAckedSeq()
                + " distressDemotionsInWindow=" + event.distressDemotionsInWindow()
                + " gapDemotionsInWindow=" + event.gapDemotionsInWindow()
                + " quarantinesInWindow=" + event.quarantinesInWindow()
                + " atMillis=" + event.atMillis());
        if (transitionListener != null) {
            transitionListener.accept(event);
        }
    }

    private void publishStateCounts() {
        metrics.onConsumerStates(
                stateCounts[ConsumerState.HEALTHY.ordinal()],
                stateCounts[ConsumerState.SLOW.ordinal()],
                stateCounts[ConsumerState.CATCHUP.ordinal()],
                stateCounts[ConsumerState.QUARANTINED.ordinal()],
                stateCounts[ConsumerState.UNHEALTHY.ordinal()]);
    }

    private static void prune(Deque<Long> timestamps, long nowMillis, long windowMs) {
        while (!timestamps.isEmpty() && nowMillis - timestamps.peekFirst() > windowMs) {
            timestamps.pollFirst();
        }
    }

    /**
     * Per-identity state. Deques are bounded by construction: each limit clears its deque
     * when it trips, and pruning evicts entries older than the window on every touch.
     */
    private static final class ConsumerRecord {
        ConsumerState state = ConsumerState.HEALTHY;
        /** When the state was entered (cooldown anchor for QUARANTINED/UNHEALTHY). */
        long stateEnteredMillis;
        /** When the queue went to/above the warn threshold; -1 = below. */
        long queueWarnSinceMillis = -1;
        /** Distress (ack_lag / queue_overflow / transport_block) demotion timestamps. */
        final Deque<Long> distressDemotions = new ArrayDeque<>(4);
        /** GAP demotion timestamps (weighted separately from distress demotions). */
        final Deque<Long> gapDemotions = new ArrayDeque<>(4);
        final Deque<Long> quarantines = new ArrayDeque<>(4);
    }
}
