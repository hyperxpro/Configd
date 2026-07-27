package io.configd.client.http;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

/**
 * Stamps mutations with optional replay-guard headers: current epoch-ms timestamp and fresh random nonce.
 * Opt-in, default-off. Fresh per attempt (load-bearing for retry): stamp() mints new material each call.
 * Headers: X-Configd-Timestamp/X-Configd-Nonce (passive-replay-only, not credential binding or integrity guard).
 */
public final class ReplayGuardSigner {

    public static final String TIMESTAMP_HEADER = "X-Configd-Timestamp";
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

    public Stamp stamp() {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return new Stamp(Long.toString(clock.millis()),
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
    }

    public record Stamp(String timestamp, String nonce) {
    }
}
