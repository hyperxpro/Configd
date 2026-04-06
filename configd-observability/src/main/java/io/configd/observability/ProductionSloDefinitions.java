package io.configd.observability;

import java.time.Duration;

/**
 * Instantiates all production SLO definitions from PROMPT.md Section 0.1.
 * Call {@link #register(SloTracker)} at system startup.
 */
public final class ProductionSloDefinitions {
    private ProductionSloDefinitions() {}

    public static void register(SloTracker tracker) {
        // Write commit latency p99 < 150ms cross-region
        tracker.defineSlo("write.commit.latency.p99", 0.99, Duration.ofHours(1));

        // Read latency at edge p99 < 1ms
        tracker.defineSlo("edge.read.latency.p99", 0.99, Duration.ofHours(1));

        // Read latency at edge p999 < 5ms
        tracker.defineSlo("edge.read.latency.p999", 0.999, Duration.ofHours(1));

        // Propagation delay p99 < 500ms global
        tracker.defineSlo("propagation.delay.p99", 0.99, Duration.ofHours(1));

        // Availability 99.999% control plane writes
        tracker.defineSlo("control.plane.availability", 0.99999, Duration.ofDays(30));

        // Availability 99.9999% edge reads
        tracker.defineSlo("edge.read.availability", 0.999999, Duration.ofDays(30));

        // Write throughput 10k/s baseline
        tracker.defineSlo("write.throughput.baseline", 0.99, Duration.ofMinutes(5));
    }
}
