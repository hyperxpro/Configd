package io.configd.server.balance;

import io.configd.common.config.ConfigException;
import io.configd.common.config.ConfigSource;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code configd.raft.autobalance.*} tunables: defaults (on-by-default with the documented
 * dampening), that values are read through the Gate-1 {@link ConfigSource}, and that the record's
 * validation and the source's typed accessors both fail closed on garbage.
 */
class LeaderBalanceConfigTest {

    /** A minimal in-memory {@link ConfigSource} over a fixed key map. */
    private static ConfigSource source(Map<String, String> values) {
        return new ConfigSource() {
            @Override
            public Optional<String> getString(String key) {
                return Optional.ofNullable(values.get(key));
            }

            @Override
            public Set<String> keysWithPrefix(String prefix) {
                return values.keySet().stream().filter(k -> k.startsWith(prefix)).collect(Collectors.toSet());
            }
        };
    }

    @Test
    void defaults_areOnByDefaultWithConservativeDampening() {
        LeaderBalanceConfig cfg = LeaderBalanceConfig.fromConfig(source(Map.of()));
        assertTrue(cfg.enabled(), "auto-balance is on by default (operator decision D2)");
        assertFalse(cfg.dryRun());
        assertEquals(30_000L, cfg.intervalMs());
        assertEquals(25, cfg.jitterPct());
        assertEquals(2, cfg.imbalanceThreshold());
        assertEquals(60_000L, cfg.cooldownMs());
        assertEquals(1, cfg.maxInFlightTransfers());
        assertEquals(5_000L, cfg.instabilityWindowMs());
    }

    @Test
    void killSwitch_and_dryRun_areReadFromConfig() {
        LeaderBalanceConfig off = LeaderBalanceConfig.fromConfig(
                source(Map.of("configd.raft.autobalance.enabled", "false")));
        assertFalse(off.enabled());

        LeaderBalanceConfig dry = LeaderBalanceConfig.fromConfig(
                source(Map.of("configd.raft.autobalance.dryRun", "true")));
        assertTrue(dry.enabled());
        assertTrue(dry.dryRun());
    }

    @Test
    void tunables_areReadFromConfig() {
        LeaderBalanceConfig cfg = LeaderBalanceConfig.fromConfig(source(Map.of(
                "configd.raft.autobalance.intervalMs", "45000",
                "configd.raft.autobalance.jitterPct", "10",
                "configd.raft.autobalance.imbalanceThreshold", "3",
                "configd.raft.autobalance.cooldownMs", "90000",
                "configd.raft.autobalance.instabilityWindowMs", "8000")));
        assertEquals(45_000L, cfg.intervalMs());
        assertEquals(10, cfg.jitterPct());
        assertEquals(3, cfg.imbalanceThreshold());
        assertEquals(90_000L, cfg.cooldownMs());
        assertEquals(8_000L, cfg.instabilityWindowMs());
    }

    @Test
    void malformedNumber_failsClosed() {
        // A typo in a numeric knob must fail the boot via the ConfigSource, not silently fall back.
        assertThrows(ConfigException.class, () -> LeaderBalanceConfig.fromConfig(
                source(Map.of("configd.raft.autobalance.intervalMs", "not-a-number"))));
    }

    @Test
    void malformedBoolean_failsClosed() {
        assertThrows(ConfigException.class, () -> LeaderBalanceConfig.fromConfig(
                source(Map.of("configd.raft.autobalance.enabled", "yes"))));
    }

    @Test
    void outOfRangeValues_rejectedByRecord() {
        assertThrows(IllegalArgumentException.class,
                () -> new LeaderBalanceConfig(true, false, 0L, 25, 2, 60_000L, 1, 5_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new LeaderBalanceConfig(true, false, 30_000L, 150, 2, 60_000L, 1, 5_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new LeaderBalanceConfig(true, false, 30_000L, 25, 0, 60_000L, 1, 5_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new LeaderBalanceConfig(true, false, 30_000L, 25, 2, 60_000L, 0, 5_000L));
    }
}
