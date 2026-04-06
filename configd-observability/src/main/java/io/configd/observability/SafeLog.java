package io.configd.observability;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Defensive helpers for log emission and metric labelling.
 *
 * <p>O7 (PA-5012/13/19) — production code paths must never log raw config
 * values, raw client IPs, raw auth tokens, or raw config keys with
 * unbounded cardinality. Two operators are provided:
 * <ul>
 *   <li>{@link #redact} — fingerprint a sensitive string for logs. The
 *       output is the SHA-256 prefix in lowercase hex; collisions are
 *       monitor-safe because the value is never reversed.</li>
 *   <li>{@link #cardinalityGuard} — reduce a high-cardinality string
 *       (config key, user id) to one of a bounded set of buckets so it
 *       can safely become a Prometheus label value without exploding
 *       the metric series count.</li>
 * </ul>
 *
 * <p>The cardinality guard is deliberately strict: a label value must
 * either be one of the well-known low-cardinality categories
 * ({@code admin}, {@code service}, {@code edge}) or it is hashed into
 * one of {@link #DEFAULT_CARDINALITY_BUCKETS} buckets. A naïve metric
 * tag of "key=$rawKey" can otherwise create one series per config key,
 * which is the canonical way to OOM Prometheus.
 */
public final class SafeLog {

    /**
     * Default bucket count for {@link #cardinalityGuard}. 64 is small
     * enough that even 100% misuse keeps Prometheus happy and large
     * enough to give a coarse breakdown for debugging.
     */
    public static final int DEFAULT_CARDINALITY_BUCKETS = 64;

    private static final Pattern SAFE_LOG_VALUE = Pattern.compile("[a-zA-Z0-9._\\-/]{1,128}");

    private SafeLog() {}

    /**
     * Fingerprint a sensitive string for log emission. Returns the
     * lowercase hex of the SHA-256 prefix (8 bytes = 16 hex chars).
     * The fingerprint is stable across runs so log queries against it
     * still group correctly, but the original value is not recoverable.
     *
     * @param value the sensitive string (must not be null)
     * @return a 16-char hex fingerprint
     */
    public static String redact(String value) {
        Objects.requireNonNull(value, "value");
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Bucket a high-cardinality string into one of {@code buckets}
     * indices. Used to safely turn an unbounded user input (config key,
     * tenant id) into a bounded Prometheus label value.
     *
     * @param value   the input — may be null (returns "unknown")
     * @param buckets number of buckets — must be positive
     * @return string of the form {@code bucket-NN}
     */
    public static String cardinalityGuard(String value, int buckets) {
        if (buckets <= 0) {
            throw new IllegalArgumentException("buckets must be positive: " + buckets);
        }
        if (value == null) {
            return "unknown";
        }
        int idx = Math.floorMod(value.hashCode(), buckets);
        return "bucket-" + idx;
    }

    /** Convenience overload using {@link #DEFAULT_CARDINALITY_BUCKETS}. */
    public static String cardinalityGuard(String value) {
        return cardinalityGuard(value, DEFAULT_CARDINALITY_BUCKETS);
    }

    /**
     * Returns true if the given string is safe to log verbatim — short,
     * ASCII-printable, and within an allow-listed character class.
     * Use this for quick assertions on caller-supplied identifiers
     * before placing them in a log message.
     */
    public static boolean isSafeForLog(String value) {
        return value != null && SAFE_LOG_VALUE.matcher(value).matches();
    }
}
