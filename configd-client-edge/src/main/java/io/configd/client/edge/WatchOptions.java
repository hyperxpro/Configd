package io.configd.client.edge;

import io.configd.distribution.wire.WatchCursor;

import java.util.Optional;

/**
 * Options for a {@link ConfigdEdgeClient#watch} call: the resume position, where it is persisted, and whether it
 * shares a connection.
 *
 * @param resumeFrom     an explicit resume cursor vector, or empty to resume from the persisted cursor (or
 *                       from-now if none). A from-now watch starts at each shard's current {@code S} and
 *                       delivers only future changes (W3-4) — request {@code WITH_INITIAL_SNAPSHOT} on the
 *                       target to also get the existing state.
 * @param persistenceKey the {@code CursorStore} key under which the evolving cursor vector is saved for durable
 *                       resume across restarts, or empty for an ephemeral (non-persisted) watch.
 * @param shareConnectionOf an existing watch whose connection this new watch should share (§06 W6-4). Only two
 *                       <b>from-now</b> watches may share a connection; a cursored / persisted watch requested to
 *                       share is refused with an {@link IllegalStateException} (W8-6a) because a shared drain has
 *                       a single position and cannot honour an independent resume (F10-1b). Empty ⇒ the default:
 *                       a dedicated connection.
 */
public record WatchOptions(Optional<WatchCursor> resumeFrom, Optional<String> persistenceKey,
                           Optional<Watch> shareConnectionOf) {

    public WatchOptions {
        resumeFrom = resumeFrom == null ? Optional.empty() : resumeFrom;
        persistenceKey = persistenceKey == null ? Optional.empty() : persistenceKey;
        shareConnectionOf = shareConnectionOf == null ? Optional.empty() : shareConnectionOf;
    }

    /** From-now, ephemeral (not persisted), dedicated connection. */
    public static WatchOptions defaults() {
        return new WatchOptions(Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Persist the evolving cursor under {@code key} for durable resume. */
    public WatchOptions persistUnder(String key) {
        return new WatchOptions(resumeFrom, Optional.of(key), shareConnectionOf);
    }

    /** Resume explicitly from {@code cursor} (overrides the persisted / from-now start). */
    public WatchOptions resume(WatchCursor cursor) {
        return new WatchOptions(Optional.of(cursor), persistenceKey, shareConnectionOf);
    }

    /** Share {@code other}'s connection (both must be from-now, else the watch() call is refused, W8-6a). */
    public WatchOptions shareConnectionOf(Watch other) {
        return new WatchOptions(resumeFrom, persistenceKey, Optional.of(other));
    }

    /** True iff this watch is from-now (no explicit resume, no durable persistence) — a share prerequisite. */
    boolean isFromNow() {
        return resumeFrom.isEmpty() && persistenceKey.isEmpty();
    }
}
