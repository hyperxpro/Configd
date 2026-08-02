package io.configd.distribution;

import io.configd.common.Clock;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Thread safety: designed for single-threaded access from the distribution
 * service I/O thread.
 */
public final class RolloutController {

    /**
     * Rollout stages, in order of increasing reach.
     */
    public enum Stage {
        CANARY(0.001),
        ONE_PERCENT(0.01),
        TEN_PERCENT(0.10),
        FIFTY_PERCENT(0.50),
        FULL(1.0);

        private final double fraction;

        Stage(double fraction) {
            this.fraction = fraction;
        }

        public double fraction() {
            return fraction;
        }
    }

    public enum RolloutPolicy {
        PROGRESSIVE,
        /** Requires elevated ACL. */
        IMMEDIATE
    }

    public enum RolloutState {
        IN_PROGRESS,
        PAUSED,
        COMPLETED,
        ROLLED_BACK
    }

    public record RolloutStatus(
            String rolloutId,
            Stage currentStage,
            RolloutState state,
            RolloutPolicy policy,
            long stageEnteredAtMs,
            long soakTimeMs,
            boolean healthPassing
    ) {
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

    public void setSoakTime(Stage stage, long soakTimeMs) {
        soakTimes.put(stage, soakTimeMs);
    }

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

    public RolloutStatus pause(String rolloutId) {
        RolloutTracker tracker = rollouts.get(rolloutId);
        if (tracker != null && tracker.state == RolloutState.IN_PROGRESS) {
            tracker.state = RolloutState.PAUSED;
        }
        return status(rolloutId);
    }

    public RolloutStatus resume(String rolloutId) {
        RolloutTracker tracker = rollouts.get(rolloutId);
        if (tracker != null && tracker.state == RolloutState.PAUSED) {
            tracker.state = RolloutState.IN_PROGRESS;
            tracker.stageEnteredAtMs = clock.currentTimeMillis();
        }
        return status(rolloutId);
    }

    public RolloutStatus rollback(String rolloutId) {
        RolloutTracker tracker = rollouts.get(rolloutId);
        if (tracker != null) {
            tracker.state = RolloutState.ROLLED_BACK;
        }
        return status(rolloutId);
    }

    /** Call this periodically with the result of health checks. */
    public void updateHealth(String rolloutId, boolean healthy) {
        RolloutTracker tracker = rollouts.get(rolloutId);
        if (tracker != null) {
            tracker.healthPassing = healthy;
        }
    }

    public RolloutStatus status(String rolloutId) {
        RolloutTracker tracker = rollouts.get(rolloutId);
        if (tracker == null) return null;
        return toStatus(rolloutId, tracker);
    }

    public int activeRolloutCount() {
        int count = 0;
        for (RolloutTracker t : rollouts.values()) {
            if (t.state == RolloutState.IN_PROGRESS || t.state == RolloutState.PAUSED) {
                count++;
            }
        }
        return count;
    }

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
