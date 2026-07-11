package io.configd.client.http;

/**
 * The configuration scope. Sent as the exact-match {@code ?scope=<NAME>} query parameter; the server parses
 * it case-insensitively and fails a request with an unknown value ({@code 400}). Absent means {@link #GLOBAL}
 * (the default, and the only scope actually routed today). Unlike {@code ?consistency=}, {@code ?scope=} is
 * matched by an <b>exact</b> parameter parse.
 */
public enum Scope {
    GLOBAL,
    REGIONAL,
    LOCAL
}
