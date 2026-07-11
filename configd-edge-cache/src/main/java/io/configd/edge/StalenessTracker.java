package io.configd.edge;

import io.configd.common.Clock;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;

import java.util.Objects;

/**
 * Measures edge-cache staleness against the <b>covered frontier</b>.
 * <p>
 * Staleness is {@code wall_now - frontier}, where the frontier is the latest point
 * in the commit stream the edge <em>knows</em> it has fully covered:
 * <pre>
 *   frontier = max( commit_ts(last applied notification),
 *                   server_now(last HEARTBEAT h where h.latestSeq == cursor) )
 *   staleness = wall_now - frontier
 * </pre>
 * State transitions:
 * <pre>
 *   CURRENT --(&gt;500ms)--&gt; STALE --(&gt;5s)--&gt; DEGRADED --(&gt;30s)--&gt; DISCONNECTED
 * </pre>
 *
 * <h2>Why frontier, not idle time</h2>
 * The prior implementation measured <em>idle time since the last update</em>
 * ({@code nanoTime() - lastUpdateNanos}). That is unsound on a quiet system: with no
 * commits for 30s - entirely normal for a configuration workload - a perfectly
 * fresh, fully-caught-up edge marches CURRENT to STALE to DEGRADED to DISCONNECTED and
 * triggers a needless re-bootstrap storm. The frontier fixes this: a heartbeat that
 * attests {@code latestSeq == cursor} ("there is nothing you have not seen as of my
 * clock T") advances the frontier, so an idle-but-heartbeating edge stays CURRENT
 * indefinitely. The idle-time proxy measurement is <b>deleted</b>, not kept alongside
 * (two staleness numbers is how dashboards lie).
 *
 * <h2>Heartbeat discipline</h2>
 * {@link #recordFrontier(long, long, long)} advances the frontier to
 * {@code serverNowMillis} <b>iff</b> {@code heartbeatLatestSeq == cursor}. When
 * {@code heartbeatLatestSeq > cursor} the edge is genuinely behind: the heartbeat is
 * the cursor-lag signal (recorded by the caller as {@code edge_fanout_cursor_lag}),
 * NOT a frontier advance - data age is real lag and must surface.
 *
 * <h2>Implausibility tripwire</h2>
 * A frontier in the future beyond the documented {@value #SKEW_ALLOWANCE_MS}ms NTP-skew
 * allowance, or a frontier that would jump <em>backwards</em>, is flagged on a dedicated
 * counter ({@value #IMPLAUSIBLE_METRIC}) and the offending sample is clamped - never
 * silently trusted. A skewed or lying clock must be visible.
 *
 * <h2>Thread safety</h2>
 * Frontier reads and writes use volatile semantics. Safe for concurrent reads with a
 * single writer (the {@link EdgeClientCore} apply thread). The implausibility counter
 * is a lock-free {@link MetricsRegistry.Counter}.
 */
public final class StalenessTracker {

    /** Staleness state thresholds in milliseconds. */
    private static final long STALE_THRESHOLD_MS = 500;
    private static final long DEGRADED_THRESHOLD_MS = 5_000;
    private static final long DISCONNECTED_THRESHOLD_MS = 30_000;

    /**
     * Documented NTP-skew allowance. A frontier up to this far in the future is tolerated
     * as clock skew (staleness clamped to 0); beyond it the implausibility tripwire fires.
     */
    static final long SKEW_ALLOWANCE_MS = 50;

    /**
     * Dedicated counter for implausible frontier samples. Named to match the metric
     * series {@code edge_staleness_implausible_total}; the {@link MetricsRegistry}
     * key is {@value} and the Prometheus exporter maps the dots to underscores and appends
     * {@code _total} for counters.
     */
    public static final String IMPLAUSIBLE_METRIC = "edge.staleness.implausible";

    /**
     * Staleness states, ordered by severity. Ordinals are load-bearing for the sim
     * determinism digest fold - do not reorder.
     */
    public enum State {
        /** Frontier is within 500ms of wall-now: the edge is up to date. */
        CURRENT,
        /** Frontier is &gt; 500ms behind wall-now. */
        STALE,
        /** Frontier is &gt; 5s behind wall-now. */
        DEGRADED,
        /** Frontier is &gt; 30s behind wall-now. */
        DISCONNECTED
    }

    private final Clock clock;

    /**
     * The covered frontier in wall-clock millis: {@code max(lastCommitTs, lastFrontierTs)}.
     * Volatile for cross-thread visibility. {@link Long#MIN_VALUE} marks "no frontier
     * yet" so the initial state is DISCONNECTED (the edge knows nothing).
     */
    private volatile long frontierMillis;

    /** The version (applied-mutation seq) of the last applied update. */
    private volatile long lastVersion;

