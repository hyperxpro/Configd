package io.configd.server;

import io.configd.observability.ConfigdMetrics;
import io.configd.store.StateMachineMetrics;

import java.util.Objects;

/**
 * Bridges the {@link StateMachineMetrics} sink ({@code configd-config-store}) into the
 * server's {@link ConfigdMetrics} registry (before this, {@code ConfigStateMachine} was constructed with
 * {@link StateMachineMetrics#NOOP} so {@code configd_apply_seconds} /
 * {@code configd_snapshot_install_failed_total} were registered but never recorded).
 *
 * <p>The apply <em>duration</em> feeds {@code configd_apply_seconds} - NOT
 * {@code configd_write_commit_seconds}, which is the end-to-end commit latency recorded at the
 * {@code raftProposer} site on the HTTP write thread. Routing apply duration into the write-commit
 * histogram would make the "write commit p99 &lt; 150 ms" SLO measure microsecond apply cost - a
 * subtler blind dashboard.
 *
 * <p>{@link #onWriteCommitFailure()} is intentionally NOT counted here: an apply that throws AFTER
 * commit is a state-divergence event already surfaced by the {@code InvariantMonitor} and the
 * inbound-routing-throwable counter; routing it to {@code write_commit_failed} would
 * double-count against the end-to-end failure counter the availability SLO consumes.
 */
final class ServerStateMachineMetrics implements StateMachineMetrics {

    private final ConfigdMetrics metrics;

    ServerStateMachineMetrics(ConfigdMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    @Override
    public void onWriteCommitSuccess(long applyDurationNanos) {
        metrics.applySeconds().record(applyDurationNanos);
    }

    @Override
    public void onWriteCommitFailure() {
        // Deliberately not counted - see class javadoc (avoid double-count; already observable).
    }

    @Override
    public void onMalformedCommittedCommand() {
        // A poison-pill committed command was skipped deterministically at apply time. Unlike
        // onWriteCommitFailure (deliberately not counted here - see class javadoc), this IS counted:
        // it is a security/integrity alarm, not a routine post-commit apply failure, and it has no
        // other observable counterpart.
        metrics.commandMalformed().increment();
    }

    @Override
    public void onSnapshotRebuildSuccess() {
        metrics.snapshotRebuild().increment();
    }

    @Override
    public void onSnapshotInstallFailed() {
        metrics.snapshotInstallFailed().increment();
    }
}
