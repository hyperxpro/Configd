package io.configd.server;

import io.configd.observability.ConfigdMetrics;
import io.configd.store.StateMachineMetrics;

import java.util.Objects;


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
    public void onSnapshotTaken(int snapshotBytes) {
        metrics.recordSnapshotBytes(snapshotBytes);
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
