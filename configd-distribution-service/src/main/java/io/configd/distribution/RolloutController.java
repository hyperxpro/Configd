package io.configd.distribution;

import io.configd.common.Clock;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Progressive rollout stage management (ADR-0008).
 * <p>
 * Config changes are rolled out in stages, with health gates between each:
 * <pre>
 *   CANARY ──► ONE_PERCENT ──► TEN_PERCENT ──► FIFTY_PERCENT ──► FULL
 * </pre>
 * Each stage has a minimum soak time. Advancement requires:
 * <ol>
 *   <li>Minimum soak time elapsed</li>
 *   <li>Health checks passing (no error rate spike)</li>
 *   <li>No manual hold placed on the rollout</li>
 * </ol>
 * <p>
 * A rollout can be paused, resumed, or rolled back at any stage. Emergency
 * rollouts ({@link RolloutPolicy#IMMEDIATE}) bypass all gates.
 * <p>
 * Thread safety: designed for single-threaded access from the distribution
 * service I/O thread.
 */
public final class RolloutController {

    /**
     * Rollout stages, in order of increasing reach.
     */
    public enum Stage {
        CANARY(0.001),         // ~0.1% of nodes
        ONE_PERCENT(0.01),
        TEN_PERCENT(0.10),
        FIFTY_PERCENT(0.50),
        FULL(1.0);

        private final double fraction;

        Stage(double fraction) {
            this.fraction = fraction;
        }

        /** The fraction of edge nodes that should receive the change. */
        public double fraction() {
            return fraction;
        }
    }

    /**
     * Rollout policies.
     */
    public enum RolloutPolicy {
        /** Normal progressive rollout through all stages. */
        PROGRESSIVE,
        /** Skip all gates — deploy immediately to 100%. Requires elevated ACL. */
        IMMEDIATE
    }

    /**
     * Current state of a rollout.
     */
    public enum RolloutState {
        IN_PROGRESS,
        PAUSED,
        COMPLETED,
        ROLLED_BACK
    }

    /**
     * Status of an individual rollout.
     */
    public record RolloutStatus(
            String rolloutId,
            Stage currentStage,
            RolloutState state,
            RolloutPolicy policy,
            long stageEnteredAtMs,
            long soakTimeMs,
            boolean healthPassing
    ) {
        /** Returns true if the rollout can advance to the next stage. */
        public boolean canAdvance(long currentTimeMs) {
            if (state != RolloutState.IN_PROGRESS) return false;
            if (currentStage == Stage.FULL) return false;
            if (!healthPassing) return false;
            long elapsed = currentTimeMs - stageEnteredAtMs;
            return elapsed >= soakTimeMs;
        }
    }

    private final Clock clock;
    private final Map<Stage, Long> soakTimes;
    private final Map<String, RolloutTracker> rollouts;

    /**
     * Creates a rollout controller with default soak times.
     * <p>
     * Default soak times:
     * <ul>
     *   <li>CANARY: 60 seconds</li>
     *   <li>ONE_PERCENT: 120 seconds</li>
     *   <li>TEN_PERCENT: 300 seconds</li>
     *   <li>FIFTY_PERCENT: 600 seconds</li>
     *   <li>FULL: 0 (terminal stage)</li>
     * </ul>
     */
    public RolloutController(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.soakTimes = new EnumMap<>(Stage.class);
        this.soakTimes.put(Stage.CANARY, 60_000L);
        this.soakTimes.put(Stage.ONE_PERCENT, 120_000L);
        this.soakTimes.put(Stage.TEN_PERCENT, 300_000L);
        this.soakTimes.put(Stage.FIFTY_PERCENT, 600_000L);
        this.soakTimes.put(Stage.FULL, 0L);
        this.rollouts = new HashMap<>();
    }

    /**
     * Overrides the soak time for a specific stage.
     */
    public void setSoakTime(Stage stage, long soakTimeMs) {
        soakTimes.put(stage, soakTimeMs);
    }

    /**
     * Starts a new rollout.
     *
     * @param rolloutId unique identifier for this rollout
     * @param policy    the rollout policy
     * @return the initial rollout status
     */
    public RolloutStatus startRollout(String rolloutId, RolloutPolicy policy) {
        Objects.requireNonNull(rolloutId, "rolloutId must not be null");
        Objects.requireNonNull(policy, "policy must not be null");

        long now = clock.currentTimeMillis();
        Stage initialStage = (policy == RolloutPolicy.IMMEDIATE) ? Stage.FULL : Stage.CANARY;

        RolloutTracker tracker = new RolloutTracker();
        tracker.currentStage = initialStage;
        tracker.state = (initialStage == Stage.FULL)
                ? RolloutState.COMPLETED : RolloutState.IN_PROGRESS;
        tracker.policy = policy;
        tracker.stageEnteredAtMs = now;
        tracker.healthPassing = true;

        rollouts.put(rolloutId, tracker);
        return status(rolloutId);
    }

    /**
     * Attempts to advance a rollout to the next stage.
     *
     * @return the updated status, or null if the rollout doesn't exist
     */
    public RolloutStatus advance(String rolloutId) {
        RolloutTracker tracker = rollouts.get(rolloutId);
        if (tracker == null) return null;

        RolloutStatus currentStatus = toStatus(rolloutId, tracker);
        if (!currentStatus.canAdvance(clock.currentTimeMillis())) {
            return currentStatus;
        }

        Stage[] stages = Stage.values();
        int nextOrdinal = tracker.currentStage.ordinal() + 1;
        if (nextOrdinal < stages.length) {
            tracker.currentStage = stages[nextOrdinal];
            tracker.stageEnteredAtMs = clock.currentTimeMillis();
            if (tracker.currentStage == Stage.FULL) {
                tracker.state = RolloutState.COMPLETED;
            }
        }
        return toStatus(rolloutId, tracker);
    }

    /**
     * Pauses a rollout at its current stage.
     */
    public RolloutStatus pause(String rolloutId) {
        RolloutTracker tracker = rollouts.get(rolloutId);
        if (tracker != null && tracker.state == RolloutState.IN_PROGRESS) {
            tracker.state = RolloutState.PAUSED;
        }
        return status(rolloutId);
    }

    /**
     * Resumes a paused rollout.
     */
    public RolloutStatus resume(String rolloutId) {
        RolloutTracker tracker = rollouts.get(rolloutId);
        if (tracker != null && tracker.state == RolloutState.PAUSED) {
            tracker.state = RolloutState.IN_PROGRESS;
            tracker.stageEnteredAtMs = clock.currentTimeMillis();
        }
        return status(rolloutId);
    }

    /**
     * Rolls back a rollout. The change should be reverted.
     */
    public RolloutStatus rollback(String rolloutId) {
        RolloutTracker tracker = rollouts.get(rolloutId);
        if (tracker != null) {
            tracker.state = RolloutState.ROLLED_BACK;
        }
        return status(rolloutId);
    }

    /**
     * Updates the health status for a rollout. Call this periodically
     * with the result of health checks.
     */
    public void updateHealth(String rolloutId, boolean healthy) {
        RolloutTracker tracker = rollouts.get(rolloutId);
        if (tracker != null) {
            tracker.healthPassing = healthy;
        }
    }

    /**
     * Returns the current status of a rollout.
     */
    public RolloutStatus status(String rolloutId) {
        RolloutTracker tracker = rollouts.get(rolloutId);
        if (tracker == null) return null;
        return toStatus(rolloutId, tracker);
    }

    /**
     * Returns the number of active rollouts.
     */
    public int activeRolloutCount() {
        int count = 0;
        for (RolloutTracker t : rollouts.values()) {
            if (t.state == RolloutState.IN_PROGRESS || t.state == RolloutState.PAUSED) {
                count++;
            }
        }
        return count;
    }

    /**
     * Removes completed or rolled-back rollouts from tracking.
     */
    public void cleanup() {
        rollouts.entrySet().removeIf(e ->
                e.getValue().state == RolloutState.COMPLETED
                        || e.getValue().state == RolloutState.ROLLED_BACK);
    }

    private RolloutStatus toStatus(String rolloutId, RolloutTracker tracker) {
        long soakTime = soakTimes.getOrDefault(tracker.currentStage, 0L);
        return new RolloutStatus(
                rolloutId,
                tracker.currentStage,
                tracker.state,
                tracker.policy,
                tracker.stageEnteredAtMs,
                soakTime,
                tracker.healthPassing
        );
    }

    private static final class RolloutTracker {
        Stage currentStage;
        RolloutState state;
        RolloutPolicy policy;
        long stageEnteredAtMs;
        boolean healthPassing;
    }
}
