package io.configd.client.http;

/**
 * The requested read consistency (§04 D3-4). {@link #STALE} (the default) is a bounded-staleness local read;
 * {@link #LINEARIZABLE} opts into a leader-confirmed read via the exact query literal
 * {@code consistency=linearizable}.
 *
 * <p><b>The loose-substring trap (§04 D3-4).</b> The server matches linearizable with
 * {@code query.contains("consistency=linearizable")} — a substring test, not an exact parse. The client
 * therefore emits <b>exactly</b> {@code consistency=linearizable} for {@link #LINEARIZABLE} and emits
 * <b>no</b> consistency parameter for {@link #STALE} (there is no "stale" keyword; stale is the absence of the
 * match), and it never composes the literal into any other query position (an over-trigger would silently
 * upgrade an unrelated read to a costlier linearizable one).
 */
public enum Consistency {
    STALE,
    LINEARIZABLE
}
