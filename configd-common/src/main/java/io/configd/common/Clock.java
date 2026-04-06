package io.configd.common;

/**
 * Abstraction over time sources. Enables deterministic simulation
 * by swapping real clock for simulated clock.
 */
public interface Clock {

    /** Wall-clock time in milliseconds since epoch. */
    long currentTimeMillis();

    /** Monotonic nanosecond counter for elapsed time measurement. */
    long nanoTime();

    /** System clock implementation using real time. */
    static Clock system() {
        return SystemClock.INSTANCE;
    }
}
