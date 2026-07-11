package io.configd.client.http;

import java.util.Objects;

/** Options for a {@link ConfigdHttpClient#put} / {@link ConfigdHttpClient#delete}. */
public record WriteOptions(Scope scope) {

    public WriteOptions {
        Objects.requireNonNull(scope, "scope");
    }

    public static WriteOptions defaults() {
        return new WriteOptions(Scope.GLOBAL);
    }

    public WriteOptions scope(Scope scope) {
        return new WriteOptions(scope);
    }
}
