package io.configd.client.http;

import java.util.Objects;

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
