package io.configd.client.http;

import java.util.Optional;

/**
 * The result of a {@link ConfigdHttpClient#get} (§04 D3). A {@code 404} is a <b>definite</b> answer, not an
 * error: it yields {@link #found()} == false with an empty {@link #value()} (§04 D3-3, §05 R6-1). A present key
 * with an empty value is {@code found() == true} with a zero-length {@code value()} — distinct from absent by
 * the {@link #found()} flag (§04 D2-7/D3-3).
 *
 * @param value      the raw value bytes when the key is present, else empty (a defensive copy; opaque bytes,
 *                   §04 D3-1)
 * @param version    the key's version from the {@code X-Config-Version} <b>header</b> (§04 D3-2/D6-1); {@code -1}
 *                   when absent
 * @param strongRead {@code true} iff the response carried {@code X-Strong-Read: true} — a leader-confirmed-fresh
 *                   read of a server-classified strong-read key (§04 D3-5/D3-5a); rely on this, not the key name
 * @param requested  the consistency the client requested (echo of {@code X-Consistency}); NOT a freshness proof
 *                   for an ordinary key (§04 D3-2a) — only {@code strongRead} certifies freshness
 */
public record GetResult(Optional<byte[]> value, long version, boolean strongRead, Consistency requested) {

    public GetResult {
        value = value == null ? Optional.empty() : value.map(byte[]::clone);
    }

    /** A present key. */
    public static GetResult present(byte[] value, long version, boolean strongRead, Consistency requested) {
        return new GetResult(Optional.of(value), version, strongRead, requested);
    }

    /** An absent key ({@code 404}). */
    public static GetResult absent(Consistency requested) {
        return new GetResult(Optional.empty(), -1L, false, requested);
    }

    /** Whether the key is present in the store (a {@code 200}); {@code false} for a {@code 404}. */
    public boolean found() {
        return value.isPresent();
    }

    /** The value bytes (a fresh copy), or throws if the key is absent — guard with {@link #found()}. */
    public byte[] valueOrThrow() {
        return value.map(byte[]::clone)
                .orElseThrow(() -> new java.util.NoSuchElementException("key is absent (404)"));
    }
}
