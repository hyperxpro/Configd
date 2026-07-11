package io.configd.client;

import io.configd.distribution.wire.WatchCursor;

import java.util.Optional;

/**
 * Persistence for a resume cursor — the per-shard {@link WatchCursor} vector a driver <b>MUST</b> persist and
 * re-send on reconnect. The cursor is vector-native even at {@code N = 1} (epoch {@code 1}, a
 * one-element {@code (gid=0, S)}); a scalar-cursor assumption is forbidden.
 *
 * <p>Ship the in-memory {@link InMemoryCursorStore} for tests and ephemeral clients; a durable deployment
 * supplies its own (a file, a local KV) so a cursor survives a process restart. The store is inert until the
 * subscribe/watch gates persist through it; it is defined here so the config surface is stable.
 */
public interface CursorStore {

    /** Persists {@code cursor} under {@code key} (a stable per-subscription/per-watch identifier). */
    void save(String key, WatchCursor cursor);

    /** Loads the cursor previously saved under {@code key}, or empty if none. */
    Optional<WatchCursor> load(String key);
}
