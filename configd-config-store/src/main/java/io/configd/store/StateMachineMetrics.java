package io.configd.store;

/**
 * Functional metrics hook for {@link ConfigStateMachine}. Wires
 * {@code configd_write_commit_*}, {@code configd_apply_seconds},
 * {@code configd_snapshot_install_failed_total}, and
 * {@code configd_snapshot_rebuild_total} from the state-machine apply /
 * restore paths into {@code MetricsRegistry} without forcing
 * {@code configd-config-store} to depend on {@code configd-observability}.
 *
 * <p>Mirrors the {@link ConfigStateMachine.InvariantChecker} pattern: a SAM
 * with a {@link #NOOP} sentinel for unit tests and pre-wire-up bootstraps.
 *
 * <p>All callbacks must be:
 * <ul>
 *   <li>thread-safe (state-machine apply is single-threaded today, but
 *       restoreSnapshot may be called from a different Raft thread);</li>
 *   <li>allocation-free on the steady-state hot path.</li>
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

    /**
     * Records a single-writer apply-owner-thread violation - an
     * {@link ConfigStateMachine#apply} call observed on a thread other than the one bound on
     * first apply. In production this is the metric counterpart of the test/sim assertion (which
     * throws). Default no-op so existing sinks need not change; the production registry
     * overrides it.
     */
    default void onApplyOwnerThreadViolation() {}

    /**
     * Records a malformed committed command observed at apply time - a grammatically-invalid
     * command that framed cleanly (passed the outer AppendEntries {@code cmdLen} bound) but failed
     * {@link CommandCodec#decode}. Such an entry is durable in the WAL and re-decodes on every apply
     * and on replay; the state machine treats it as {@link StateMachine#NON_MUTATING} (a
     * deterministic, cluster-wide-identical skip) rather than throwing out of the apply loop - this
     * counter is the alarm that a poison-pill entry was committed (a cert-valid-but-Byzantine leader,
     * or corruption). Default no-op so existing sinks need not change; the production registry
     * overrides it.
     */
    default void onMalformedCommittedCommand() {}

    /**
     * Records the serialized byte length of a snapshot this node just produced via
     * {@link ConfigStateMachine#snapshot()}. Backs a last-snapshot-size gauge so an operator can watch
     * the state approach the per-chunk wire cap (capacity discipline) rather than discovering it only
     * when a transfer starts chunking. Fired on the snapshot-producing thread (the Raft owner thread
     * during compaction). Default no-op so existing sinks need not change; the production registry
     * overrides it.
     *
     * @param snapshotBytes the length in bytes of the snapshot just serialized
     */
    default void onSnapshotTaken(int snapshotBytes) {}

    /** No-op metrics sink - used by tests and bootstraps with no registry. */
    StateMachineMetrics NOOP = new StateMachineMetrics() {
        @Override public void onWriteCommitSuccess(long applyDurationNanos) {}
        @Override public void onWriteCommitFailure() {}
        @Override public void onSnapshotRebuildSuccess() {}
        @Override public void onSnapshotInstallFailed() {}
    };
}
