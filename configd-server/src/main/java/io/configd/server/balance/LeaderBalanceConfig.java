package io.configd.server.balance;

import io.configd.common.config.ConfigSource;

/**
 * Tunables for the decentralized leadership auto-balance loop, read once at boot through
 * {@link ConfigSource} so they compose with the existing config layering (system properties over
 * environment over an optional YAML file) and fail closed on a malformed value rather than silently
 * falling back to a default.
 *
 * <p>All keys live under {@code configd.raft.autobalance.*}, nested in the existing
 * {@code configd.raft.*} keyspace ({@code shardCount}, {@code ownerPoolSize}, ...):
 *
 * <ul>
 *   <li>{@code enabled} (default {@code true}) - the hard kill switch. On by default because shipping
 *       the balancer off reproduces the exact leader-drift gap it exists to close (an operator who must
 *       know to flip it on is the failure mode already observed); the conservative dampening below makes
 *       on-by-default safe.</li>
 *   <li>{@code dryRun} (default {@code false}) - observe-only. Computes and logs/metrics the transfer it
 *       WOULD make without executing it, the safest first-release posture.</li>
 *   <li>{@code intervalMs} (default 30000) - base check cadence. Leadership drift is slow (failovers,
 *       restarts), so a corrective loop at tens of seconds converges a drifted cluster within a couple of
 *       minutes without being twitchy.</li>
 *   <li>{@code jitterPct} (default 25) - the cadence is jittered by +/- this percentage to desynchronize
 *       each node's loop, so they do not fire in lockstep and herd onto the same target (CockroachDB's
 *       {@code jitteredInterval}).</li>
 *   <li>{@code imbalanceThreshold} (default 2) - act only when {@code max-min} leader count across the
 *       cluster is at least this. A spread of 1 is unavoidable whenever the group count is not divisible
 *       by the node count, so 2 is the minimum actionable imbalance. This is the small-N integer analogue
 *       of CockroachDB's fractional 5%-of-mean tolerance, which is meaningless at {@code N <= 16}.</li>
 *   <li>{@code cooldownMs} (default 60000, ~2x cadence) - after this node initiates a transfer it stays
 *       quiet for this long, letting the moved leadership settle and the distribution re-measure before
 *       the next shed.</li>
 *   <li>{@code maxInFlightTransfers} (default 1) - transfers a node initiates per cadence. The loop is a
 *       single-thread synchronous shedder, so it already initiates exactly one per cadence; this key
 *       records the contract and is validated {@code >= 1}. Values {@code > 1} are reserved for a future
 *       asynchronous design and are not yet honored.</li>
 *   <li>{@code instabilityWindowMs} (default 5000) - a term bump on any group observed within this
 *       look-back forces the whole cycle to back off (an election-storm signal). Kept well below the
 *       cadence so a settled once-per-cadence transfer does not itself count as churn.</li>
 * </ul>
 */
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

    /**
     * Reads the {@code configd.raft.autobalance.*} keys from {@code cfg}, applying the documented
     * defaults for absent keys. A present-but-unparseable value fails the boot via the
     * {@link ConfigSource} typed accessors (a typo in a knob must not be masked by the default).
     */
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
