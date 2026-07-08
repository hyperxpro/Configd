package io.configd.client.http;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

/**
 * Stamps a mutating request with the optional replay-guard headers (§04 D11-3 / §05 R6-4): the current epoch-ms
 * timestamp and a fresh random nonce. Opt-in and default-off — a deployment enables the guard, and only then
 * does the client populate these; against a guard-disabled deployment the headers are ignored.
 *
 * <p><b>Fresh per attempt (load-bearing for retry).</b> {@link #stamp} mints a NEW timestamp + nonce on every
 * call, so each retry attempt carries fresh material. Re-sending the original on a retry is a self-inflicted
 * {@code 409} (in-window replayed nonce) or {@code 401} (after the window) — the router therefore re-stamps on
 * every attempt, never reusing a prior stamp.
 *
 * <p>The header names match the server's {@code ReplayGuard} ({@code X-Configd-Timestamp} / {@code X-Configd-Nonce});
 * the client does not depend on the server type. <b>Trust model:</b> the guard is passive-replay-only — it does
 * NOT bind a credential to a node and is NOT request integrity or authentication strength (§04 D11-3).
 */
public final class ReplayGuardSigner {

    /** Matches {@code io.configd.api.ReplayGuard.TIMESTAMP_HEADER}. */
    public static final String TIMESTAMP_HEADER = "X-Configd-Timestamp";
    /** Matches {@code io.configd.api.ReplayGuard.NONCE_HEADER}. */
    public static final String NONCE_HEADER = "X-Configd-Nonce";

    private static final int NONCE_BYTES = 16;

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ReplayGuardSigner() {
        this(Clock.systemUTC());
    }

    ReplayGuardSigner(Clock clock) {
        this.clock = clock;
    }

    /** A fresh {@code (timestamp, nonce)} pair for one attempt. */
    public Stamp stamp() {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return new Stamp(Long.toString(clock.millis()),
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
    }

    /** One attempt's replay headers. */
    public record Stamp(String timestamp, String nonce) {
    }
}
