package io.configd.distribution.fanout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SlowConsumerPolicyConfigTest {

    @Test
    void defaultsMatchTheDesignTable() {
        SlowConsumerPolicyConfig c = SlowConsumerPolicyConfig.defaults();
        assertEquals(10_000L, c.queueWarnWindowMs());
        assertEquals(3, c.demoteLimit());
        assertEquals(10, c.gapDemoteLimit());
        assertEquals(60_000L, c.demoteWindowMs());
        assertEquals(60_000L, c.quarantineCooldownMs());
        assertEquals(3, c.quarantineLimit());
        assertEquals(3_600_000L, c.unhealthyWindowMs());
        assertEquals(3_600_000L, c.unhealthyCooldownMs());
        assertEquals(4_096, c.maxTrackedIdentities());
    }

    @Test
    void rejectsEveryNonPositiveField() {
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                0L, 3, 10, 60_000L, 60_000L, 3, 1L, 1L, 1));
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                1L, 0, 10, 60_000L, 60_000L, 3, 1L, 1L, 1));
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                1L, 3, 0, 60_000L, 60_000L, 3, 1L, 1L, 1));
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                1L, 3, 10, 0L, 60_000L, 3, 1L, 1L, 1));
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                1L, 3, 10, 60_000L, 0L, 3, 1L, 1L, 1));
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                1L, 3, 10, 60_000L, 60_000L, 0, 1L, 1L, 1));
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                1L, 3, 10, 60_000L, 60_000L, 3, 0L, 1L, 1));
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                1L, 3, 10, 60_000L, 60_000L, 3, 1L, 0L, 1));
        assertThrows(IllegalArgumentException.class, () -> new SlowConsumerPolicyConfig(
                1L, 3, 10, 60_000L, 60_000L, 3, 1L, 1L, 0));
    }
}
