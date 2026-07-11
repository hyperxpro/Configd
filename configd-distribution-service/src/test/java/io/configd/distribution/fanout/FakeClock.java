package io.configd.distribution.fanout;

import io.configd.common.Clock;

/**
 * A plain, controllable {@link Clock} for {@link FanOutSessionCore} tests. The session
 * core takes time only through {@code tick(nowMillis)}, so the clock is rarely read
 * directly - but it satisfies the constructor and lets tests assert the no-wall-clock
 * contract by advancing time explicitly. ({@code SimulatedClock} lives in another
 * module's test scope, so this module supplies its own.)
 */
final class FakeClock implements Clock {

    private long nowMillis;

    FakeClock(long startMillis) {
        this.nowMillis = startMillis;
    }

    void set(long millis) {
        this.nowMillis = millis;
    }

    long advance(long deltaMillis) {
        this.nowMillis += deltaMillis;
        return nowMillis;
    }

    long now() {
        return nowMillis;
    }

    @Override
    public long currentTimeMillis() {
        return nowMillis;
    }

    @Override
    public long nanoTime() {
        return nowMillis * 1_000_000L;
    }
}
