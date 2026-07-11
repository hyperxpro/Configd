package io.configd.testkit;

import io.configd.common.Clock;

/**
 * A {@link Clock} view offset by a fixed per-node skew (the clock-skew fault). Wraps
 * the shared simulated time source and adds a constant
 * {@code offsetMs}, modelling a node whose wall clock disagrees with its peers.
 * <p>
 * <b>Scope note (honest correction to the design).</b> {@code RaftNode} does NOT
 * read wall-clock time - its election/heartbeat timing is purely tick-driven - so
 * per-node skew does <em>not</em> perturb elections. The only consumer of this
 * clock in the simulated stack is {@code ConfigStateMachine}, which stamps each
 * applied entry with {@code clock.currentTimeMillis()}. Skew therefore affects
 * <em>entry timestamps</em> (the staleness / HLC surface), not consensus liveness.
 * Bounded skew keeps timestamp order sane; unbounded skew is an observability /
 * staleness-measurement concern recorded as a liveness-class finding, never a
 * consensus safety failure.
 * <p>
 * Deterministic: derives entirely from the supplied time source and a fixed offset.
 */
final class SkewedClock implements Clock {

    private final java.util.function.LongSupplier baseMillis;
    private final long offsetMs;

    SkewedClock(java.util.function.LongSupplier baseMillis, long offsetMs) {
        this.baseMillis = baseMillis;
        this.offsetMs = offsetMs;
    }

    @Override
    public long currentTimeMillis() {
        return baseMillis.getAsLong() + offsetMs;
    }

    @Override
    public long nanoTime() {
        return currentTimeMillis() * 1_000_000L;
    }

    long offsetMs() {
        return offsetMs;
    }
}
