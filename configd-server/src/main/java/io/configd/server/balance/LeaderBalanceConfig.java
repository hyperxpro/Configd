package io.configd.server.balance;

import io.configd.common.config.ConfigSource;


public record LeaderBalanceConfig(
        boolean enabled,
        boolean dryRun,
        long intervalMs,
        int jitterPct,
        int imbalanceThreshold,
        long cooldownMs,
        int maxInFlightTransfers,
        long instabilityWindowMs) {

    private static final String PREFIX = "configd.raft.autobalance.";

    public LeaderBalanceConfig {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException(PREFIX + "intervalMs must be > 0, was " + intervalMs);
        }
        if (jitterPct < 0 || jitterPct > 100) {
            throw new IllegalArgumentException(PREFIX + "jitterPct must be in [0,100], was " + jitterPct);
        }
        if (imbalanceThreshold < 1) {
            throw new IllegalArgumentException(
                    PREFIX + "imbalanceThreshold must be >= 1, was " + imbalanceThreshold);
        }
        if (cooldownMs < 0) {
            throw new IllegalArgumentException(PREFIX + "cooldownMs must be >= 0, was " + cooldownMs);
        }
        if (maxInFlightTransfers < 1) {
            throw new IllegalArgumentException(
                    PREFIX + "maxInFlightTransfers must be >= 1, was " + maxInFlightTransfers);
        }
        if (instabilityWindowMs < 0) {
            throw new IllegalArgumentException(
                    PREFIX + "instabilityWindowMs must be >= 0, was " + instabilityWindowMs);
        }
    }

    
    public static LeaderBalanceConfig fromConfig(ConfigSource cfg) {
        return new LeaderBalanceConfig(
                cfg.getBoolean(PREFIX + "enabled", true),
                cfg.getBoolean(PREFIX + "dryRun", false),
                cfg.getLong(PREFIX + "intervalMs", 30_000L),
                cfg.getInt(PREFIX + "jitterPct", 25),
                cfg.getInt(PREFIX + "imbalanceThreshold", 2),
                cfg.getLong(PREFIX + "cooldownMs", 60_000L),
                cfg.getInt(PREFIX + "maxInFlightTransfers", 1),
                cfg.getLong(PREFIX + "instabilityWindowMs", 5_000L));
    }
}
