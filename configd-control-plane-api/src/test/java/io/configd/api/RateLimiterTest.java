package io.configd.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    @Test
    void burstPermitsAvailableImmediately() {
        AtomicLong nanos = new AtomicLong(0);
        io.configd.common.Clock clock = new io.configd.common.Clock() {
            @Override public long currentTimeMillis() { return nanos.get() / 1_000_000; }
            @Override public long nanoTime() { return nanos.get(); }
        };

        RateLimiter limiter = new RateLimiter(clock, 100, 10);

        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(), "permit " + i);
        }
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void permitsRefillOverTime() {
        AtomicLong nanos = new AtomicLong(0);
        io.configd.common.Clock clock = new io.configd.common.Clock() {
            @Override public long currentTimeMillis() { return nanos.get() / 1_000_000; }
            @Override public long nanoTime() { return nanos.get(); }
        };

        RateLimiter limiter = new RateLimiter(clock, 1000, 5);

        for (int i = 0; i < 5; i++) limiter.tryAcquire();
        assertFalse(limiter.tryAcquire());

        // Advance 10ms = 10 permits at 1000/s
        nanos.addAndGet(10_000_000L);
        assertTrue(limiter.tryAcquire());
    }

    @Test
    void permitsDoNotExceedMax() {
        AtomicLong nanos = new AtomicLong(0);
        io.configd.common.Clock clock = new io.configd.common.Clock() {
            @Override public long currentTimeMillis() { return nanos.get() / 1_000_000; }
            @Override public long nanoTime() { return nanos.get(); }
        };

        RateLimiter limiter = new RateLimiter(clock, 100, 5);

        nanos.addAndGet(10_000_000_000L);

        int acquired = 0;
        while (limiter.tryAcquire()) acquired++;
        assertEquals(5, acquired);
    }

    @Test
    void invalidParametersThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> new RateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new RateLimiter(100, 0));
    }

    @Test
    void tryAcquireMultiple() {
        RateLimiter limiter = new RateLimiter(100, 10);
        assertTrue(limiter.tryAcquire(5));
        assertTrue(limiter.tryAcquire(5));
        assertFalse(limiter.tryAcquire(1));
    }

    /**
     * A thread that loses the lastRefillNanos CAS must discard the permits it computed for that
     * window, not credit them again - otherwise concurrent losers double-count the same refill
     * window and the bucket over-allocates beyond its capacity.
     */
    @Test
    void concurrentAccessDoesNotOverAllocatePermits() throws Exception {
        AtomicLong nanos = new AtomicLong(0);
        io.configd.common.Clock clock = new io.configd.common.Clock() {
            @Override public long currentTimeMillis() { return nanos.get() / 1_000_000; }
            @Override public long nanoTime() { return nanos.get(); }
        };

        RateLimiter limiter = new RateLimiter(clock, 1000, 100);

        for (int i = 0; i < 100; i++) assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        // Advance time by exactly 50ms = 50 new permits at 1000/sec
        nanos.addAndGet(50_000_000L);

        int threadCount = 50;
        java.util.concurrent.atomic.AtomicInteger acquired = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            Thread.startVirtualThread(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { return; }
                if (limiter.tryAcquire()) {
                    acquired.incrementAndGet();
                }
                done.countDown();
            });
        }

        ready.await();
        go.countDown();
        done.await(5, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(50, acquired.get(),
                "Concurrent acquire must not exceed available permits (was " + acquired.get() + ")");
    }
}
