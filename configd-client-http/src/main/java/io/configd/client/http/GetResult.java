package io.configd.client.http;

import java.util.Optional;

/**
 * 404 is definite (not error), yields found()==false. Present key with zero-length value is distinct (found()==true).
 * strongRead: true iff X-Strong-Read header set — leader-confirmed-fresh read of server-classified strong-read key.
 */
public record GetResult(Optional<byte[]> value, long version, boolean strongRead, Consistency requested) {

    public GetResult {
        value = value == null ? Optional.empty() : value.map(byte[]::clone);
    }

    public static GetResult present(byte[] value, long version, boolean strongRead, Consistency requested) {
        return new GetResult(Optional.of(value), version, strongRead, requested);
    }

    public static GetResult absent(Consistency requested) {
        return new GetResult(Optional.empty(), -1L, false, requested);
    }

    public boolean found() {
        return value.isPresent();
    }

    public byte[] valueOrThrow() {
        return value.map(byte[]::clone)
                .orElseThrow(() -> new java.util.NoSuchElementException("key is absent (404)"));
    }
}
