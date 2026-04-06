package io.configd.testkit;

import io.configd.common.Clock;

/**
 * Deterministic simulated clock for testing.
 * Time only advances when explicitly ticked.
 * Enables reproducible, deterministic simulation.
 */
public final class SimulatedClock implements Clock {

    private long currentTimeMs;
    private long nanoOffset;

    public SimulatedClock(long initialTimeMs) {
        this.currentTimeMs = initialTimeMs;
        this.nanoOffset = 0;
    }

    public SimulatedClock() {
        this(1_700_000_000_000L); // Fixed epoch for reproducibility
    }

    @Override
    public long currentTimeMillis() {
        return currentTimeMs;
    }

    @Override
    public long nanoTime() {
        return currentTimeMs * 1_000_000L + nanoOffset;
    }

    /** Advance time by the given number of milliseconds. */
    public void advanceMs(long ms) {
        if (ms < 0) throw new IllegalArgumentException("Cannot go backwards: " + ms);
        currentTimeMs += ms;
    }

    /** Advance time by the given number of nanoseconds (sub-ms precision). */
    public void advanceNanos(long nanos) {
        if (nanos < 0) throw new IllegalArgumentException("Cannot go backwards: " + nanos);
        nanoOffset += nanos;
        long extraMs = nanoOffset / 1_000_000L;
        if (extraMs > 0) {
            currentTimeMs += extraMs;
            nanoOffset %= 1_000_000L;
        }
    }

    /** Set absolute time (for simulation reset). */
    public void setTimeMs(long timeMs) {
        this.currentTimeMs = timeMs;
        this.nanoOffset = 0;
    }
}
