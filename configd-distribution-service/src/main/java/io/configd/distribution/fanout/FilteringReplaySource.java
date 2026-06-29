package io.configd.distribution.fanout;

import io.configd.distribution.ReplaySource;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import java.util.Objects;

/**
 * A {@link ReplaySource} decorator that filters the catch-up <b>snapshot</b> to a single watch's
 * target (RFC §2 W5-10 / W7-4) before it is streamed as {@code WATCH_SNAPSHOT_*}.
 *
 * <p><b>Why this exists (the read-authz hole it closes).</b> The watch veneer drives the shared
 * connection core with a <b>full-store</b> subscribe, so the core's catch-up snapshot
 * ({@code SNAPSHOT_FIRST}) is the <b>whole store</b>. The per-{@code NOTIFY} filter narrows the
 * live tail to each watch's target, but the snapshot is a <b>separate</b> server→client path: left
 * unfiltered, a watch authorized for one key receives <b>every</b> key on its first snapshot — a
 * read-authorization bypass <i>around</i> the subscription gate (INV-WATCH-READ, W7-4). This
 * decorator filters the snapshot to the drain-owning watch's target so the snapshot exposes only
 * what the live path would, with the <b>same literal match</b> ({@link WatchTarget#matches}).
 *
 * <p><b>Match-all ⇒ no filter.</b> A FULL or {@code full_chain_verify} target was gated by a
 * root-scope grant (W7-3), so it is authorized for the whole store; filtering is skipped (the
 * snapshot passes through whole, and the rebuild is avoided). A {@code null} target — a <b>legacy
 * (non-watch) connection</b>, or a watch connection before the drain-owner is known — also passes
 * through, preserving the legacy fan-out's byte-identical whole-store snapshot.
 *
 * <p><b>v1 boundary.</b> The connection has ONE shared drain (W8-6), so the filter is the
 * <b>drain-owning (first) watch's</b> target. A connection-level re-snapshot (demotion) is
 * therefore filtered to the drain-owner — a sibling watch with a different target does not receive
 * a tailored re-snapshot (it must reconnect). This is a correctness boundary, not a leak: the
 * snapshot never exposes a key beyond the drain-owner's <b>authorized</b> target.
 *
 * <p><b>Threading.</b> {@link #setTarget} (driver, in {@code handleWatchCreate} before the first
 * {@code onSubscribe}) and {@link #replayFromSnapshot} (the core, inside {@code tick}) both run on
 * the {@link FanOutConnectionDriver}'s single session thread — session-thread-confined, no locking
 * (the same single-writer discipline as the core / registry).
 */
final class FilteringReplaySource implements ReplaySource {

    private final ReplaySource delegate;

    /** The drain-owner's target; {@code null} ⇒ passthrough (legacy connection, or not yet set). */
    private WatchTarget target;

    FilteringReplaySource(ReplaySource delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Sets the filter target to the connection drain-owner's. A {@code null} or
     * {@linkplain WatchTarget#isMatchAll() match-all} (FULL / {@code full_chain_verify}) target
     * leaves the snapshot whole.
     */
    void setTarget(WatchTarget target) {
        this.target = target;
    }

    @Override
    public Replay replayFromSnapshot() {
        Replay replay = delegate.replayFromSnapshot();
        WatchTarget t = this.target;
        if (t == null || t.isMatchAll()) {
            return replay; // legacy / FULL / full_chain_verify → whole store (root-authorized)
        }
        ConfigSnapshot snap = replay.snapshot();
        // Rebuild a snapshot carrying ONLY the target's matching keys — the same literal match the
        // per-NOTIFY filter uses — preserving the snapshot's version/seq (the resume floor is the
        // same point in history; only the content is narrowed to the authorized target).
        var acc = new Object() {
            HamtMap<String, VersionedValue> map = HamtMap.empty();
        };
        snap.data().forEach((key, value) -> {
            if (t.matches(key)) {
                acc.map = acc.map.put(key, value);
            }
        });
        ConfigSnapshot filtered = new ConfigSnapshot(acc.map, snap.version(), snap.timestamp());
        return new Replay(filtered, replay.seq());
    }
}
