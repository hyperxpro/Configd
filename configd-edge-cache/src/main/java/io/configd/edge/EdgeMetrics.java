package io.configd.edge;

/**
 * Immutable snapshot of edge node metrics at a point in time.
 * <p>
 * Captures the essential health indicators for an edge config client:
 * current replication version, staleness relative to the control plane,
 * subscription topology, and local data size.
 *
 * @param currentVersion   the monotonic version of the local config store
 * @param stalenessMs      milliseconds since the last successful update from the control plane
 * @param stalenessState   the discrete staleness state (CURRENT, STALE, DEGRADED, DISCONNECTED)
 * @param subscriptionCount number of active prefix subscriptions
 * @param snapshotSize     number of config entries in the local store
 */
public record EdgeMetrics(
        long currentVersion,
        long stalenessMs,
        StalenessTracker.State stalenessState,
        int subscriptionCount,
        int snapshotSize
) {

    public EdgeMetrics {
        if (currentVersion < 0) {
            throw new IllegalArgumentException("currentVersion must be non-negative: " + currentVersion);
        }
        if (stalenessMs < 0) {
            throw new IllegalArgumentException("stalenessMs must be non-negative: " + stalenessMs);
        }
        if (stalenessState == null) {
            throw new IllegalArgumentException("stalenessState must not be null");
        }
        if (subscriptionCount < 0) {
            throw new IllegalArgumentException("subscriptionCount must be non-negative: " + subscriptionCount);
        }
        if (snapshotSize < 0) {
            throw new IllegalArgumentException("snapshotSize must be non-negative: " + snapshotSize);
        }
    }
}
