package io.configd.client.http;

/**
 * Configuration scope: sent as exact-match ?scope=<NAME> (server parses case-insensitively, fails unknown 400).
 * Absent = GLOBAL (default). Matched by exact parameter parse, unlike ?consistency= (substring).
 */
public enum Scope {
    GLOBAL,
    REGIONAL,
    LOCAL
}
