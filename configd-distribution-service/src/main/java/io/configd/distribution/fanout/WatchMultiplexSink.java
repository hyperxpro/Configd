package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;
import io.configd.distribution.wire.WatchCursor;
import io.configd.store.ConfigMutation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The watch multiplex/filter veneer: a {@link TransportSink} <b>decorator</b> placed
 * between the untouched {@link FanOutSessionCore} and the real transport sink. The core
 * drains the single connection-level signed chain forward exactly as before (one shard at
 * N=1, one cursor, one ack, connection-level snapshot / heartbeat / backpressure - the W8-6
 * per-connection shared fate); this decorator <b>translates</b> the core's structured output
 * frames into <b>per-watch</b> {@code WATCH_*} frames, each <b>filtered by its target</b>.
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
 *   SUBSCRIBE_OK(latestSeq, mode)      -&gt; WATCH_CREATED(pendingWatch, [ShardMode(0, latestSeq, mode)])
 *   NOTIFY([CommitNotification])       -&gt; per live watch, per notification: filter target; if any
 *                                         change matches, WATCH_EVENT(watchId, 0, seq, commitTs, changes)
 *                                         (one event per shard-commit, W5-6; never split/coalesce)
 *   HEARTBEAT(latestSeq, serverNow)    -&gt; per live watch: WATCH_PROGRESS(watchId, [(0, drainedS)], serverNow)
 *                                         where drainedS is the core's drained cursor, NOT the raw
 *                                         latestSeq (the W5-7 upper-bound / no-silent-gap clamp)
 *   SNAPSHOT_{BEGIN,CHUNK,END}         -&gt; WATCH_SNAPSHOT_{BEGIN,CHUNK,END}(snapshotOwner, 0, ...)
 *                                         (the connection-level catch-up maps to the FIXED drain-
 *                                         owning watch - captured once, not per-frame, W5-5/F2;
 *                                         the snapshot BYTES are pre-filtered to that watch's
 *                                         target by FilteringReplaySource, W5-10/W7-4; v1
 *                                         single-snapshotting-watch boundary)
 *   ERROR_CLOSE(DEMOTED_TO_CATCHUP)    -&gt; passthrough (the connection-level demotion notice the
 *                                         core offers; W8-6 - a driver MUST tolerate it)
 *   close(code, msg)                   -&gt; per live watch: WATCH_CANCELED(watchId, code, -, msg)
 *                                         (surfaces a connection-level terminal, incl.
 *                                         GAP_UNRECOVERABLE, as a per-watch terminal, W5-9/W6-4),
 *                                         then delegate.close
 * </pre>
 * The per-watch terminals that originate in the <b>router</b> - {@code NOT_AUTHORIZED} rejects
 * and subsequent-watch {@code WATCH_CREATED} acks - are NOT produced here; the driver emits
 * them directly via {@link #offerWatchFrame(EdgeFrame)} (which bypasses translation).
 *
 * <h2>v1 boundary (W8-6 shared drain)</h2>
 * All watches on a connection share <b>one</b> core drain, <b>one</b> cursor, and <b>one</b>
 * backpressure fate. The connection drain starts at the <b>first</b> watch's resume cursor;
 * a watch that needs to resume from an independent position MUST use a separate connection.
 * Backpressure is per-connection ({@code CURSOR_ACK} is a connection-level scalar), so a
 * single slow/greedy watch can demote every sibling - head-of-line blocking inherent to one
 * shared mTLS transport. Per-watch flow-control / fairness is the named v2 extension W10-8.
 *
 * <h2>Threading</h2>
 * {@code watchConnection} is set by the reader thread (the first {@code WATCH_CREATE} decides
 * the connection type) and read by the session thread in {@link #offer}; it is therefore
 * {@code volatile}. Everything else ({@code pendingCreateWatchId}, the registry, all
 * translation) is session-thread-confined - the core only calls {@code offer}/{@code close}
 * on its single session-loop thread, and the driver posts the router state changes as session
 * commands.
 */
final class WatchMultiplexSink implements TransportSink {

    /** The single-shard group id at N=1 (W3-5: the one-element vector is {@code (0, S)}). */
    private static final int GID_0 = 0;

    private final TransportSink delegate;
    private final WatchRegistry registry;

    /**
     * The core's drained cursor supplier - {@code () -> session.cursor()}. Read lazily (only
     * during translation, long after construction) so it can be wired before the session is
     * constructed. It is the W5-7 clamp source: the seq the edge has actually drained
     * (verified + filtered), never the raw {@code HEARTBEAT.latestSeq} which may run ahead.
     */
    private final LongSupplier drainedCursor;

    /** Reader-set, session-read: false means legacy passthrough (byte-identical), true means translate. */
    private volatile boolean watchConnection;

    /**
     * Session-thread-only: the watch awaiting the connection-level {@code SUBSCRIBE_OK} to its
     * {@code WATCH_CREATED}. Set by the driver immediately before it drives the first
     * authorized watch's {@code onSubscribe}; consumed by the next {@code SUBSCRIBE_OK}.
     */
    private long pendingCreateWatchId = WatchRegistry.NO_WATCH;

    /**
     * Session-thread-only: the watch that <b>owns the shared connection drain</b> (the first
     * authorized watch, whose {@code onSubscribe} started the drain) - the id every
     * {@code WATCH_SNAPSHOT_*} frame is tagged with. Captured ONCE when the drain starts, NOT
     * re-evaluated per frame: a connection-level snapshot transfer pauses across ticks on
     * backpressure, and if the owner cancels mid-transfer, {@code firstLiveWatchId()} would flip to
     * a sibling that was acked {@code TAIL} - mis-attributing the snapshot to a watch promised none
     * (W2-8 / W5-5). Tagging the fixed owner means a snapshot to a since-canceled owner is simply
     * discarded by the client (its watch is gone), never mis-delivered to a live sibling.
     */
    private long snapshotOwnerWatchId = WatchRegistry.NO_WATCH;

    /** Session-thread-only close-once guard (the core closes at most once; defensive). */
    private boolean closed;

    WatchMultiplexSink(TransportSink delegate, WatchRegistry registry, LongSupplier drainedCursor) {
        this.delegate = delegate;
        this.registry = registry;
        this.drainedCursor = drainedCursor;
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
     * Arms the next {@code SUBSCRIBE_OK} to be translated into a {@code WATCH_CREATED} for
     * {@code watchId} (the first authorized watch, whose {@code onSubscribe} the driver is
     * about to drive). Session-thread-only.
     */
    void expectWatchCreated(long watchId) {
        this.pendingCreateWatchId = watchId;
    }

    /**
     * Records the drain-owning watch id that every {@code WATCH_SNAPSHOT_*} frame is tagged with
     * (the first authorized watch; see {@link #snapshotOwnerWatchId}). Session-thread-only, set
     * once when the shared drain starts.
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
            case EdgeFrame.Heartbeat hb -> translateHeartbeat(hb);
            case EdgeFrame.SnapshotBegin sb -> delegate.offer(new EdgeFrame.WatchSnapshotBegin(
                    snapshotOwnerWatchId, GID_0, sb.snapshotSeq(), sb.chunkCount(), sb.totalBytes()));
            case EdgeFrame.SnapshotChunk sc -> delegate.offer(new EdgeFrame.WatchSnapshotChunk(
                    snapshotOwnerWatchId, GID_0, sc.index(), sc.bytes()));
            case EdgeFrame.SnapshotEnd se -> delegate.offer(new EdgeFrame.WatchSnapshotEnd(
                    snapshotOwnerWatchId, GID_0, se.snapshotSeq()));
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
        long watchId = pendingCreateWatchId;
        pendingCreateWatchId = WatchRegistry.NO_WATCH;
        EdgeFrame.ShardMode shard =
                new EdgeFrame.ShardMode(GID_0, Math.max(0L, ok.latestSeq()), ok.mode());
        return delegate.offer(new EdgeFrame.WatchCreated(watchId, List.of(shard)));
    }

    private boolean translateNotify(EdgeFrame.Notify n) {
        // Fan one connection-level NOTIFY out to each live watch, filtered by its target. One
        // WATCH_EVENT per (matching) shard-commit (W5-6) - never split, never coalesced. A
        // refused delegate.offer is the W8-6 shared-fate backpressure: return false so the core
        // demotes (clears in-flight, snapshots); the undelivered tail is re-driven and the
        // driver dedups by S (W6-1). Iterate watches outer / notifications inner so each
        // (watch_id, gid) substream stays contiguous and ascending in S.
        for (WatchRegistry.WatchEntry entry : registry.liveEntries()) {
            WatchTarget target = entry.target();
            for (CommitNotification cn : n.notifications()) {
                List<EdgeFrame.WatchChange> changes = filter(cn, target);
                if (changes.isEmpty()) {
                    continue; // no matching key for this watch - cursor advances via the next event / progress
                }
                EdgeFrame.WatchEvent event = new EdgeFrame.WatchEvent(
                        entry.watchId(), GID_0, cn.seq(), cn.commitTimestampMillis(), changes);
                if (!delegate.offer(event)) {
                    return false; // would block - core demotes (W8-6); remaining events via snapshot resync
                }
            }
        }
        return true; // all matching events accepted (or nothing matched: vacuously accepted)
    }

    private boolean translateHeartbeat(EdgeFrame.Heartbeat hb) {
        // The bookmark (W5-7): carry the drained cursor (verified + filtered frontier), clamped
        // to never exceed it - NOT the raw HEARTBEAT.latestSeq, which can run ahead of what the
        // edge has examined (a bookmark past unexamined commits would be a silent gap, W6-1).
        long drainedS = Math.max(0L, drainedCursor.getAsLong());
        WatchCursor cursor = WatchCursor.of(GID_0, drainedS);
        for (WatchRegistry.WatchEntry entry : registry.liveEntries()) {
            if (!delegate.offer(new EdgeFrame.WatchProgress(entry.watchId(), cursor, hb.serverNowMillis()))) {
                return false; // shared-fate backpressure (W8-6)
            }
        }
        return true;
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
