package io.configd.client;

import io.configd.distribution.wire.WatchCursor;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The default {@link CursorStore}: a process-local {@link ConcurrentHashMap}. Cursors do not survive a
 * restart; a durable deployment supplies its own implementation. Thread-safe.
 */
public final class InMemoryCursorStore implements CursorStore {

    private final ConcurrentHashMap<String, WatchCursor> cursors = new ConcurrentHashMap<>();

    @Override
    public void save(String key, WatchCursor cursor) {
        cursors.put(key, cursor);
    }

    @Override
    public Optional<WatchCursor> load(String key) {
        return Optional.ofNullable(cursors.get(key));
    }
}