    /**
     * Optional invariant monitor for staleness-bound violations. May be null - if
     * so, threshold violations are not reported through the monitor (the frontier
     * measurement itself is unaffected).
     */
    private final InvariantMonitor invariantMonitor;

    /**
     * Optional implausibility counter. May be null - if so, implausible samples are still
     * clamped, just not counted. Production wiring supplies the process
     * {@link MetricsRegistry}; tests can read {@link #implausibleCount()}.
     */
    private final MetricsRegistry.Counter implausibleCounter;

    /** Most recently observed leader version, for the staleness-bound diagnostic message. */
    private volatile long lastObservedRemoteVersion;

    /**
     * Creates a tracker using the given clock, initialized in {@link State#DISCONNECTED}
     * (no frontier known yet). No invariant monitor, no implausibility counter.
     */
    public StalenessTracker(Clock clock) {
        this(clock, null, null);
    }

    /**
     * Creates a tracker with an {@link InvariantMonitor} wired in for staleness-bound
     * checking. No implausibility counter (use the three-arg constructor for implausibility
     * metric wiring).
     */
    public StalenessTracker(Clock clock, InvariantMonitor invariantMonitor) {
        this(clock, invariantMonitor, null);
    }

    /**
     * Full constructor: clock, optional staleness-bound monitor, optional implausibility
     * counter.
     *
     * @param clock              the wall clock for the staleness measurement (non-null)
     * @param invariantMonitor   optional staleness-bound invariant monitor (may be null)
     * @param implausibleCounter optional implausible-frontier counter (may be null)
     */
    public StalenessTracker(Clock clock, InvariantMonitor invariantMonitor,
                            MetricsRegistry.Counter implausibleCounter) {
        Objects.requireNonNull(clock, "clock must not be null");
        this.clock = clock;
        this.invariantMonitor = invariantMonitor;
        this.implausibleCounter = implausibleCounter;
        // No frontier yet -> initial state is DISCONNECTED (the edge has covered nothing).
        this.frontierMillis = Long.MIN_VALUE;
        this.lastVersion = 0;
    }

    /**
     * Creates a tracker using the system clock.
     */
    public StalenessTracker() {
        this(Clock.system());
    }

    /**
     * Records a successfully applied update. Advances the covered frontier to
     * {@code commitTimestampMillis} (the leader's wall clock at commit/apply) - this is
     * the data-age term of the frontier. The frontier is monotonic: a
     * {@code commitTimestampMillis} that would move it backwards trips the implausibility
     * guard and is clamped.
     *
     * @param version               the applied-mutation seq of this update
     * @param commitTimestampMillis the leader commit timestamp (the covered-frontier clock)
     */
    public void recordUpdate(long version, long commitTimestampMillis) {
        this.lastVersion = version;
        advanceFrontier(commitTimestampMillis);
    }

    /**
     * Records an applied version WITHOUT a frontier advance. Used for snapshot cutover
     * when the snapshot carries no commit timestamp (snapshot bodies encode
     * {@code [seq][entries]} only - {@code ConfigSnapshot.timestamp() == 0} after
     * {@code EdgeSnapshotCodec.deserialize}): fabricating a frontier of 0 would either
     * regress the frontier or trip the implausibility counter on every legitimate
     * cutover, polluting the skew tripwire with false positives. The frontier instead
     * heals from the first post-snapshot NOTIFY commit timestamp or cursor-matched
     * HEARTBEAT.
     *
     * @param version the applied-mutation seq of the loaded snapshot
     */
    public void recordVersion(long version) {
        this.lastVersion = version;
    }

    /**
     * Records a HEARTBEAT-carried frontier. Advances the covered frontier to
     * {@code serverNowMillis} <b>iff</b> {@code heartbeatLatestSeq == cursor} - i.e. the
     * server attests "nothing you have not seen as of my clock T". When
     * {@code heartbeatLatestSeq > cursor} the edge is genuinely behind, so the frontier is
     * NOT advanced (data age is real lag); the heartbeat is then the cursor-lag signal,
     * which the caller records separately.
     * <p>
     * The cursor-match check is performed <b>inside</b> this method (the caller passes the
     * heartbeat's {@code latestSeq} and its own {@code cursor}) so the frontier law lives
     * in one place and cannot be bypassed by a mis-wired caller.
     *
     * @param heartbeatLatestSeq the heartbeat's {@code latestSeq} (server's highest seq)
     * @param cursor             the edge's current applied cursor
     * @param serverNowMillis    the heartbeat's {@code serverNowMillis} (server wall clock)
     * @return {@code true} if the heartbeat advanced the frontier (cursor matched),
     *         {@code false} if it was a cursor-lag signal (latestSeq &gt; cursor)
     */
    public boolean recordFrontier(long heartbeatLatestSeq, long cursor, long serverNowMillis) {
        if (heartbeatLatestSeq != cursor) {
            // latestSeq > cursor: genuinely behind - cursor-lag signal, NOT a frontier
            // advance. (latestSeq < cursor cannot happen on a monotonic stream, but if a
            // skewed/lagging relay sends it we likewise refuse to advance - the edge's own
            // applied frontier already dominates.)
            return false;
        }
        advanceFrontier(serverNowMillis);
        return true;
    }

