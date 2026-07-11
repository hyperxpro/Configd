package io.configd.distribution.fanout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Validates {@link SlowConsumerPolicyConfig} defaults (each a named threshold) and every
 * positivity guard, so the bounds are mutation-tight, mirroring {@code FanOutConfigTest}.
 */
class SlowConsumerPolicyConfigTest {

    @Test
    void defaultsMatchTheDesignTable() {
        SlowConsumerPolicyConfig c = SlowConsumerPolicyConfig.defaults();
        assertEquals(10_000L, c.queueWarnWindowMs());   // 0 credits for a window greater than 10 s
        assertEquals(3, c.demoteLimit());
        assertEquals(10, c.gapDemoteLimit());           // gap demotions count more
        assertEquals(60_000L, c.demoteWindowMs());
        assertEquals(60_000L, c.quarantineCooldownMs());
        assertEquals(3, c.quarantineLimit());           // 3 quarantines in 1 hour
        assertEquals(3_600_000L, c.unhealthyWindowMs());
        assertEquals(3_600_000L, c.unhealthyCooldownMs()); // automatic exit after cooldown
        assertEquals(4_096, c.maxTrackedIdentities());
    }

    @Test
    void rejectsEveryNonPositiveField() {
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                0L, 3, 10, 60_000L, 60_000L, 3, 1L, 1L, 1));   // queueWarnWindowMs
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                1L, 0, 10, 60_000L, 60_000L, 3, 1L, 1L, 1));   // demoteLimit
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                1L, 3, 0, 60_000L, 60_000L, 3, 1L, 1L, 1));    // gapDemoteLimit
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                1L, 3, 10, 0L, 60_000L, 3, 1L, 1L, 1));        // demoteWindowMs
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                1L, 3, 10, 60_000L, 0L, 3, 1L, 1L, 1));        // quarantineCooldownMs
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                1L, 3, 10, 60_000L, 60_000L, 0, 1L, 1L, 1));   // quarantineLimit
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                1L, 3, 10, 60_000L, 60_000L, 3, 0L, 1L, 1));   // unhealthyWindowMs
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                1L, 3, 10, 60_000L, 60_000L, 3, 1L, 0L, 1));   // unhealthyCooldownMs
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                1L, 3, 10, 60_000L, 60_000L, 3, 1L, 1L, 0));   // maxTrackedIdentities
    }
}
