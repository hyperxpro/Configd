package io.configd.client.http;

/**
 * Read consistency: STALE (local bounded-staleness, default) or LINEARIZABLE (leader-confirmed).
 * Server matches with query.contains("consistency=linearizable") (substring, not parse): client must emit
 * exactly that literal for LINEARIZABLE, no parameter for STALE, never compose it into other query positions.
 */
public enum Consistency {
    STALE,
    LINEARIZABLE
}
