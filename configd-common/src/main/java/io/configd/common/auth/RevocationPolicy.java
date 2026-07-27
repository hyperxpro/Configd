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
 * who sets it {@code false} re-arms the foot-gun and MUST be warned loudly (the interior is never wired to
 * a checker, so the flag exists to make the invariant explicit and auditable).
 */
public record RevocationPolicy(RevocationMode mode, boolean exemptInterNode, long responderTimeoutMs) {

    public static final RevocationPolicy OFF = new RevocationPolicy(RevocationMode.OFF, true, 3_000L);

    public RevocationPolicy {
        java.util.Objects.requireNonNull(mode, "mode");
        if (responderTimeoutMs <= 0L) {
            throw new IllegalArgumentException("responderTimeoutMs must be > 0: " + responderTimeoutMs);
        }
    }

    /**
     * Builds the policy from {@link ConfigSource}, fail-closed (an unrecognized mode name fails the boot).
     * Absent keys fall back to {@link #OFF}, so an unconfigured deployment does no online revocation.
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

    public boolean enabled() {
        return mode != RevocationMode.OFF;
    }

    public boolean admits(RevocationStatus status) {
        return switch (mode) {
            case OFF -> true;
            case LAX -> status != RevocationStatus.REVOKED;
            case STRICT -> status == RevocationStatus.GOOD;
        };
    }

    /**
         * Under {@code lax} this is the operator's ONLY signal that the responder is down (admission still
         * succeeds); under {@code strict} it pairs with a fail-closed rejection.
         */
    public boolean shouldAlarm(RevocationStatus status) {
        return enabled() && status == RevocationStatus.UNKNOWN;
    }
}
