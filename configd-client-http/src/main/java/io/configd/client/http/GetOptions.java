package io.configd.client.http;

import java.util.Objects;

/**
 * Options for a {@link ConfigdHttpClient#get} (§04 D3/D7). {@code scope} defaults to {@link Scope#GLOBAL};
 * {@code consistency} defaults to {@link Consistency#STALE}. Requesting {@link Consistency#LINEARIZABLE} on an
 * ordinary key MAY return a {@code 503} the client follows/retries (§05); a server-side strong-read key is
 * always served linearizably (and fails closed) regardless of this field (§04 D3-5, invisible to the driver).
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
