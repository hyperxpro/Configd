package io.configd.api;

import io.configd.common.Clock;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free token-bucket rate limiter for write request throttling.
 * <p>
 * Uses a CAS-based approach to avoid the synchronized serialization
 * point that caused virtual thread pinning and throughput bottlenecks.
 * <p>
 * State is encoded into a single {@code AtomicLong} pair: stored permits
 * (as fixed-point long with 1000x scale) and last refill time. This
 * enables lock-free concurrent access via compare-and-swap.
 * <p>
 * Thread safety: fully lock-free. Safe for concurrent access from
 * virtual threads without carrier thread pinning.
 */
public final class RateLimiter {

    private final Clock clock;
    private final double permitsPerSecond;
    private final double maxPermits;

    /**
     * Stored permits scaled by 1000 for fixed-point arithmetic in AtomicLong.
     */
    private final AtomicLong storedPermitsScaled;
    private final AtomicLong lastRefillNanos;

    private static final long SCALE = 1000L;

    /**
     * Creates a rate limiter.
     *
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

    /**
     * Attempts to acquire a single permit.
     *
     * @return true if the permit was granted, false if rate limited
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Attempts to acquire the specified number of permits.
     * Lock-free via CAS loop.
     *
     * @param permits number of permits to acquire
     * @return true if all permits were granted
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive: " + permits);
        }

        long requiredScaled = (long) permits * SCALE;

        while (true) {
            long now = clock.nanoTime();

            // Refill: try to advance the refill timestamp
            long lastRefill = lastRefillNanos.get();
            long elapsedNanos = now - lastRefill;
            long newPermitsScaled = 0;
            if (elapsedNanos > 0) {
                newPermitsScaled = (long) (elapsedNanos * permitsPerSecond / 1_000_000_000.0 * SCALE);
                if (newPermitsScaled > 0) {
                    // Try to claim this refill window. If another thread already
                    // advanced the clock, discard our computed permits — they were
                    // already credited by the winning thread. Without this guard,
                    // concurrent losers double-count the same time window.
                    if (!lastRefillNanos.compareAndSet(lastRefill, now)) {
                        newPermitsScaled = 0;
                    }
                }
            }

            long currentScaled = storedPermitsScaled.get();
            long maxScaled = (long) (maxPermits * SCALE);
            long availableScaled = Math.min(maxScaled, currentScaled + newPermitsScaled);

            if (availableScaled < requiredScaled) {
                // Not enough permits even after refill
                // Still try to credit the refill so other threads benefit
                if (newPermitsScaled > 0 && availableScaled > currentScaled) {
                    storedPermitsScaled.compareAndSet(currentScaled, availableScaled);
                }
                return false;
            }

            long afterAcquire = availableScaled - requiredScaled;
            if (storedPermitsScaled.compareAndSet(currentScaled, afterAcquire)) {
                return true;
            }
            // CAS failed — another thread modified the state; retry
        }
    }

    /**
     * Returns the current number of available permits (approximate).
     */
    public double availablePermits() {
        long now = clock.nanoTime();
        long lastRefill = lastRefillNanos.get();
        long elapsedNanos = now - lastRefill;
        double newPermits = elapsedNanos > 0
                ? elapsedNanos * permitsPerSecond / 1_000_000_000.0 : 0;
        double current = storedPermitsScaled.get() / (double) SCALE;
        return Math.min(maxPermits, current + newPermits);
    }

    /**
     * Returns the configured rate in permits per second.
     */
    public double permitsPerSecond() {
        return permitsPerSecond;
    }
}
