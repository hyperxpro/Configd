package io.configd.observability;

import java.time.Duration;

public final class ProductionSloDefinitions {
    private ProductionSloDefinitions() {}

    public static void register(SloTracker tracker) {
        tracker.defineSlo("write.commit.latency.p99", 0.99, Duration.ofHours(1));
        tracker.defineSlo("edge.read.latency.p99", 0.99, Duration.ofHours(1));
        tracker.defineSlo("edge.read.latency.p999", 0.999, Duration.ofHours(1));
        tracker.defineSlo("propagation.delay.p99", 0.99, Duration.ofHours(1));
        tracker.defineSlo("control.plane.availability", 0.99999, Duration.ofDays(30));
        tracker.defineSlo("edge.read.availability", 0.999999, Duration.ofDays(30));
        tracker.defineSlo("write.throughput.baseline", 0.99, Duration.ofMinutes(5));
    }
}
