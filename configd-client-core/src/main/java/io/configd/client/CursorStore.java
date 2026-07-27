package io.configd.client;

import io.configd.distribution.wire.WatchCursor;

import java.util.Optional;

/**
 * Persistence for the resume cursor — the per-shard {@link WatchCursor} vector. Must persist and re-send on
 * reconnect. Cursor is vector-native even at N=1; scalar-cursor assumption is forbidden. Store is inert until
 * subscribe/watch gates persist through it.
 */
public interface CursorStore {

    void save(String key, WatchCursor cursor);

    Optional<WatchCursor> load(String key);
}
