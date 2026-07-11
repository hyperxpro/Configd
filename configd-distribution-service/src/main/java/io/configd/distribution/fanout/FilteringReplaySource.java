package io.configd.distribution.fanout;

import io.configd.distribution.ReplaySource;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * A {@link ReplaySource} decorator that filters the catch-up <b>snapshot</b> to a key predicate
 * before it is streamed - either a single watch's target as {@code
 * WATCH_SNAPSHOT_*}, or a filtered legacy SUBSCRIBE session's prefix set as
 * {@code SNAPSHOT_*}, so the catch-up snapshot is narrowed the same way the live tail is.
 *
 * <p><b>Why this exists (the read-authz hole it closes).</b> The watch veneer drives the shared
 * connection core with a <b>full-store</b> subscribe, so the core's catch-up snapshot
 * ({@code SNAPSHOT_FIRST}) is the <b>whole store</b>. The per-{@code NOTIFY} filter narrows the
 * live tail to each watch's target, but the snapshot is a <b>separate</b> server-to-client path:
 * left unfiltered, a watch authorized for one key receives <b>every</b> key on its first snapshot -
 * a read-authorization bypass around the subscription gate (INV-WATCH-READ: a watch must never
 * expose a key a plain read would deny). This decorator
 * filters the snapshot to the drain-owning watch's target so the snapshot exposes only what the
 * live path would, with the <b>same literal match</b> ({@link WatchTarget#matches}).
 *
 * <p><b>Match-all means no filter.</b> A FULL or {@code full_chain_verify} target was gated by a
 * root-scope grant, so it is authorized for the whole store; filtering is skipped (the
 * snapshot passes through whole, and the rebuild is avoided). A {@code null} target - a legacy
 * (non-watch) connection, or a watch connection before the drain-owner is known - also passes
 * through, preserving the legacy fan-out's byte-identical whole-store snapshot.
 *
 * <p><b>Shared-drain boundary.</b> The connection has ONE shared drain, so the filter is the
 * <b>drain-owning (first) watch's</b> target. A connection-level re-snapshot (demotion) is
 * therefore filtered to the drain-owner - a sibling watch with a different target does not receive
 * a tailored re-snapshot (it must reconnect). This is a correctness boundary, not a leak: the
 * snapshot never exposes a key beyond the drain-owner's authorized target.
 *
 * <p><b>Threading.</b> {@link #setTarget} (driver, in {@code handleWatchCreate} before the first
 * {@code onSubscribe}) and {@link #replayFromSnapshot} (the core, inside {@code tick}) both run on
 * the {@link FanOutConnectionDriver}'s single session thread - session-thread-confined, no locking.
 */
final class FilteringReplaySource implements ReplaySource {

    private final ReplaySource delegate;

    /** The snapshot key filter; {@code null} means passthrough (legacy connection, or not yet set). */
    private Predicate<String> predicate;

    FilteringReplaySource(ReplaySource delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Sets the filter to a watch drain-owner's target. A {@code null} or
     * {@linkplain WatchTarget#isMatchAll() match-all} (FULL / {@code full_chain_verify}) target
     * leaves the snapshot whole (passthrough). Preserves the watch-plane behavior exactly.
     */
    void setTarget(WatchTarget target) {
        this.predicate = (target == null || target.isMatchAll()) ? null : target::matches;
    }

    /**
     * Sets the filter to an arbitrary key predicate - the filtered legacy SUBSCRIBE session's
     * prefix-plus-strong-read matcher. A {@code null} predicate leaves the snapshot
     * whole (passthrough).
     */
    void setPredicate(Predicate<String> predicate) {
        this.predicate = predicate;
    }

    @Override
    public Replay replayFromSnapshot() {
        Replay replay = delegate.replayFromSnapshot();
        Predicate<String> p = this.predicate;
        if (p == null) {
            return replay; // legacy / FULL / full_chain_verify: whole store (root-authorized)
        }
        ConfigSnapshot snap = replay.snapshot();
        // Rebuild a snapshot carrying ONLY the matching keys - the same literal match the
        // per-NOTIFY / drain filter uses - preserving the snapshot's version/seq (the resume
        // floor is the same point in history; only the content is narrowed).
        var acc = new Object() {
            HamtMap<String, VersionedValue> map = HamtMap.empty();
        };
        snap.data().forEach((key, value) -> {
            if (p.test(key)) {
                acc.map = acc.map.put(key, value);
            }
        });
        ConfigSnapshot filtered = new ConfigSnapshot(acc.map, snap.version(), snap.timestamp());
        return new Replay(filtered, replay.seq());
    }
}
