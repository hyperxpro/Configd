package io.configd.client.http;

/**
 * The configuration scope (§01 A2, §04 D7). Sent as the exact-match {@code ?scope=<NAME>} query parameter; the
 * server parses it case-insensitively and fails a request with an unknown value ({@code 400}). Absent ⇒
 * {@link #GLOBAL} (the default, and the only scope a v1 deployment actually routes — D7-4). Unlike
 * {@code ?consistency=}, {@code ?scope=} is matched by an <b>exact</b> parameter parse (§04 D3-4/D7-3).
 */
public enum Scope {
    GLOBAL,
    REGIONAL,
    LOCAL
}
