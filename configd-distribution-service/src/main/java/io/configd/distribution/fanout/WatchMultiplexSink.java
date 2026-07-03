package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;
import io.configd.store.ConfigMutation;

import java.util.ArrayList;
import java.util.List;

/**
 * One shard's watch multiplex/filter veneer: a {@link TransportSink} <b>decorator</b> placed
 * between one per-shard {@link FanOutSessionCore} and the connection's real transport sink. Its
 * core drains that <b>one shard's</b> signed chain forward (one cursor, one ack); this decorator
 * <b>translates</b> the core's structured output into <b>per-watch</b> {@code WATCH_*} frames, each
 * <b>filtered by the watch's target</b> and <b>tagged with this sink's shard {@code gid}</b>.
 *
 * <p>At {@code N = 1} there is one sink (gid 0) over one core - byte-identical to the single-shard
 * drain. At {@code N > 1} the connection owns one sink per shard; the two cross-shard
 * frames - {@code WATCH_CREATED} (a vector of N {@link EdgeFrame.ShardMode}s) and
 * {@code WATCH_PROGRESS} (an N-component cursor vector) - cannot be built from one shard's state, so
 * this sink <b>forwards</b> its {@code SUBSCRIBE_OK} and its idle {@code HEARTBEAT} to the
 * driver-side {@link Coordinator}, which coalesces across the shards.
 *
 * <h2>Two modes (one decorator, always installed)</h2>
 * <ul>
 *   <li><b>Legacy passthrough</b> ({@code watchConnection == false}, the default): every
 *       {@code offer}/{@code close} delegates verbatim. A legacy {@code SUBSCRIBE}-first
 *       connection never flips the flag, so its {@code SUBSCRIBE_OK}/{@code NOTIFY}/
 *       {@code HEARTBEAT}/{@code SNAPSHOT_*}/{@code ERROR_CLOSE} pass through unchanged -
 *       the {@code 0x01} byte-identity guarantee.</li>
 *   <li><b>Watch translation</b> ({@code watchConnection == true}): the core's structured
 *       frames are mapped per the translation table below.</li>
 * </ul>
 *
 * <h2>Translation table (W5-*)</h2>
 * <pre>
 *   SUBSCRIBE_OK(latestSeq, mode)      -&gt; Coordinator.onShardCreated(gid, latestSeq, mode)
 *                                         (collected for the ONE coalesced WATCH_CREATED the driver
 *                                         emits after seeding every shard - a per-shard sink cannot
 *                                         build the N-ShardMode vector)
 *   NOTIFY([CommitNotification])       -&gt; per live watch, per notification: filter target; if any
 *                                         change matches, WATCH_EVENT(watchId, gid, seq, commitTs, changes)
 *                                         (one event per shard-commit, W5-6; never split/coalesce)
 *   HEARTBEAT(latestSeq, serverNow)    -&gt; Coordinator.onIdleProgress(serverNow) (the driver emits ONE
 *                                         coalesced N-component WATCH_PROGRESS per live watch; each
 *                                         component is that shard's drained cursor, the W5-7 clamp)
 *   SNAPSHOT_{BEGIN,CHUNK,END}         -&gt; WATCH_SNAPSHOT_{BEGIN,CHUNK,END}(snapshotOwner, gid, ...)
 *                                         (this shard's catch-up maps to the FIXED drain-owning
 *                                         watch - captured once, not per-frame, W5-5/F2; the snapshot
 *                                         BYTES are pre-filtered to that watch's target by
 *                                         FilteringReplaySource, W5-10/W7-4; v1 boundary)
 *   ERROR_CLOSE(DEMOTED_TO_CATCHUP)    -&gt; passthrough (the connection-level demotion notice the
 *                                         core offers; W8-6 - a driver MUST tolerate it)
 *   close(code, msg)                   -&gt; per live watch: WATCH_CANCELED(watchId, code, -, msg)
 *                                         (surfaces a connection-level terminal, incl.
 *                                         GAP_UNRECOVERABLE, as a per-watch terminal, W5-9/W6-4),
 *                                         then delegate.close
 * </pre>
 * The per-watch terminals that originate in the <b>router</b> - {@code NOT_AUTHORIZED} rejects,
 * the coalesced {@code WATCH_CREATED}, and the coalesced {@code WATCH_PROGRESS} - are NOT produced
 * here; the driver emits them directly via {@link #offerWatchFrame(EdgeFrame)} (which bypasses
 * translation).
 *
 * <h2>v1 boundary (W8-6 shared drain)</h2>
 * All watches on a connection share the per-shard core drains, <b>one</b> cursor per shard, and
 * <b>one</b> connection-level backpressure fate. The drains start at the <b>first</b> watch's resume
 * cursor (demuxed per shard); a watch that needs to resume from an independent position MUST use a
 * separate connection. Backpressure is per-connection ({@code CURSOR_ACK} is a connection-level
 * scalar broadcast to every shard core), so a single slow/greedy shard substream can demote every
 * sibling - the head-of-line blocking inherent to one shared mTLS transport. Per-watch/per-shard
 * flow-control / fairness is the named v2 extension W10-8.
 *
 * <h2>Threading</h2>
 * {@code watchConnection} is set by the reader thread (the first {@code WATCH_CREATE} decides
 * the connection type) and read by the session thread in {@link #offer}; it is therefore
 * {@code volatile}. Everything else (the registry, the {@link Coordinator} callbacks, all
 * translation) is session-thread-confined - a shard's core only calls {@code offer}/{@code close}
 * during the driver's single-threaded sequential sweep on the session-loop thread.
 */
