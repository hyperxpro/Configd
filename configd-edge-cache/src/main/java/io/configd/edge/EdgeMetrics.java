package io.configd.edge;

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
