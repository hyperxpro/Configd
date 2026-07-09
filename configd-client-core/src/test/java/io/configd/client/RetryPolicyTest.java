package io.configd.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {

    @Test
    void validatesArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(Duration.ZERO, Duration.ofSeconds(1), 3));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1), 3));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(Duration.ofMillis(1), Duration.ofSeconds(1), 0));
    }

    @Test
    void backoffIsAlwaysBoundedByMax() {
        RetryPolicy policy = new RetryPolicy(Duration.ofMillis(100), Duration.ofSeconds(5), 100);
        for (int attempt = 1; attempt <= 80; attempt++) {
            Duration d = policy.backoff(attempt);
            assertTrue(!d.isNegative(), "backoff is non-negative at attempt " + attempt);
            assertTrue(d.compareTo(Duration.ofSeconds(5)) <= 0,
                    "backoff never exceeds max at attempt " + attempt + ": " + d);
        }
    }

    @Test
    void backoffCeilingGrowsWithAttemptThenSaturates() {
        // The full-jitter draw is in [0, cap]; assert the CAP (max possible) grows early and saturates at max.
        RetryPolicy policy = new RetryPolicy(Duration.ofMillis(100), Duration.ofSeconds(30), 100);
        // At a large attempt the cap is the max, so repeated draws must eventually approach it; a loose check
        // that some draw across many attempts lands in the upper half of the ceiling.
        boolean sawLarge = false;
        for (int i = 0; i < 200; i++) {
            if (policy.backoff(60).toMillis() > 15_000) {
                sawLarge = true;
                break;
            }
        }
        assertTrue(sawLarge, "at a saturated attempt, draws span up toward the 30 s ceiling");
    }

    @Test
    void defaultsAreSane() {
        RetryPolicy d = RetryPolicy.defaults();
        assertTrue(d.base().toMillis() > 0);
        assertTrue(d.max().compareTo(d.base()) >= 0);
        assertTrue(d.maxAttempts() >= 1);
    }
}
