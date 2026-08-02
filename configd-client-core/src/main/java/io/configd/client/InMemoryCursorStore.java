package io.configd.client;

import io.configd.distribution.wire.WatchCursor;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
