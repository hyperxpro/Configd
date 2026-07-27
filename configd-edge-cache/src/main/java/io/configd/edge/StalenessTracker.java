package io.configd.edge;

import io.configd.common.Clock;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;

import java.util.Objects;

/**
 * Frontier staleness: wall_now - frontier (max of commit_ts and cursor-matched heartbeat).
 * Transitions: CURRENT(500ms) → STALE(5s) → DEGRADED(30s) → DISCONNECTED.
 * Implausibility tripwire for clock skew / backward frontier. Volatile single-writer.
 */
public final class StalenessTracker {

    private static final long STALE_THRESHOLD_MS = 500;
    private static final long DEGRADED_THRESHOLD_MS = 5_000;
    private static final long DISCONNECTED_THRESHOLD_MS = 30_000;

    // NTP-skew allowance: future-frontier beyond this fires implausibility tripwire.
    static final long SKEW_ALLOWANCE_MS = 50;

    // Implausible frontier samples counter (metric: edge_staleness_implausible_total).
    public static final String IMPLAUSIBLE_METRIC = "edge.staleness.implausible";

    // Ordinals are load-bearing for sim determinism — do not reorder.
    public enum State {
        CURRENT,
        STALE,
        DEGRADED,
        DISCONNECTED
    }

    private final Clock clock;

    // Frontier in wall-clock millis: max(commitTs, heartbeat). Long.MIN_VALUE = no frontier yet.
    private volatile long frontierMillis;

    private volatile long lastVersion;
    private final InvariantMonitor invariantMonitor;
    private final MetricsRegistry.Counter implausibleCounter;
    private volatile long lastObservedRemoteVersion;

    public StalenessTracker(Clock clock) {
        this(clock, null, null);
    }

    public StalenessTracker(Clock clock, InvariantMonitor invariantMonitor) {
        this(clock, invariantMonitor, null);
    }

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

    public StalenessTracker() {
        this(Clock.system());
    }

    public void recordUpdate(long version, long commitTimestampMillis) {
        this.lastVersion = version;
        advanceFrontier(commitTimestampMillis);
    }

    public void recordVersion(long version) {
        this.lastVersion = version;
    }

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

    public void observeRemoteVersion(long remoteVersion) {
        this.lastObservedRemoteVersion = remoteVersion;
    }

    public boolean isStale(long thresholdMs) {
        long staleMs = stalenessMs();
        boolean stale = staleMs > thresholdMs;
        if (stale && invariantMonitor != null) {
            invariantMonitor.assertStalenessBound(
                    lastVersion, lastObservedRemoteVersion, staleMs, thresholdMs);
        }
        return stale;
    }

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

    public long stalenessMs() {
        long frontier = frontierMillis;
        if (frontier == Long.MIN_VALUE) {
            // No frontier yet -> maximally stale (DISCONNECTED until the first update/frontier).
            return DISCONNECTED_THRESHOLD_MS + 1;
        }
        long staleMs = clock.currentTimeMillis() - frontier;
        return staleMs < 0 ? 0 : staleMs;
    }

    public long lastVersion() {
        return lastVersion;
    }

    public long implausibleCount() {
        return implausibleCounter == null ? 0L : implausibleCounter.get();
    }
}
