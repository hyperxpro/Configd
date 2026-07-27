package io.configd.client;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bounded exponential backoff with full jitter for reconnect/retry, plus a caller-supplied attempt ceiling.
 * The reference client never hot-loops: a retryable outcome (a pre-handshake refusal, a
 * {@link CredentialExpiredException} reconnect, a transient {@code FRAME_CORRUPT}) waits
 * {@link #backoff(int)} before the next attempt, and {@link #maxAttempts()} caps how many attempts a policy
 * makes before giving up.
 *
 * <p>The delay for attempt {@code n} (1-based) is {@code base · 2^(n-1)} clamped to {@code max}, then
 * <b>full-jittered</b> to a uniform value in {@code [0, capped]} (the AWS "full jitter" recipe) so a fleet of
 * reconnecting drivers does not synchronize into a thundering herd.
 */
public record RetryPolicy(Duration base, Duration max, int maxAttempts) {

    public RetryPolicy {
        if (base.isNegative() || base.isZero()) {
            throw new IllegalArgumentException("base backoff must be positive: " + base);
        }
        if (max.compareTo(base) < 0) {
            throw new IllegalArgumentException("max backoff " + max + " must be >= base " + base);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
        }
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(Duration.ofMillis(200), Duration.ofSeconds(30), 10);
    }

    public Duration backoff(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1: " + attempt);
        }
        long baseMs = base.toMillis();
        long maxMs = max.toMillis();
        // Cap the exponential shift so 2^(attempt-1) cannot overflow before the min() clamp.
        int shift = Math.min(attempt - 1, 62);
        long uncapped = (shift >= 62 || baseMs > (maxMs >> Math.min(shift, 62)))
                ? maxMs
                : Math.min(maxMs, baseMs << shift);
        long jittered = uncapped <= 0 ? 0 : ThreadLocalRandom.current().nextLong(uncapped + 1);
        return Duration.ofMillis(jittered);
    }
}
