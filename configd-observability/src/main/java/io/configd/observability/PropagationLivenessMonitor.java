package io.configd.observability;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime counterpart of TLA+ LIVE-1 (EdgePropagationLiveness):
 * "Every committed write eventually reaches every live edge."
 *
 * Tracks the gap between the leader's commit index and each edge's
 * applied index. Fires a violation if any live edge falls behind
 * by more than the configured threshold.
 */
public final class PropagationLivenessMonitor {

    private final long maxLagEntries;
    private final AtomicLong leaderCommitIndex = new AtomicLong(0);
    private final ConcurrentHashMap<String, Long> edgeAppliedVersions = new ConcurrentHashMap<>();
    private final MetricsRegistry metrics;

    public PropagationLivenessMonitor(long maxLagEntries, MetricsRegistry metrics) {
        if (maxLagEntries <= 0) throw new IllegalArgumentException("maxLagEntries must be positive");
        this.maxLagEntries = maxLagEntries;
        this.metrics = Objects.requireNonNull(metrics);
        metrics.counter("propagation.lag.violation");
    }

    public void updateLeaderCommit(long commitIndex) {
        leaderCommitIndex.set(commitIndex);
    }

    public void updateEdgeApplied(String edgeId, long appliedVersion) {
        edgeAppliedVersions.put(edgeId, appliedVersion);
    }

    public void removeEdge(String edgeId) {
        edgeAppliedVersions.remove(edgeId);
    }

    /**
     * Checks all edges for propagation lag violations.
     * @return number of edges that are lagging beyond threshold
     */
    public int checkAll() {
        long commit = leaderCommitIndex.get();
        int violations = 0;
        for (var entry : edgeAppliedVersions.entrySet()) {
            long lag = commit - entry.getValue();
            if (lag > maxLagEntries) {
                violations++;
                metrics.counter("propagation.lag.violation").increment();
            }
        }
        return violations;
    }

    public long lagFor(String edgeId) {
        Long applied = edgeAppliedVersions.get(edgeId);
        if (applied == null) return -1;
        return leaderCommitIndex.get() - applied;
    }
}
