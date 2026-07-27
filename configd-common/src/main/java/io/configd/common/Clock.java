package io.configd.common;

/**
 * Abstraction over time sources. Enables deterministic simulation
 * by swapping real clock for simulated clock.
 */
public interface Clock {

    long currentTimeMillis();

    long nanoTime();

    static Clock system() {
        return SystemClock.INSTANCE;
    }
}
