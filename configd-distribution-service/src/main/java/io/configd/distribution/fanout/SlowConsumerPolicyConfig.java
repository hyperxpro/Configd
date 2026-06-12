package io.configd.distribution.fanout;

/**
 * Named, validated configuration for the {@link SlowConsumerGovernor} (C4 design §2;
 * charter §6 rule 8 — every policy threshold is a named config with a metric). Defaults
 * implement architecture §7's policy ladder re-based on the C1 frame/byte/ack-lag signals
 * (the §7 credit numbers are superseded — C1 design §4 / review condition 4).
 *
 * @param queueWarnWindowMs    how long the outbound queue must stay at/above the C1
 *                             {@code queueWarnPct} threshold before HEALTHY→SLOW
 *                             ({@code edge.fanout.policy.queueWarnWindowMs}, default 10_000
 *                             — §7's "0 credits for &gt; 10 s" analogue; metric
 *                             {@code edge_fanout_slow_transitions_total})
 * @param demoteLimit          distress demotions ({@code ack_lag} / {@code queue_overflow} /
 *                             {@code transport_block}) within {@code demoteWindowMs} that
 *                             trip QUARANTINED ({@code edge.fanout.policy.demoteLimit},
 *                             default 3; metric {@code edge_fanout_quarantines_total})
 * @param gapDemoteLimit       GAP demotions within {@code demoteWindowMs} that trip
 *                             QUARANTINED ({@code edge.fanout.policy.gapDemoteLimit},
 *                             default 10). Deliberately higher than {@code demoteLimit}:
 *                             a GAP is often a network/eviction artifact, not consumer
 *                             slowness — a flaky WAN must not quarantine a healthy edge
 *                             (screen condition C4-2/C4-3); the limit is the backstop for
 *                             a genuinely gap-looping consumer (same metric)
 * @param demoteWindowMs       the sliding window the demotion limits are counted over
 *                             ({@code edge.fanout.policy.demoteWindowMs}, default 60_000)
 * @param quarantineCooldownMs how long SUBSCRIBEs from a QUARANTINED identity are refused
 *                             before forced re-bootstrap readmission
 *                             ({@code edge.fanout.policy.quarantineCooldownMs}, default
 *                             60_000 — §7's "must re-bootstrap"; metric
 *                             {@code edge_fanout_reconnects_refused_total})
 * @param quarantineLimit      quarantines within {@code unhealthyWindowMs} that escalate to
 *                             UNHEALTHY ({@code edge.fanout.policy.quarantineLimit},
 *                             default 3 — §7's "3 quarantines in 1 hour"; metric
 *                             {@code edge_fanout_unhealthy_total})
 * @param unhealthyWindowMs    the sliding window quarantines are counted over
 *                             ({@code edge.fanout.policy.unhealthyWindowMs}, default
 *                             3_600_000)
 * @param unhealthyCooldownMs  how long an UNHEALTHY identity is refused before automatic
 *                             readmission ({@code edge.fanout.policy.unhealthyCooldownMs},
 *                             default 3_600_000). This auto time-based exit is the C4-3
 *                             anti-permanent-lockout guarantee: UNHEALTHY is never terminal
 *                             without operator action — the cooldown alone re-admits
 *                             (metric {@code edge_fanout_readmissions_total})
 * @param maxTrackedIdentities bound on the per-identity tracking map (hard rule 4: no
 *                             unbounded designs). Eviction removes only the
 *                             least-recently-touched HEALTHY identity; identities in any
 *                             distressed state are never evicted (their count is itself
 *                             bounded by real distinct certs in distress)
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

    /** The C4 design §2 defaults: 10 s / 3 / 10 / 60 s / 60 s / 3 / 1 h / 1 h / 4096. */
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
