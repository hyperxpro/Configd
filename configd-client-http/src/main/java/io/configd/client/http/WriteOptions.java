package io.configd.client.http;

import java.util.Objects;

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
