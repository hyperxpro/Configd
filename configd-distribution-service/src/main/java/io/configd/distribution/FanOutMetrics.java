package io.configd.distribution;

/**
 * Functional metrics hook for {@link FanOutBuffer} (overflow policy).
 *
 * <p>Follows the same pattern as {@code StateMachineMetrics}: a tiny SAM-style
 * interface with a {@link #NOOP} sentinel, so {@code configd-distribution-service}
 * need not depend on {@code configd-observability}. The server wires a real
 * {@code fanout_buffer_dropped_total} counter; tests and bootstraps use NOOP.
 *
 * <p>All callbacks run on the single appender (apply) thread and must be
 * allocation-free - eviction is on the steady-state append path.
 */
public interface FanOutMetrics {

    void onDropped();

    FanOutMetrics NOOP = () -> {};
}
