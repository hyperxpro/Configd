package io.configd.api;

import io.configd.common.Clock;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class RateLimiter {

    private final Clock clock;
    private final double permitsPerSecond;
    private final double maxPermits;

    private final AtomicLong storedPermitsScaled;
    private final AtomicLong lastRefillNanos;

    private static final long SCALE = 1000L;

    /**
     * @param clock            time source
     * @param permitsPerSecond sustained rate (e.g., 10000 for 10k writes/s)
     * @param burstPermits     maximum burst capacity (permits that can accumulate)
     */
    public RateLimiter(Clock clock, double permitsPerSecond, double burstPermits) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond must be positive: " + permitsPerSecond);
        }
        if (burstPermits <= 0) {
            throw new IllegalArgumentException("burstPermits must be positive: " + burstPermits);
        }
        this.permitsPerSecond = permitsPerSecond;
        this.maxPermits = burstPermits;
        this.storedPermitsScaled = new AtomicLong((long) (burstPermits * SCALE));
        this.lastRefillNanos = new AtomicLong(clock.nanoTime());
    }

    /**
     * Creates a rate limiter using the system clock.
     *
     * @param permitsPerSecond sustained rate
     * @param burstPermits     maximum burst capacity
     */
    public RateLimiter(double permitsPerSecond, double burstPermits) {
        this(Clock.system(), permitsPerSecond, burstPermits);
    }

    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    public boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive: " + permits);
        }

        long requiredScaled = (long) permits * SCALE;

        while (true) {
            long now = clock.nanoTime();

            long lastRefill = lastRefillNanos.get();
            long elapsedNanos = now - lastRefill;
            long newPermitsScaled = 0;
            if (elapsedNanos > 0) {
                newPermitsScaled = (long) (elapsedNanos * permitsPerSecond / 1_000_000_000.0 * SCALE);
                if (newPermitsScaled > 0) {
                    if (!lastRefillNanos.compareAndSet(lastRefill, now)) {
                        newPermitsScaled = 0;
                    }
                }
            }

            long currentScaled = storedPermitsScaled.get();
            long maxScaled = (long) (maxPermits * SCALE);
            long availableScaled = Math.min(maxScaled, currentScaled + newPermitsScaled);

            if (availableScaled < requiredScaled) {
                if (newPermitsScaled > 0 && availableScaled > currentScaled) {
                    storedPermitsScaled.compareAndSet(currentScaled, availableScaled);
                }
                return false;
            }

            long afterAcquire = availableScaled - requiredScaled;
            if (storedPermitsScaled.compareAndSet(currentScaled, afterAcquire)) {
                return true;
            }
        }
    }

    public double availablePermits() {
        long now = clock.nanoTime();
        long lastRefill = lastRefillNanos.get();
        long elapsedNanos = now - lastRefill;
        double newPermits = elapsedNanos > 0
                ? elapsedNanos * permitsPerSecond / 1_000_000_000.0 : 0;
        double current = storedPermitsScaled.get() / (double) SCALE;
        return Math.min(maxPermits, current + newPermits);
    }

    public double permitsPerSecond() {
        return permitsPerSecond;
    }
}
