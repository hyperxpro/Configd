package io.configd.distribution;

import io.configd.common.NodeId;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigSnapshot;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;

/**
 * Manages catch-up for edge nodes that have fallen behind.
 * <p>
 * When an edge node detects a version gap (received delta's fromVersion
 * doesn't match its current version), it enters catch-up mode. The
 * CatchUpService provides two recovery mechanisms:
 * <ol>
 *   <li><b>Delta replay</b> — replays missing deltas from the WAL or
 *       delta history buffer. Used when the gap is small.</li>
 *   <li><b>Full snapshot sync</b> — transfers the entire config snapshot
 *       to the edge. Used when deltas are no longer available.</li>
 * </ol>
 * <p>
 * Thread safety: designed for single-threaded access from the distribution
 * service I/O thread. No synchronization is used.
 */
public final class CatchUpService {

    /**
     * Result of a catch-up request.
     */
    public sealed interface CatchUpResult {
        /** Deltas available — replay them to catch up. */
        record DeltaReplay(Queue<ConfigDelta> deltas) implements CatchUpResult {}
        /** Deltas not available — full snapshot required. */
        record SnapshotRequired(ConfigSnapshot snapshot) implements CatchUpResult {}
        /** No catch-up data available (snapshot provider returned null). */
        record Unavailable() implements CatchUpResult {}
    }

    /**
     * Provides the current snapshot for full sync. Implemented by the
     * config store layer.
     */
    @FunctionalInterface
    public interface SnapshotProvider {
        ConfigSnapshot currentSnapshot();
    }

    /** Ring buffer of recent deltas, keyed by fromVersion for fast lookup. */
    private final Map<Long, ConfigDelta> deltaHistory;
    private final int maxDeltaHistory;
    private final SnapshotProvider snapshotProvider;

    /** Tracks which nodes are currently in catch-up mode. */
    private final Map<NodeId, Long> catchUpNodes;

    /**
     * Creates a catch-up service.
     *
     * @param maxDeltaHistory  maximum number of deltas to retain for replay
     * @param snapshotProvider provides the current snapshot for full sync
     */
    public CatchUpService(int maxDeltaHistory, SnapshotProvider snapshotProvider) {
        if (maxDeltaHistory <= 0) {
            throw new IllegalArgumentException("maxDeltaHistory must be positive: " + maxDeltaHistory);
        }
        this.maxDeltaHistory = maxDeltaHistory;
        this.snapshotProvider = Objects.requireNonNull(snapshotProvider, "snapshotProvider must not be null");
        this.deltaHistory = new HashMap<>();
        this.catchUpNodes = new HashMap<>();
    }

    /**
     * Records a delta for potential future replay. Call this for every
     * delta produced by the config store.
     */
    public void recordDelta(ConfigDelta delta) {
        Objects.requireNonNull(delta, "delta must not be null");
        deltaHistory.put(delta.fromVersion(), delta);
        trimHistory();
    }

    /**
     * Resolves a catch-up request for a node at the given version.
     * <p>
     * Attempts to find a chain of deltas from {@code nodeVersion} to the
     * current version. If the chain is complete, returns a DeltaReplay.
     * Otherwise, falls back to a full snapshot.
     *
     * @param node        the requesting edge node
     * @param nodeVersion the edge node's current version
     * @return the catch-up result
     */
    public CatchUpResult resolve(NodeId node, long nodeVersion) {
        Objects.requireNonNull(node, "node must not be null");

        // Try to build a delta chain
        Queue<ConfigDelta> chain = new LinkedList<>();
        long currentVersion = nodeVersion;
        int maxChain = maxDeltaHistory; // safety limit

        while (maxChain-- > 0) {
            ConfigDelta delta = deltaHistory.get(currentVersion);
            if (delta == null) {
                break; // Gap in history — can't replay
            }
            chain.add(delta);
            currentVersion = delta.toVersion();

            // Check if we've caught up to the latest snapshot
            ConfigSnapshot snap = snapshotProvider.currentSnapshot();
            if (snap != null && currentVersion >= snap.version()) {
                catchUpNodes.remove(node);
                return new CatchUpResult.DeltaReplay(chain);
            }
        }

        // Delta chain incomplete — fall back to full snapshot
        ConfigSnapshot snapshot = snapshotProvider.currentSnapshot();
        if (snapshot != null) {
            catchUpNodes.remove(node);
            return new CatchUpResult.SnapshotRequired(snapshot);
        }

        return new CatchUpResult.Unavailable();
    }

    /**
     * Marks a node as needing catch-up. Used for tracking purposes.
     */
    public void markNeedsCatchUp(NodeId node, long nodeVersion) {
        catchUpNodes.put(node, nodeVersion);
    }

    /**
     * Returns true if the node is currently in catch-up mode.
     */
    public boolean needsCatchUp(NodeId node) {
        return catchUpNodes.containsKey(node);
    }

    /**
     * Returns the number of nodes currently in catch-up mode.
     */
    public int catchUpNodeCount() {
        return catchUpNodes.size();
    }

    /**
     * Returns the number of deltas retained in history.
     */
    public int deltaHistorySize() {
        return deltaHistory.size();
    }

    private void trimHistory() {
        while (deltaHistory.size() > maxDeltaHistory) {
            // Remove the oldest delta (lowest fromVersion)
            long oldest = Long.MAX_VALUE;
            for (long version : deltaHistory.keySet()) {
                if (version < oldest) {
                    oldest = version;
                }
            }
            deltaHistory.remove(oldest);
        }
    }
}
