package io.configd.common.auth;

import io.configd.common.config.ConfigSource;

/**
 * The online-revocation configuration for CLIENT / edge certificates: the {@link RevocationMode} posture,
 * the responder timeout, and the load-bearing {@link #exemptInterNode} invariant that keeps the CockroachDB
 * strict-lock-out foot-gun unreachable for the cluster interior.
 *
 * <h2>The strict-self-lock-out guard (verbatim from CockroachDB)</h2>
 * CockroachDB's own docs warn, of {@code security.ocsp.mode=strict}:
 * <blockquote>
 * "In the strict mode, all certificates are presumed to be invalid if the OCSP server is not reachable.
 * Setting the cluster setting {@code security.ocsp.mode} to {@code strict} will lock you out of your
 * CockroachDB database if your OCSP server is unavailable."
 * </blockquote>
 * Configd's structural mitigation is stronger than CockroachDB's "ramp off -> lax -> strict" guidance:
 * online revocation applies to <b>client / edge certificates only</b>. Two credential classes are EXEMPT
 * and validate by chain + {@code notAfter} alone, never consulting a responder:
 * <ol>
 *   <li>the Raft inter-node mTLS plane (the cluster interior) - consensus, replication, and apply keep
 *       running even if the responder is down under {@code strict};</li>
 *   <li>the cluster's own break-glass admin credential.</li>
 * </ol>
 * So the foot-gun is unreachable for the interior <b>by construction</b> (the interior has no responder in
 * its path), regardless of responder health. {@link #exemptInterNode} defaults {@code true}; an operator
 * who sets it {@code false} re-arms the foot-gun and MUST be warned loudly (the interior is still never
 * wired to a checker in v1, so the flag exists to make the invariant explicit and auditable).
 */
public record RevocationPolicy(RevocationMode mode, boolean exemptInterNode, long responderTimeoutMs) {

    /** The safe default: OFF (no online check, byte-identical to the pre-Gate-5 edge), interior exempt. */
    public static final RevocationPolicy OFF = new RevocationPolicy(RevocationMode.OFF, true, 3_000L);

    public RevocationPolicy {
        java.util.Objects.requireNonNull(mode, "mode");
        if (responderTimeoutMs <= 0L) {
            throw new IllegalArgumentException("responderTimeoutMs must be > 0: " + responderTimeoutMs);
        }
    }

    /**
     * Builds the policy from {@link ConfigSource}, fail-closed (an unrecognized mode name fails the boot).
     * Absent keys fall back to {@link #OFF}, so an unconfigured deployment does no online revocation and is
     * byte-identical to before.
     */
    public static RevocationPolicy fromConfig(ConfigSource cfg) {
        RevocationMode mode = cfg.getString("configd.auth.revocation.mode")
                .filter(v -> !v.isBlank())
                .map(RevocationMode::parse)
                .orElse(RevocationMode.OFF);
        boolean exemptInterNode = cfg.getBoolean("configd.auth.revocation.exemptInterNode", true);
        long responderTimeoutMs = cfg.getLong("configd.auth.revocation.responderTimeoutMs",
                OFF.responderTimeoutMs);
        return new RevocationPolicy(mode, exemptInterNode, responderTimeoutMs);
    }

    /** Whether any online check runs at all (i.e. the posture is not {@link RevocationMode#OFF}). */
    public boolean enabled() {
        return mode != RevocationMode.OFF;
    }

    /**
     * The admission decision for a looked-up {@link RevocationStatus}: {@code true} to ADMIT the cert,
     * {@code false} to REJECT it. This is where lax-vs-strict lives:
     * <ul>
     *   <li>{@code OFF} - admit everything (no check ran);</li>
     *   <li>{@code LAX} - reject only a definite {@code REVOKED}; fail-OPEN on {@code UNKNOWN};</li>
     *   <li>{@code STRICT} - admit only a definite {@code GOOD}; fail-CLOSED on {@code REVOKED} and
     *       {@code UNKNOWN}.</li>
     * </ul>
     */
    public boolean admits(RevocationStatus status) {
        return switch (mode) {
            case OFF -> true;
            case LAX -> status != RevocationStatus.REVOKED;
            case STRICT -> status == RevocationStatus.GOOD;
        };
    }

    /**
     * Whether a looked-up status warrants the responder-unreachable alarm: an {@code UNKNOWN} answer while
     * a check is enabled. Under {@code lax} this pairs with a fail-open admission (the alarm is the only
     * signal the operator gets); under {@code strict} it pairs with a fail-closed rejection.
     */
    public boolean shouldAlarm(RevocationStatus status) {
        return enabled() && status == RevocationStatus.UNKNOWN;
    }
}
