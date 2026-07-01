package io.configd.distribution.fanout;

/**
 * Named, validated configuration for the {@link SlowConsumerGovernor}. Every policy
 * threshold is a named config with a metric. Defaults: see {@link #defaults()}.
 *
 * @param queueWarnWindowMs    how long the outbound queue must stay at/above the
 *                             {@code queueWarnPct} threshold before HEALTHY-to-SLOW
 *                             ({@code edge.fanout.policy.queueWarnWindowMs}, default 10_000;
 *                             metric {@code edge_fanout_slow_transitions_total})
 * @param demoteLimit          distress demotions ({@code ack_lag} / {@code queue_overflow} /
 *                             {@code transport_block}) within {@code demoteWindowMs} that
 *                             trip QUARANTINED ({@code edge.fanout.policy.demoteLimit},
 *                             default 3; metric {@code edge_fanout_quarantines_total})
 * @param gapDemoteLimit       GAP demotions within {@code demoteWindowMs} that trip
 *                             QUARANTINED ({@code edge.fanout.policy.gapDemoteLimit},
 *                             default 10). Deliberately higher than {@code demoteLimit}:
 *                             a GAP is often a network/eviction artifact, not consumer
 *                             slowness - a flaky WAN must not quarantine a healthy edge;
 *                             the limit is the backstop for a genuinely gap-looping consumer
 * @param demoteWindowMs       the sliding window the demotion limits are counted over
 *                             ({@code edge.fanout.policy.demoteWindowMs}, default 60_000)
 * @param quarantineCooldownMs how long SUBSCRIBEs from a QUARANTINED identity are refused
 *                             before forced re-bootstrap readmission
 *                             ({@code edge.fanout.policy.quarantineCooldownMs}, default
 *                             60_000; metric {@code edge_fanout_reconnects_refused_total})
 * @param quarantineLimit      quarantines within {@code unhealthyWindowMs} that escalate to
 *                             UNHEALTHY ({@code edge.fanout.policy.quarantineLimit},
 *                             default 3; metric {@code edge_fanout_unhealthy_total})
 * @param unhealthyWindowMs    the sliding window quarantines are counted over
 *                             ({@code edge.fanout.policy.unhealthyWindowMs}, default
 *                             3_600_000)
 * @param unhealthyCooldownMs  how long an UNHEALTHY identity is refused before automatic
 *                             readmission ({@code edge.fanout.policy.unhealthyCooldownMs},
 *                             default 3_600_000). UNHEALTHY is never terminal without
 *                             operator action - the cooldown alone re-admits
 *                             (metric {@code edge_fanout_readmissions_total})
 * @param maxTrackedIdentities bound on the per-identity tracking map (no unbounded designs).
 *                             Eviction removes only the least-recently-touched HEALTHY
 *                             identity; distressed identities are never evicted
 *                             ({@code edge.fanout.policy.maxTrackedIdentities}, default 4_096)
 */
public record SlowConsumerPolicyConfig(
        long queueWarnWindowMs,
        int demoteLimit,
        int gapDemoteLimit,
        long demoteWindowMs,
        long quarantineCooldownMs,
        int quarantineLimit,
        long unhealthyWindowMs,
        long unhealthyCooldownMs,
        int maxTrackedIdentities) {

    public SlowConsumerPolicyConfig {
        requirePositive(queueWarnWindowMs, "queueWarnWindowMs");
        requirePositive(demoteLimit, "demoteLimit");
        requirePositive(gapDemoteLimit, "gapDemoteLimit");
        requirePositive(demoteWindowMs, "demoteWindowMs");
        requirePositive(quarantineCooldownMs, "quarantineCooldownMs");
        requirePositive(quarantineLimit, "quarantineLimit");
        requirePositive(unhealthyWindowMs, "unhealthyWindowMs");
        requirePositive(unhealthyCooldownMs, "unhealthyCooldownMs");
        requirePositive(maxTrackedIdentities, "maxTrackedIdentities");
    }

    /** Defaults: 10 s / 3 / 10 / 60 s / 60 s / 3 / 1 h / 1 h / 4096. */
    public static SlowConsumerPolicyConfig defaults() {
        return new SlowConsumerPolicyConfig(
                10_000L,     // queueWarnWindowMs
                3,           // demoteLimit
                10,          // gapDemoteLimit
                60_000L,     // demoteWindowMs
                60_000L,     // quarantineCooldownMs
                3,           // quarantineLimit
                3_600_000L,  // unhealthyWindowMs
                3_600_000L,  // unhealthyCooldownMs
                4_096);      // maxTrackedIdentities
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }
}
