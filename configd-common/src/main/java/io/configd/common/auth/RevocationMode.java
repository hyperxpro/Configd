package io.configd.common.auth;

/**
 * The online-revocation posture for CLIENT / edge certificates, modelled directly on CockroachDB's
 * {@code security.ocsp.mode} ({@code off} / {@code lax} / {@code strict}). It governs what happens when a
 * revocation responder (OCSP / CRL) answers, and - critically - what happens when it is UNREACHABLE:
 *
 * <ul>
 *   <li>{@link #OFF} (default) - no online revocation check at all; a certificate is trusted on chain +
 *       {@code notAfter} alone, exactly as before Gate 5 (byte-identical).</li>
 *   <li>{@link #LAX} - honour a definite revoked / not-revoked answer, but <b>fail-OPEN</b> (admit) when
 *       the responder is unreachable, raising a loud responder-down warning. Recommended once a responder
 *       is configured.</li>
 *   <li>{@link #STRICT} - honour a definite answer and <b>fail-CLOSED</b> (reject) when the responder is
 *       unreachable. This carries the CockroachDB lock-out foot-gun: an unreachable responder rejects
 *       cert clients. Its blast radius is bounded to NEW client connections, and the inter-node /
 *       break-glass-admin planes are structurally EXEMPT (never consult a responder), so a down responder
 *       can never brick the cluster interior. Adopt only after validating with {@link #LAX}.</li>
 * </ul>
 */
public enum RevocationMode {
    OFF,
    LAX,
    STRICT;

    /**
     * Parses a mode name case-insensitively, fail-closed: an unrecognized value is a hard error, never a
     * silent fallback to {@link #OFF} (a typo must fail the boot, not silently disable revocation).
     */
    public static RevocationMode parse(String value) {
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "off" -> OFF;
            case "lax" -> LAX;
            case "strict" -> STRICT;
            default -> throw new IllegalArgumentException(
                    "configd.auth.revocation.mode must be one of off|lax|strict, got: '" + value + "'");
        };
    }
}
