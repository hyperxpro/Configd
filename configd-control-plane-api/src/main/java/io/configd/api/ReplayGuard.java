package io.configd.api;

import io.configd.common.Clock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Replay protection for mutating control-plane requests. A client
 * stamps each request with {@code X-Configd-Timestamp} (epoch ms) and a unique
 * {@code X-Configd-Nonce}; the guard rejects a request whose timestamp is outside
 * a {@code ±window} of server time (a stale capture or a clock-skewed/forged
 * future stamp) and rejects a nonce it has already seen inside the window (a
 * verbatim capture-and-replay).
 *
 * <h2>Trust model (honesty requirement)</h2>
 * This defends against a <b>passive</b> attacker who captures a legitimate
 * request and re-sends it verbatim. It does <b>NOT</b> stop a holder of the
 * bearer token from minting a <em>fresh</em> request (new nonce + current
 * timestamp): that is the bearer token's trust model, not the replay guard's.
 * For per-request integrity against an active token-holder, content signing
 * (SigV4-style HMAC over method+path+body+timestamp+nonce) is required - that
 * is out of scope here.
 *
 * <h2>Bounding (anti-DoS)</h2>
 * The seen-nonce store is bounded two ways so it cannot be turned into a
 * memory-exhaustion lever by an attacker who floods unique nonces:
 * <ul>
 *   <li><b>TTL eviction</b>: a nonce is only retained for {@code window} (its
 *       replay-relevance lifetime); expired entries are evicted lazily on each
 *       call and opportunistically at insert. A nonce older than the window
 *       could not be replayed successfully anyway (its timestamp would be
 *       stale), so dropping it loses no protection.</li>
 *   <li><b>Hard size cap</b> {@code maxNonces} (default
 *       {@link #DEFAULT_MAX_NONCES}): an LRU eviction order bounds the map even
 *       under a same-instant flood. Evicting the oldest nonce can only ever
 *       <em>weaken</em> replay detection for that one already-accepted request
 *       within its window - it can never cause a false reject - so the cap is a
 *       safe, bounded trade.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * {@link #check} is synchronized on this instance.
 */
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

    // nonce -> expiry epoch-ms (the timestamp at which it leaves the window).
    // accessOrder=true makes this an LRU so the size cap evicts the oldest.
    private final LinkedHashMap<String, Long> seen;

    /** Creates a guard with the default ±5-minute window and default cap. */
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

        // Reject both stale (too old) and future-skewed (too far ahead) stamps.
        if (Math.abs(now - timestampMs) > windowMs) {
            return Decision.STALE;
        }

        // A stale/expired prior sighting has already been evicted above, so a hit
        // here is a genuine in-window replay.
        if (seen.containsKey(nonce)) {
            return Decision.REPLAY;
        }

        // Record with an expiry one window past NOW (its replay-relevance horizon).
        seen.put(nonce, now + windowMs);
        return Decision.ACCEPTED;
    }

    /** Current number of retained nonces (for tests/observability). */
    public synchronized int trackedNonces() {
        return seen.size();
    }

    /**
     * Lazily evicts every nonce whose expiry has passed (expiry &le; now). Runs
     * on every {@link #check}, so the store self-trims to at most the nonces seen
     * within the last {@code window}; combined with the {@code maxNonces} LRU cap
     * this keeps the store bounded under any input.
     */
    private void evictExpired(long now) {
        seen.values().removeIf(expiry -> expiry <= now);
    }
}