final class WatchMultiplexSink implements TransportSink {

    /**
     * The cross-shard coalescing seam. A per-shard sink knows only its own shard, so the two frames
     * that carry an N-shard vector are built by the driver: a shard's {@code SUBSCRIBE_OK} is
     * collected for the one coalesced {@code WATCH_CREATED}, and a shard's idle heartbeat triggers
     * the one coalesced {@code WATCH_PROGRESS}. Both callbacks run on the session thread.
     */
    interface Coordinator {

        /**
         * A shard core acknowledged its {@code SUBSCRIBE_OK}; record its initial mode for the
         * coalesced {@code WATCH_CREATED} the driver emits after every covered shard is seeded.
         */
        void onShardCreated(int gid, long latestSeq, EdgeFrame.Mode mode);

        /**
         * A shard core's idle heartbeat cadence fired; emit ONE coalesced {@code WATCH_PROGRESS}
         * per live watch (deduped so several idle shards in one sweep produce a single frame).
         *
         * @return the transport offer result - {@code false} means the outbound would block, which
         *         the triggering core reads as transport-gone (preserving the prior single-shard
         *         close-on-refused-heartbeat behavior exactly at {@code N = 1})
         */
        boolean onIdleProgress(long serverNowMillis);
    }

    private final TransportSink delegate;
    private final WatchRegistry registry;

    /** This sink's shard group id - stamped on every {@code WATCH_EVENT} / {@code WATCH_SNAPSHOT_*}. */
    private final int gid;

    /** The cross-shard coalescer (the driver); receives this shard's SUBSCRIBE_OK and idle heartbeat. */
    private final Coordinator coordinator;

    /** Reader-set, session-read: false means legacy passthrough (byte-identical), true means translate. */
    private volatile boolean watchConnection;

    /**
     * Session-thread-only: the watch that <b>owns this shard's drain</b> (the first authorized
     * watch, whose {@code onSubscribe} started the drain) - the id every {@code WATCH_SNAPSHOT_*}
     * frame from this shard is tagged with. Captured ONCE when the drain starts, NOT re-evaluated
     * per frame: a snapshot transfer pauses across ticks on backpressure, and if the owner cancels
     * mid-transfer, a per-frame re-pick would flip to a sibling that was acked {@code TAIL} -
     * mis-attributing the snapshot to a watch promised none (W2-8 / W5-5). Tagging the fixed owner
     * means a snapshot to a since-canceled owner is simply discarded by the client (its watch is
     * gone), never mis-delivered to a live sibling.
     */
    private long snapshotOwnerWatchId = WatchRegistry.NO_WATCH;

    /** Session-thread-only close-once guard (the core closes at most once; defensive). */
    private boolean closed;

    WatchMultiplexSink(TransportSink delegate, WatchRegistry registry, int gid, Coordinator coordinator) {
        this.delegate = delegate;
        this.registry = registry;
        this.gid = gid;
        this.coordinator = coordinator;
    }

    // -----------------------------------------------------------------------
    // Router-facing controls (driver, session thread - except setWatchConnection)
    // -----------------------------------------------------------------------

    /** Flips this connection to watch translation (reader thread, on the first WATCH_CREATE). */
    void setWatchConnection(boolean watch) {
        this.watchConnection = watch;
    }

    /** True iff this connection has been flipped to watch translation. */
    boolean isWatchConnection() {
        return watchConnection;
    }

    /**
     * Records the drain-owning watch id that every {@code WATCH_SNAPSHOT_*} frame from this shard is
     * tagged with (the first authorized watch; see {@link #snapshotOwnerWatchId}). Session-thread-
     * only, set once when this shard's drain starts.
     */
    void setSnapshotOwner(long watchId) {
        this.snapshotOwnerWatchId = watchId;
    }

    /**
     * Emits a router-originated {@code WATCH_*} frame (a {@code WATCH_CREATED} ack for a
     * subsequent watch, or a {@code WATCH_CANCELED} reject/cancel) straight to the transport,
     * bypassing translation. These are already client-facing watch frames; translation only
     * applies to the core's connection-level output.
     */
    boolean offerWatchFrame(EdgeFrame frame) {
        return delegate.offer(frame);
    }

    // -----------------------------------------------------------------------
    // TransportSink - the core's outbound boundary
    // -----------------------------------------------------------------------

    @Override
    public boolean offer(EdgeFrame frame) {
        if (!watchConnection) {
            return delegate.offer(frame); // legacy passthrough - byte-identical
        }
        return translate(frame);
    }

