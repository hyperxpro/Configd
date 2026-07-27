package io.configd.client.http;

/**
 * Result of committed write: seq parsed from response body (Committed: seq=<N>), not header.
 * Same monotonic per-shard applied-mutation sequence as X-Config-Version in linearizable read.
 */
public record WriteOutcome(long seq) {

    public WriteOutcome {
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be non-negative: " + seq);
        }
    }
}
