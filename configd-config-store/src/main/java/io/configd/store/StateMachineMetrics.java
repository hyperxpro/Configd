package io.configd.store;

/**
 * Functional metrics hook for {@link ConfigStateMachine}.
 *
 * <p>F5 (Tier-1-METRIC-DRIFT, closes H-001 / DOC-014) — wires
 * {@code configd_write_commit_*}, {@code configd_apply_seconds},
 * {@code configd_snapshot_install_failed_total}, and
 * {@code configd_snapshot_rebuild_total} from the state-machine apply /
 * restore paths into {@code MetricsRegistry} without forcing
 * {@code configd-config-store} to depend on {@code configd-observability}.
 *
 * <p>The interface is mirror-image of the existing
 * {@link ConfigStateMachine.InvariantChecker} pattern: a tiny SAM with
 * a {@link #NOOP} sentinel for unit tests and pre-wire-up bootstraps.
 *
 * <p>All callbacks must be:
 * <ul>
 *   <li>thread-safe (state-machine apply is single-threaded today, but
 *       restoreSnapshot may be called from a different Raft thread);</li>
 *   <li>allocation-free on the steady-state hot path so the
 *       {@code apply()} loop continues to satisfy §8 hard rules.</li>
 * </ul>
 */
public interface StateMachineMetrics {

    /**
     * Records a successful apply of a write command (PUT / DELETE / BATCH).
     *
     * @param applyDurationNanos wall-clock nanoseconds spent inside
     *                           {@link ConfigStateMachine#apply} for this entry
     *                           (signing included)
     */
    void onWriteCommitSuccess(long applyDurationNanos);

    /**
     * Records a failed apply (e.g. signing fail-close).
     */
    void onWriteCommitFailure();

    /**
     * Records a successful snapshot rebuild from an InstallSnapshot RPC.
     */
    void onSnapshotRebuildSuccess();

    /**
     * Records a failed snapshot install (validation rejection or any
     * unchecked throw inside {@code restoreSnapshot}).
     */
    void onSnapshotInstallFailed();

    /** No-op metrics sink — used by tests / bootstraps with no registry. */
    StateMachineMetrics NOOP = new StateMachineMetrics() {
        @Override public void onWriteCommitSuccess(long applyDurationNanos) {}
        @Override public void onWriteCommitFailure() {}
        @Override public void onSnapshotRebuildSuccess() {}
        @Override public void onSnapshotInstallFailed() {}
    };
}