    /**
     * Advances the frontier to {@code candidateMillis}, enforcing monotonicity and the
     * implausibility tripwire:
     * <ul>
     *   <li>A candidate in the future beyond {@value #SKEW_ALLOWANCE_MS}ms (negative
     *       staleness beyond NTP skew) is implausible - counted and clamped to wall-now.</li>
     *   <li>A candidate that would move the frontier backwards is implausible - counted and
     *       the frontier is held (never regresses).</li>
     * </ul>
     */
    private void advanceFrontier(long candidateMillis) {
        long now = clock.currentTimeMillis();
        long current = frontierMillis;

        // Future-frontier tripwire: a candidate beyond the skew allowance means a leader/
        // relay clock ahead of ours. Count it and clamp the frontier to now (staleness 0)
        // rather than trusting a negative staleness.
        if (candidateMillis > now + SKEW_ALLOWANCE_MS) {
            recordImplausible();
            candidateMillis = now;
        }

        // Regression tripwire: the covered frontier must be monotonic. A candidate below
        // the current frontier (e.g. a re-ordered heartbeat, or a commitTs from a skewed
        // node behind a clock we already advanced past) is implausible - count it and hold
        // the frontier. current == Long.MIN_VALUE is the "no frontier yet" sentinel and is
        // never a regression.
        if (current != Long.MIN_VALUE && candidateMillis < current) {
            recordImplausible();
            return;
        }

        frontierMillis = candidateMillis;
    }

    private void recordImplausible() {
        if (implausibleCounter != null) {
            implausibleCounter.increment();
        }
    }

    /**
     * Reports the most recently observed leader version (independent of whether we have
     * applied it). Used for the staleness-bound diagnostic message when {@link #isStale(long)}
     * fires.
     */
    public void observeRemoteVersion(long remoteVersion) {
        this.lastObservedRemoteVersion = remoteVersion;
    }

    /**
     * Returns {@code true} if the current frontier staleness exceeds {@code thresholdMs}.
     * Routes the decision through {@link InvariantMonitor} when one was supplied
     * so threshold violations increment
     * {@code configd.invariant.violation.staleness_bound}.
     *
     * @param thresholdMs the staleness upper bound (usually {@code STALE_THRESHOLD_MS})
     * @return true if {@code stalenessMs() > thresholdMs}
     */
    public boolean isStale(long thresholdMs) {
        long staleMs = stalenessMs();
        boolean stale = staleMs > thresholdMs;
        if (stale && invariantMonitor != null) {
            invariantMonitor.assertStalenessBound(
                    lastVersion, lastObservedRemoteVersion, staleMs, thresholdMs);
        }
        return stale;
    }

    /**
     * Returns the current staleness state based on the covered frontier.
     */
    public State currentState() {
        long staleMs = stalenessMs();
        if (staleMs > DISCONNECTED_THRESHOLD_MS) {
            return State.DISCONNECTED;
        }
        if (staleMs > DEGRADED_THRESHOLD_MS) {
            return State.DEGRADED;
        }
        if (staleMs > STALE_THRESHOLD_MS) {
            return State.STALE;
        }
        return State.CURRENT;
    }

    /**
     * Returns {@code wall_now - frontier} in milliseconds. Before any frontier is known
     * the edge has covered nothing, so this returns a value past the DISCONNECTED threshold
     * (initial state DISCONNECTED). The result is never negative: a frontier ahead of
     * wall-now (within the skew allowance) clamps to 0.
     */
    public long stalenessMs() {
        long frontier = frontierMillis;
        if (frontier == Long.MIN_VALUE) {
            // No frontier yet -> maximally stale (DISCONNECTED until the first update/frontier).
            return DISCONNECTED_THRESHOLD_MS + 1;
        }
        long staleMs = clock.currentTimeMillis() - frontier;
        return staleMs < 0 ? 0 : staleMs;
    }

    /**
     * Returns the version of the last applied update.
     */
    public long lastVersion() {
        return lastVersion;
    }

    /**
     * Returns the number of implausible frontier samples observed.
     * Reads the wired counter; 0 when no counter was supplied.
     */
    public long implausibleCount() {
        return implausibleCounter == null ? 0L : implausibleCounter.get();
    }
}
