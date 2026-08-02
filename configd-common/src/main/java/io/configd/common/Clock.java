package io.configd.common;

public interface Clock {

    long currentTimeMillis();

    long nanoTime();

    static Clock system() {
        return SystemClock.INSTANCE;
    }
}
