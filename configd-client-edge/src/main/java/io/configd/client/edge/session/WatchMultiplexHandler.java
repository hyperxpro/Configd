package io.configd.client.edge.session;

import io.configd.client.ConfigdException;
import io.configd.client.GapUnrecoverableException;
import io.configd.client.StaleTopologyException;
import io.configd.client.edge.InboundFrameHandler;
import io.configd.distribution.wire.EdgeFrame;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Multiplex several from-now watches on one shared connection. Demultiplexes by watch_id; per-watch
 * terminals affect only that watch, leave siblings streaming.
 */
public final class WatchMultiplexHandler implements InboundFrameHandler {

    private final ConcurrentHashMap<Long, WatchSession> byWatchId = new ConcurrentHashMap<>();
    private final List<WatchSession> all = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong watchIdSeq = new java.util.concurrent.atomic.AtomicLong(1);
    private volatile EdgeConnection connection;

    public void bindConnection(EdgeConnection conn) {
        this.connection = conn;
    }
    public void adopt(WatchSession watch) {
        // Keep the shared sequence ahead of the id the host already minted while dedicated (avoid a collision).
        watchIdSeq.updateAndGet(cur -> Math.max(cur, watch.watchId() + 1));
        all.add(watch);
        watch.shareOn(err -> terminateOne(watch, err), id -> byWatchId.put(id, watch), watchIdSeq::getAndIncrement);
        byWatchId.put(watch.watchId(), watch); // its id was already assigned while dedicated
    }

    public void add(WatchSession watch) {
        all.add(watch);
        watch.shareOn(err -> terminateOne(watch, err), id -> byWatchId.put(id, watch), watchIdSeq::getAndIncrement);
        EdgeConnection c = connection;
        if (c != null) {
            watch.onConnected(c);
        }
    }

    public void remove(WatchSession watch) {
        byWatchId.values().remove(watch);
        all.remove(watch);
    }

    public void onConnected(EdgeConnection conn) {
        this.connection = conn;
        byWatchId.clear();
        for (WatchSession w : all) {
            if (!w.isClosed()) {
                w.onConnected(conn);
            }
        }
    }

    @Override
    public void onFrame(EdgeFrame frame) {
        Long watchId = watchIdOf(frame);
        if (watchId == null) {
            return;
        }
        WatchSession w = byWatchId.get(watchId);
        if (w != null) {
            w.onFrame(frame); // an unknown watch_id (stale, superseded by a re-create) is dropped
        }
    }

    @Override
    public void onPerWatch(long watchId, ConfigdException watchError) {
        WatchSession w = byWatchId.get(watchId);
        if (w != null) {
            terminateOne(w, watchError);
        }
    }

    @Override
    public void onCancelAck(long watchId) {
        WatchSession w = byWatchId.remove(watchId);
        if (w != null) {
            all.remove(w);
        }
    }

    @Override
    public void onTerminal(ConfigdException terminal) {
        // A connection-fatal terminal affects every hosted watch. The EdgeSession's reconnect logic runs; on
        // reconnect onConnected re-creates them all (transparent). Propagate so a Gap/Stale connection-terminal
        // arms each watch's re-bootstrap flag before the re-create.
        for (WatchSession w : all) {
            w.onTerminal(terminal);
        }
    }

    @Override
    public boolean wantsMoreFrames() {
        // The shared reader parks when ANY hosted watch has fallen behind — connection-level backpressure
        // only; there is no per-watch flow isolation. CURSOR_ACK stays a connection scalar; the server
        // ignores a regressing ack, so each watch acking its own max is safe.
        for (WatchSession w : all) {
            if (!w.wantsMoreFrames()) {
                return false;
            }
        }
        return true;
    }

    /** Errors every hosted watch's stream — the shared session permanently gave up. */
    public void onClientGaveUp(ConfigdException error) {
        for (WatchSession w : all) {
            w.onClientGaveUp(error);
        }
    }

    int liveWatchCount() {
        return all.size();
    }

    private void terminateOne(WatchSession watch, ConfigdException error) {
        byWatchId.values().remove(watch);
        if (error instanceof GapUnrecoverableException || error instanceof StaleTopologyException) {
            // Per-watch re-bootstrap on the SAME connection; the siblings never notice.
            EdgeConnection c = connection;
            if (c != null && !watch.isClosed()) {
                watch.reBootstrapOnSameConnection(c); // re-registers its fresh watch_id via the shareOn sink
            }
        } else {
            // Forbidden / BadSubscribe / ChainVerification / ... — end ONLY this watch; siblings keep streaming.
            all.remove(watch);
            watch.errorStream(error);
        }
    }

    private static Long watchIdOf(EdgeFrame frame) {
        return switch (frame) {
            case EdgeFrame.WatchCreated wc -> wc.watchId();
            case EdgeFrame.WatchEvent we -> we.watchId();
            case EdgeFrame.WatchProgress wp -> wp.watchId();
            case EdgeFrame.WatchSnapshotBegin b -> b.watchId();
            case EdgeFrame.WatchSnapshotChunk c -> c.watchId();
            case EdgeFrame.WatchSnapshotEnd e -> e.watchId();
            default -> null;
        };
    }
}
