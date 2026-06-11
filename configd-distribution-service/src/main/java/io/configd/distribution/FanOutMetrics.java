package io.configd.distribution;

/**
 * Functional metrics hook for {@link FanOutBuffer} (ADR-0034 §overflow policy).
 *
 * <p>Mirrors the established {@code StateMachineMetrics} pattern: a tiny SAM-style
 * interface with a {@link #NOOP} sentinel, so {@code configd-distribution-service}
 * need not depend on {@code configd-observability}. The server wires a real
 * {@code fanout_buffer_dropped_total} counter; tests and bootstraps use NOOP.
 *
 * <p>All callbacks run on the single appender (apply) thread and must be
 * allocation-free — eviction is on the steady-state append path.
 */
public interface FanOutMetrics {

    /**
     * Records that one notification was dropped (evicted by drop-oldest overflow).
     * Backs the {@code fanout_buffer_dropped_total} metric. Called once per
     * evicted entry, on the appender thread.
     */
    void onDropped();

    /** No-op sink for tests / pre-wire bootstraps. */
    FanOutMetrics NOOP = () -> {};
}
