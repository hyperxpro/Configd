package io.configd.api;

import io.configd.common.Clock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ReplayGuard {

    /** Default acceptance window in milliseconds (±5 minutes). */
    public static final long DEFAULT_WINDOW_MS = 300_000L;

    /** Default hard cap on retained nonces. */
    public static final int DEFAULT_MAX_NONCES = 1_000_000;

    /** Header carrying the client's request timestamp (epoch ms). */
    public static final String TIMESTAMP_HEADER = "X-Configd-Timestamp";

    /** Header carrying the client's per-request unique nonce. */
    public static final String NONCE_HEADER = "X-Configd-Nonce";

    /** The outcome of a replay check. */
    public enum Decision {
        /** Timestamp in-window and nonce unseen: accept (the nonce is now recorded). */
        ACCEPTED,
        /** Timestamp outside ±window (stale capture or future-skewed): reject. */
        STALE,
        /** Nonce already seen within the window: reject as a replay. */
        REPLAY,
        /** A required header was missing or malformed: reject. */
        MALFORMED
    }

    private final Clock clock;
    private final long windowMs;
    private final int maxNonces;

    private final LinkedHashMap<String, Long> seen;

    public ReplayGuard(Clock clock) {
        this(clock, DEFAULT_WINDOW_MS, DEFAULT_MAX_NONCES);
    }

    /**
     * @param clock     time source (non-null)
     * @param windowMs  the half-width of the acceptance window in ms (&gt; 0)
     * @param maxNonces the hard size cap on the nonce store (&gt; 0)
     */
    public ReplayGuard(Clock clock, long windowMs, int maxNonces) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (windowMs <= 0) {
            throw new IllegalArgumentException("windowMs must be positive: " + windowMs);
        }
        if (maxNonces <= 0) {
            throw new IllegalArgumentException("maxNonces must be positive: " + maxNonces);
        }
        this.windowMs = windowMs;
        this.maxNonces = maxNonces;
        this.seen = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > ReplayGuard.this.maxNonces;
            }
        };
    }

    /**
     * Checks a request's replay headers and, on acceptance, records the nonce.
     *
     * @param timestampHeader the raw {@code X-Configd-Timestamp} value (nullable)
     * @param nonce           the raw {@code X-Configd-Nonce} value (nullable)
     * @return the {@link Decision}; {@link Decision#ACCEPTED} also records the nonce
     */
    public synchronized Decision check(String timestampHeader, String nonce) {
        if (timestampHeader == null || timestampHeader.isBlank()
                || nonce == null || nonce.isBlank()) {
            return Decision.MALFORMED;
        }
        long timestampMs;
        try {
            timestampMs = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return Decision.MALFORMED;
        }

        long now = clock.currentTimeMillis();
        evictExpired(now);

        if (Math.abs(now - timestampMs) > windowMs) {
            return Decision.STALE;
        }

        if (seen.containsKey(nonce)) {
            return Decision.REPLAY;
        }

        seen.put(nonce, now + windowMs);
        return Decision.ACCEPTED;
    }

    public synchronized int trackedNonces() {
        return seen.size();
    }

    private void evictExpired(long now) {
        seen.values().removeIf(expiry -> expiry <= now);
    }
}
