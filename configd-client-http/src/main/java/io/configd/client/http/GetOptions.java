package io.configd.client.http;

import java.util.Objects;

/**
 * Options for a {@link ConfigdHttpClient#get}. {@code scope} defaults to {@link Scope#GLOBAL};
 * {@code consistency} defaults to {@link Consistency#STALE}. Requesting {@link Consistency#LINEARIZABLE} on an
 * ordinary key may return a {@code 503} that the client follows and retries; a server-side strong-read key is
 * always served linearizably (and fails closed) regardless of this field -- invisible to the driver.
 */
public record GetOptions(Scope scope, Consistency consistency) {

    public GetOptions {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(consistency, "consistency");
    }

    public static GetOptions defaults() {
        return new GetOptions(Scope.GLOBAL, Consistency.STALE);
    }

    public GetOptions scope(Scope scope) {
        return new GetOptions(scope, consistency);
    }

    public GetOptions consistency(Consistency consistency) {
        return new GetOptions(scope, consistency);
    }
}
