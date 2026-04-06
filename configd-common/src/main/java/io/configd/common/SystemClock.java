package io.configd.common;

enum SystemClock implements Clock {
    INSTANCE;

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
}
