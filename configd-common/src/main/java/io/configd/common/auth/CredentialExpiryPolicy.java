package io.configd.common.auth;

import io.configd.common.config.ConfigSource;

/**
 * The pure, transport-agnostic lead-time-window + clock-skew model for a credential that carries an
 * absolute expiry (an OIDC token {@code exp}, or an mTLS leaf {@code notAfter}). It computes two
 * quantities and holds no I/O, no clock, and no mutable state - so it is trivially unit-testable and
 * shared by both the server (which enforces the hard-expiry close) and the edge client (which schedules
 * a proactive refresh ahead of expiry).
 *
 * <h2>The two quantities</h2>
 * <ul>
 *   <li><b>The refresh window {@code W}</b> ({@link #tokenRefreshWindowMs} / {@link #certRefreshWindowMs}):
 *       {@code W = clamp(fraction * lifetime, floor, ceil)}. A credential enters its refresh window at
 *       {@code expiresAt - W}. This is a <em>client</em> signal - the point at which a proactively
 *       refreshing peer should renew (a re-presentable token via {@code REFRESH_AUTH}, or a reconnect for
 *       a cert). The server never closes inside the window; the window is a grace so a refreshing client
 *       is not cut off. Anchored on etcd (refresh at a fraction of the TTL) and golang oauth2's
 *       refresh-ahead-of-{@code exp}. Defaults: token {@code (0.20, 30s, 5m)}, cert {@code (0.10, 5m, 1h)}.</li>
 *   <li><b>The server close deadline</b> ({@link #serverCloseDeadlineMillis}): {@code expiresAt + leewayMs}.
 *       The server closes a connection whose credential has lapsed at {@code expiresAt + leewayMs}, never
 *       before. The leeway (default 60s) absorbs clock skew between the credential-issuing authority (IdP /
 *       CA) and this server, so a connection is not slammed exactly at a skewed {@code exp}. Because
 *       {@code W >> leewayMs}, the window opens well before the close deadline and the two never invert.</li>
 * </ul>
 *
 * <p>The leeway applies ONLY to a credential with an authority-issued absolute expiry (skew is between
 * two clocks). A server-computed session-lifetime cap (a static bearer token's default TTL, measured on
 * this server's own clock as {@code now + ttl}) has no cross-clock skew and does not use this model - it
 * closes at exactly {@code now + ttl}, byte-identical to the pre-Gate-5 edge.
 */
public record CredentialExpiryPolicy(double tokenWindowFraction, long tokenWindowFloorMs,
                                     long tokenWindowCeilMs, double certWindowFraction,
                                     long certWindowFloorMs, long certWindowCeilMs,
                                     long clockSkewLeewayMs) {

    /** The finding's recommended defaults: token {@code (0.20, 30s, 5m)}, cert {@code (0.10, 5m, 1h)}, leeway 60s. */
    public static final CredentialExpiryPolicy DEFAULTS = new CredentialExpiryPolicy(
            0.20, 30_000L, 300_000L, 0.10, 300_000L, 3_600_000L, 60_000L);

    public CredentialExpiryPolicy {
        requireFraction("configd.auth.expiry.tokenWindowFraction", tokenWindowFraction);
        requireFraction("configd.auth.expiry.certWindowFraction", certWindowFraction);
        requireWindowBounds("token", tokenWindowFloorMs, tokenWindowCeilMs);
        requireWindowBounds("cert", certWindowFloorMs, certWindowCeilMs);
        if (clockSkewLeewayMs < 0) {
            throw new IllegalArgumentException("clockSkewLeewayMs must be >= 0: " + clockSkewLeewayMs);
        }
    }

    /**
     * Builds the policy from {@link ConfigSource}, fail-closed (a present-but-unparseable knob fails the
     * boot). Absent keys fall back to {@link #DEFAULTS}, so an unconfigured deployment reproduces the
     * finding's recommended windows.
     */
    public static CredentialExpiryPolicy fromConfig(ConfigSource cfg) {
        return new CredentialExpiryPolicy(
                cfg.getDouble("configd.auth.expiry.tokenWindowFraction", DEFAULTS.tokenWindowFraction),
                cfg.getLong("configd.auth.expiry.tokenWindowFloorMs", DEFAULTS.tokenWindowFloorMs),
                cfg.getLong("configd.auth.expiry.tokenWindowCeilMs", DEFAULTS.tokenWindowCeilMs),
                cfg.getDouble("configd.auth.expiry.certWindowFraction", DEFAULTS.certWindowFraction),
                cfg.getLong("configd.auth.expiry.certWindowFloorMs", DEFAULTS.certWindowFloorMs),
                cfg.getLong("configd.auth.expiry.certWindowCeilMs", DEFAULTS.certWindowCeilMs),
                cfg.getLong("configd.auth.clockSkewLeewayMs", DEFAULTS.clockSkewLeewayMs));
    }

    /** The refresh lead-time window (ms) for a token whose total lifetime is {@code lifetimeMs}. */
    public long tokenRefreshWindowMs(long lifetimeMs) {
        return clampWindow(tokenWindowFraction, lifetimeMs, tokenWindowFloorMs, tokenWindowCeilMs);
    }

    /** The refresh lead-time window (ms) for a cert whose total lifetime is {@code lifetimeMs}. */
    public long certRefreshWindowMs(long lifetimeMs) {
        return clampWindow(certWindowFraction, lifetimeMs, certWindowFloorMs, certWindowCeilMs);
    }

    /**
     * The wall-clock time (ms since epoch) at which the server closes a connection whose credential
     * expires at {@code credentialExpiresAtMillis}: {@code credentialExpiresAtMillis + leeway}, saturating
     * rather than overflowing so a {@code notAfter} near {@link Long#MAX_VALUE} never wraps negative.
     */
    public long serverCloseDeadlineMillis(long credentialExpiresAtMillis) {
        long deadline = credentialExpiresAtMillis + clockSkewLeewayMs;
        // Saturate on overflow (a far-future notAfter + leeway must never wrap to a past time).
        return (deadline < credentialExpiresAtMillis) ? Long.MAX_VALUE : deadline;
    }

    private static long clampWindow(double fraction, long lifetimeMs, long floorMs, long ceilMs) {
        long raw = (lifetimeMs <= 0L) ? 0L : (long) (fraction * (double) lifetimeMs);
        return Math.max(floorMs, Math.min(ceilMs, raw));
    }

    private static void requireFraction(String name, double fraction) {
        if (!(fraction >= 0.0 && fraction <= 1.0)) { // also rejects NaN
            throw new IllegalArgumentException(name + " must be in [0.0, 1.0]: " + fraction);
        }
    }

    private static void requireWindowBounds(String which, long floorMs, long ceilMs) {
        if (floorMs < 0L) {
            throw new IllegalArgumentException(which + " window floorMs must be >= 0: " + floorMs);
        }
        if (ceilMs < floorMs) {
            throw new IllegalArgumentException(
                    which + " window ceilMs (" + ceilMs + ") must be >= floorMs (" + floorMs + ")");
        }
    }
}
