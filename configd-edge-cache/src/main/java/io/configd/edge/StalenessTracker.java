package io.configd.edge;

import io.configd.common.Clock;
import io.configd.observability.InvariantMonitor;

import java.util.Objects;

/**
 * Monitors staleness of the local edge store relative to the control plane.
 * <p>
 * State transitions based on time since last successful update:
 * <pre>
 *   CURRENT ──(>500ms)──► STALE ──(>5s)──► DEGRADED ──(>30s)──► DISCONNECTED
 * </pre>
 * Each call to {@link #recordUpdate} resets the state to {@link State#CURRENT}.
 * <p>
 * Thread safety: reads and writes to {@code lastUpdateNanos} and
 * {@code lastVersion} use volatile semantics. The tracker is safe for
 * concurrent reads with a single writer (the delta applier thread).
 */
public final class StalenessTracker {

    /** Staleness state thresholds in milliseconds. */
    private static final long STALE_THRESHOLD_MS = 500;
    private static final long DEGRADED_THRESHOLD_MS = 5_000;
    private static final long DISCONNECTED_THRESHOLD_MS = 30_000;

    /**
     * Staleness states, ordered by severity.
     */
    public enum State {
        /** Store is up to date with the control plane. */
        CURRENT,
        /** Last update was received > 500ms ago. */
        STALE,
        /** Last update was received > 5s ago. */
        DEGRADED,
        /** Last update was received > 30s ago. */
        DISCONNECTED
    }

    private final Clock clock;

    /**
     * Monotonic nanoTime of the last successful update.
     * Volatile for cross-thread visibility.
     */
    private volatile long lastUpdateNanos;

    /** The version number of the last applied update. */
    private volatile long lastVersion;

    /**
     * Optional invariant monitor (F-0073) for INV-S1 staleness-bound
     * violations. May be null — if so, violations are not reported.
     */
    private final InvariantMonitor invariantMonitor;

    /** Most recently observed leader version, for diagnostic messages. */
    private volatile long lastObservedRemoteVersion;

    /**
     * Creates a tracker using the given clock, initialized in
     * {@link State#DISCONNECTED} state (no updates received yet).
     */
    public StalenessTracker(Clock clock) {
        this(clock, null);
    }

    /**
     * Creates a tracker with an {@link InvariantMonitor} wired in. When the
     * observed staleness exceeds {@code STALE_THRESHOLD_MS}, the monitor's
     * {@code staleness_bound} invariant is reported (F-0073 / INV-S1).
     */
    public StalenessTracker(Clock clock, InvariantMonitor invariantMonitor) {
        Objects.requireNonNull(clock, "clock must not be null");
        this.clock = clock;
        this.invariantMonitor = invariantMonitor;
        // Initialize to a very old time so initial state is DISCONNECTED
        this.lastUpdateNanos = clock.nanoTime() - (DISCONNECTED_THRESHOLD_MS + 1) * 1_000_000L;
        this.lastVersion = 0;
    }

    /**
     * Creates a tracker using the system clock.
     */
    public StalenessTracker() {
        this(Clock.system());
    }

    /**
     * Records a successful update from the control plane.
     * Resets the staleness state to {@link State#CURRENT}.
     *
     * @param version   the version number of the applied update
     * @param timestamp the HLC timestamp of the update (informational)
     */
    public void recordUpdate(long version, long timestamp) {
        this.lastVersion = version;
        this.lastUpdateNanos = clock.nanoTime();
    }

    /**
     * Reports the most recently observed leader version (independent of
     * whether we have successfully applied it). Used for the INV-S1
     * diagnostic message when {@link #isStale(long)} fires.
     */
    public void observeRemoteVersion(long remoteVersion) {
        this.lastObservedRemoteVersion = remoteVersion;
    }

    /**
     * Returns {@code true} if the tracker has been idle for longer than
     * {@code thresholdMs}. Routes the decision through {@link InvariantMonitor}
     * when one was supplied (F-0073 / INV-S1), so threshold violations
     * increment {@code configd.invariant.violation.staleness_bound}.
     *
     * @param thresholdMs the staleness upper bound (usually {@code STALE_THRESHOLD_MS})
     * @return true if the elapsed time since the last update exceeds {@code thresholdMs}
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
     * Returns the current staleness state based on elapsed time since
     * the last update.
     */
    public State currentState() {
        long elapsedMs = stalenessMs();
        if (elapsedMs > DISCONNECTED_THRESHOLD_MS) {
            return State.DISCONNECTED;
        }
        if (elapsedMs > DEGRADED_THRESHOLD_MS) {
            return State.DEGRADED;
        }
        if (elapsedMs > STALE_THRESHOLD_MS) {
            return State.STALE;
        }
        return State.CURRENT;
    }

    /**
     * Returns the number of milliseconds since the last successful update.
     */
    public long stalenessMs() {
        long now = clock.nanoTime();
        long elapsedNanos = now - lastUpdateNanos;
        return elapsedNanos / 1_000_000L;
    }

    /**
     * Returns the version of the last applied update.
     */
    public long lastVersion() {
        return lastVersion;
    }
}
