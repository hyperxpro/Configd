package io.configd.distribution.fanout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Validates {@link FanOutConfig} defaults and every guard (charter §6 rule 8 — named,
 * validated config). Each invalid-argument branch is exercised so the bounds are
 * mutation-tight (gate-3).
 */
class FanOutConfigTest {

    @Test
    void defaultsMatchTheDesignTable() {
        FanOutConfig c = FanOutConfig.defaults();
        assertEquals(256, c.queueFrames());
        assertEquals(80, c.queueWarnPct());
        assertEquals(64, c.batchMaxNotifications());
        assertEquals(262_144, c.batchMaxBytes());
        assertEquals(8_192L, c.ackLagDemoteSeqs());
        assertEquals(250L, c.heartbeatMs());
        assertEquals(5L, c.idlePollMs());
        assertEquals(1_048_576, c.snapshotChunkBytes());
    }

    @Test
    void warnThresholdIsQueueFramesTimesPct() {
        // 256 * 80 / 100 = 204
        assertEquals(204, FanOutConfig.defaults().queueWarnThresholdFrames());
        // exact integer math at a small value: 5 * 80 / 100 = 4
        assertEquals(4, new FanOutConfig(5, 80, 1, 1024, 1L, 1L, 1L, 1024).queueWarnThresholdFrames());
        // 0% warn -> 0
        assertEquals(0, new FanOutConfig(10, 0, 1, 1024, 1L, 1L, 1L, 1024).queueWarnThresholdFrames());
    }

    @Test
    void rejectsEveryOutOfRangeField() {
        assertThrows(IllegalArgumentException.class,
                () -> new FanOutConfig(0, 80, 1, 1024, 1L, 1L, 1L, 1024)); // queueFrames
        assertThrows(IllegalArgumentException.class,
                () -> new FanOutConfig(1, -1, 1, 1024, 1L, 1L, 1L, 1024)); // queueWarnPct < 0
        assertThrows(IllegalArgumentException.class,
                () -> new FanOutConfig(1, 101, 1, 1024, 1L, 1L, 1L, 1024)); // queueWarnPct > 100
        assertThrows(IllegalArgumentException.class,
                () -> new FanOutConfig(1, 80, 0, 1024, 1L, 1L, 1L, 1024)); // batchMaxNotifications
        assertThrows(IllegalArgumentException.class,
                () -> new FanOutConfig(1, 80, 65, 1024, 1L, 1L, 1L, 1024)); // > codec batch cap (64)
        assertThrows(IllegalArgumentException.class,
                () -> new FanOutConfig(1, 80, 1, 0, 1L, 1L, 1L, 1024)); // batchMaxBytes
        assertThrows(IllegalArgumentException.class,
                () -> new FanOutConfig(1, 80, 1, 262_145, 1L, 1L, 1L, 1024)); // > codec byte cap
        assertThrows(IllegalArgumentException.class,
                () -> new FanOutConfig(1, 80, 1, 1024, 0L, 1L, 1L, 1024)); // ackLagDemoteSeqs
        assertThrows(IllegalArgumentException.class,
                () -> new FanOutConfig(1, 80, 1, 1024, 1L, 0L, 1L, 1024)); // heartbeatMs
        assertThrows(IllegalArgumentException.class,
                () -> new FanOutConfig(1, 80, 1, 1024, 1L, 1L, 0L, 1024)); // idlePollMs
        assertThrows(IllegalArgumentException.class,
                () -> new FanOutConfig(1, 80, 1, 1024, 1L, 1L, 1L, 0)); // snapshotChunkBytes
        assertThrows(IllegalArgumentException.class,
                () -> new FanOutConfig(1, 80, 1, 1024, 1L, 1L, 1L, 1_048_577)); // > codec chunk cap
    }

    @Test
    void boundaryValuesAreAccepted() {
        // Exactly at each cap must be valid (kills off-by-one mutants on the > checks).
        FanOutConfig c = new FanOutConfig(1, 100, 64, 262_144, 1L, 1L, 1L, 1_048_576);
        assertEquals(64, c.batchMaxNotifications());
        assertEquals(262_144, c.batchMaxBytes());
        assertEquals(1_048_576, c.snapshotChunkBytes());
        assertEquals(100, c.queueWarnPct());
    }
}