    @Override
    public void close(ErrorCode code, String message) {
        if (closed) {
            return;
        }
        closed = true;
        if (watchConnection) {
            // Surface a connection-level terminal (e.g. GAP_UNRECOVERABLE, W6-4; SERVER_SHUTDOWN)
            // as a per-watch terminal for every live watch (W5-9) before the connection dies.
            for (WatchRegistry.WatchEntry e : registry.liveEntries()) {
                delegate.offer(new EdgeFrame.WatchCanceled(e.watchId(), code, null, message));
            }
        }
        delegate.close(code, message);
    }

    // -----------------------------------------------------------------------
    // Translation (session thread)
    // -----------------------------------------------------------------------

    private boolean translate(EdgeFrame frame) {
        return switch (frame) {
            case EdgeFrame.SubscribeOk ok -> translateSubscribeOk(ok);
            case EdgeFrame.Notify n -> translateNotify(n);
            case EdgeFrame.Heartbeat hb -> coordinator.onIdleProgress(hb.serverNowMillis());
            case EdgeFrame.SnapshotBegin sb -> delegate.offer(new EdgeFrame.WatchSnapshotBegin(
                    snapshotOwnerWatchId, gid, sb.snapshotSeq(), sb.chunkCount(), sb.totalBytes()));
            case EdgeFrame.SnapshotChunk sc -> delegate.offer(new EdgeFrame.WatchSnapshotChunk(
                    snapshotOwnerWatchId, gid, sc.index(), sc.bytes()));
            case EdgeFrame.SnapshotEnd se -> delegate.offer(new EdgeFrame.WatchSnapshotEnd(
                    snapshotOwnerWatchId, gid, se.snapshotSeq()));
            // The only ErrorClose the core OFFERS is the non-fatal DEMOTED_TO_CATCHUP notice
            // (terminal closes go via close()). Forward it verbatim - a driver MUST tolerate
            // the connection-level demotion (W8-6); the WATCH_SNAPSHOT_* catch-up follows.
            case EdgeFrame.ErrorClose ec -> delegate.offer(ec);
            // Defensive: any other frame (none expected from the core on a watch connection)
            // forwards verbatim rather than being dropped.
            default -> delegate.offer(frame);
        };
    }

    private boolean translateSubscribeOk(EdgeFrame.SubscribeOk ok) {
        // A per-shard sink cannot build the N-ShardMode WATCH_CREATED vector; forward this shard's
        // initial mode to the driver, which emits the ONE coalesced WATCH_CREATED after seeding
        // every covered shard. Reads latestSeq + mode; ignores the 0x03 filtered bit (always false
        // on the watch plane - the watch cores drive fullStore/empty-prefixes, so no server filter).
        coordinator.onShardCreated(gid, Math.max(0L, ok.latestSeq()), ok.mode());
        return true;
    }

    private boolean translateNotify(EdgeFrame.Notify n) {
        // Fan this shard's NOTIFY out to each live watch, filtered by its target. One WATCH_EVENT per
        // (matching) shard-commit (W5-6) - never split, never coalesced - tagged with this shard's gid.
        // A watch whose target has no key on this shard matches nothing here (a KEY on another shard),
        // so per-shard cores need no per-watch coverage subsetting for delivery. A refused delegate.offer
        // is the W8-6 shared-fate backpressure: return false so the core demotes (clears in-flight,
        // snapshots); the undelivered tail is re-driven and the driver dedups by S (W6-1). Iterate
        // watches outer / notifications inner so each (watch_id, gid) substream stays contiguous and
        // ascending in S.
        for (WatchRegistry.WatchEntry entry : registry.liveEntries()) {
            WatchTarget target = entry.target();
            for (CommitNotification cn : n.notifications()) {
                List<EdgeFrame.WatchChange> changes = filter(cn, target);
                if (changes.isEmpty()) {
                    continue; // no matching key for this watch - cursor advances via the next event / progress
                }
                EdgeFrame.WatchEvent event = new EdgeFrame.WatchEvent(
                        entry.watchId(), gid, cn.seq(), cn.commitTimestampMillis(), changes);
                if (!delegate.offer(event)) {
                    return false; // would block - core demotes (W8-6); remaining events via snapshot resync
                }
            }
        }
        return true; // all matching events accepted (or nothing matched: vacuously accepted)
    }

    /**
     * The per-watch routing filter (W5-6): the matching changes of one shard-commit for one
     * target. Distinct from authorization (the gate already authorized the whole target) -
     * this is pure routing over the already-verified, server-authoritative stream.
     */
    private static List<EdgeFrame.WatchChange> filter(CommitNotification cn, WatchTarget target) {
        List<ConfigMutation> mutations = cn.delta().mutations();
        List<EdgeFrame.WatchChange> changes = new ArrayList<>(mutations.size());
        for (ConfigMutation m : mutations) {
            if (!target.matches(m.key())) {
                continue;
            }
            if (m instanceof ConfigMutation.Put put) {
                // valueUnsafe() is the store's internal array; WatchChange.put clones it once.
                changes.add(EdgeFrame.WatchChange.put(put.key(), put.valueUnsafe()));
            } else { // ConfigMutation.Delete
                changes.add(EdgeFrame.WatchChange.delete(m.key()));
            }
        }
        return changes;
    }
}
