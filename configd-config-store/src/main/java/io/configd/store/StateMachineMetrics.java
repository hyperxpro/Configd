package io.configd.store;

/**
 * Metrics hook: apply/restore events to registry without config-store→observability dep.
 * Mirrors InvariantChecker: SAM with NOOP sentinel for tests.
 * All callbacks must be thread-safe and allocation-free on hot path.
 */
public interface StateMachineMetrics {

    void onWriteCommitSuccess(long applyDurationNanos);

    void onWriteCommitFailure();

    void onSnapshotRebuildSuccess();

    void onSnapshotInstallFailed();

    /**
     * Apply call on non-owner thread; metric counterpart to test/sim assertion (throws).
     */
    default void onApplyOwnerThreadViolation() {}

    /**
     * Poison-pill entry: framed cleanly but failed decode. Alarm for Byzantine leader or corruption.
     * Treated as NON_MUTATING (deterministic cluster-wide skip).
     */
    default void onMalformedCommittedCommand() {}

    /**
     * Snapshot size gauge for operator capacity discipline (avoid late discovery at transfer time).
     */
    default void onSnapshotTaken(int snapshotBytes) {}

    StateMachineMetrics NOOP = new StateMachineMetrics() {
        @Override public void onWriteCommitSuccess(long applyDurationNanos) {}
        @Override public void onWriteCommitFailure() {}
        @Override public void onSnapshotRebuildSuccess() {}
        @Override public void onSnapshotInstallFailed() {}
    };
}
