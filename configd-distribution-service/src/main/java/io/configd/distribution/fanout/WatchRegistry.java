package io.configd.distribution.fanout;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The per-connection watch table: maps a client-assigned {@code watch_id} to its live
 * {@link WatchEntry}, and remembers <b>every</b> id ever used so a {@code watch_id} is
 * <b>never reused</b> for the connection's lifetime - even after the prior watch is canceled
 * or closed. The no-reuse rule removes the late-frame-misattribution hazard the multiplex
 * would otherwise admit: a stray in-flight frame from a canceled watch can never be
 * mistaken for a freshly-created one.
 *
 * <h2>Threading</h2>
 * <b>Session-thread-confined.</b> Every mutation ({@link #register}/{@link #cancel}) and every
 * read/iteration ({@link #liveEntries}/{@link #liveCount}) happens on the
 * {@link FanOutConnectionDriver}'s single session-loop thread: the reader thread only
 * <em>posts</em> {@code WATCH_CREATE}/{@code WATCH_CANCEL} as session commands, and the
 * {@link WatchMultiplexSink} translation that iterates the live entries runs inside the core
 * {@code tick}/{@code onSubscribe} on that same thread. No locking is therefore required (the
 * same single-writer discipline that protects the non-thread-safe {@code FanOutSessionCore}).
 */
final class WatchRegistry {

    /** The "no watch" sentinel (e.g. an unset pending-create / snapshot-owner id). */
    static final long NO_WATCH = -1L;

    /** Live watches, in creation order (deterministic multiplex fan-out / snapshot ownership). */
    private final Map<Long, WatchEntry> live = new LinkedHashMap<>();

    /** Every {@code watch_id} ever registered on this connection (never reused). */
    private final Set<Long> everUsed = new HashSet<>();

    /** True iff {@code watchId} has ever been used on this connection (live OR canceled). */
    boolean isUsed(long watchId) {
        return everUsed.contains(watchId);
    }

    /**
     * Registers a new live watch. The caller MUST have already rejected a reused id
     * ({@link #isUsed}); this method records the id in {@code everUsed} regardless so a future
     * reuse is caught even if the entry is later canceled.
     */
    void register(WatchEntry entry) {
        live.put(entry.watchId(), entry);
        everUsed.add(entry.watchId());
    }

    /**
     * Cancels (removes) the live watch; the id stays in {@code everUsed} (no reuse).
     *
     * @return the removed entry, or {@code null} if no live watch had that id
     */
    WatchEntry cancel(long watchId) {
        return live.remove(watchId);
    }

    /** The live entry for {@code watchId}, or {@code null} if not live. */
    WatchEntry get(long watchId) {
        return live.get(watchId);
    }

    /** The live entries in creation order (read-only; iterated on the session thread only). */
    Collection<WatchEntry> liveEntries() {
        return Collections.unmodifiableCollection(live.values());
    }

    /** True iff no watch is currently live. */
    boolean isEmpty() {
        return live.isEmpty();
    }

    /** The number of live watches. */
    int liveCount() {
        return live.size();
    }

    /**
     * The number of distinct {@code watch_id}s ever used on this connection - the no-reuse budget.
     * Because {@code everUsed} never shrinks, the driver bounds it (a per-connection
     * watch-id budget) so a long-lived connection with high watch churn cannot grow it unbounded.
     */
    int totalUsed() {
        return everUsed.size();
    }

    /**
     * One live watch. Immutable; the per-connection shared drain means a watch carries
     * no independent cursor/queue state - only its identity, principal, target, the covered shard
     * set, and the resume seq it requested (used only by the FIRST watch to position the shared
     * per-shard core drains).
     *
     * @param watchId      the client-assigned multiplex id
     * @param principal    the authenticated identity that created (and is authorized for) it
     * @param roles        the asserted roles at creation ({@code Set.of()} on the cert-DN edge)
     * @param target       the authorized watch target (the per-watch routing filter)
     * @param coveredGids  the shard gids this target scatters to (ascending), the client-facing
     *                     narrowing for {@code WATCH_CREATED} / {@code WATCH_PROGRESS}: one element
     *                     for KEY, all shards for PREFIX/FULL. At {@code N = 1} it is {@code {0}}.
     *                     Coverage is target-driven, never cursor-inferred.
     * @param startCursorS the requested resume seq {@code S} (the {@code gid=0} cursor
     *                     component, or 0 for "from now"); positions only the first watch's drain
     * @param flags        the raw {@code WATCH_CREATE} flag byte (diagnostic / forward-compat)
     */
    record WatchEntry(long watchId, String principal, Set<String> roles, WatchTarget target,
                      int[] coveredGids, long startCursorS, int flags) {
    }
}
