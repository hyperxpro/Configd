package io.configd.client.edge;

import io.configd.distribution.wire.WatchCursor;

import java.util.Optional;

/**
 * Watch options: resume position, persistence, and connection sharing. Only from-now watches may share
 * (cursored/persisted shares are refused: shared drain has single position, cannot honor independent resume).
 */
public record WatchOptions(Optional<WatchCursor> resumeFrom, Optional<String> persistenceKey,
                           Optional<Watch> shareConnectionOf) {

    public WatchOptions {
        resumeFrom = resumeFrom == null ? Optional.empty() : resumeFrom;
        persistenceKey = persistenceKey == null ? Optional.empty() : persistenceKey;
        shareConnectionOf = shareConnectionOf == null ? Optional.empty() : shareConnectionOf;
    }

    public static WatchOptions defaults() {
        return new WatchOptions(Optional.empty(), Optional.empty(), Optional.empty());
    }

    public WatchOptions persistUnder(String key) {
        return new WatchOptions(resumeFrom, Optional.of(key), shareConnectionOf);
    }

    public WatchOptions resume(WatchCursor cursor) {
        return new WatchOptions(Optional.of(cursor), persistenceKey, shareConnectionOf);
    }

    public WatchOptions shareConnectionOf(Watch other) {
        return new WatchOptions(resumeFrom, persistenceKey, Optional.of(other));
    }

    boolean isFromNow() {
        return resumeFrom.isEmpty() && persistenceKey.isEmpty();
    }
}
