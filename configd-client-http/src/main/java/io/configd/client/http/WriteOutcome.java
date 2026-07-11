package io.configd.client.http;

/**
 * The result of a committed {@link ConfigdHttpClient#put} / {@link ConfigdHttpClient#delete}: the
 * applied-mutation cursor {@code seq}, parsed from the response <b>body</b> ({@code Committed: seq=<N>}) --
 * not a header (the single most common data-plane driver bug; the read version, by contrast, is the
 * {@code X-Config-Version} header). Both are the same monotonic per-shard applied-mutation sequence: a
 * {@code put} returning {@code seq=N} means an immediate linearizable read of that key returns
 * {@code X-Config-Version: N}.
 *
 * @param seq the applied-mutation sequence assigned to this write
 */
public record WriteOutcome(long seq) {

    public WriteOutcome {
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be non-negative: " + seq);
        }
    }
}
