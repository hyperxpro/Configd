package io.configd.client.edge.session;

import io.configd.client.ConfigdException;
import io.configd.client.CursorStore;
import io.configd.client.edge.ConfigChange;
import io.configd.client.edge.InboundFrameHandler;
import io.configd.client.edge.WatchEvent;
import io.configd.client.edge.WatchTarget;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.WatchCursor;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Watch state machine over one connection: one watch_id, potentially multi-shard. Driver merge: dedup by
 * (gid,S), advance per-shard cursors, per-key/per-shard-ordered events only. Request(n) backpressure.
 */
public final class WatchSession implements InboundFrameHandler, Flow.Publisher<WatchEvent> {

    private static final byte WIRE = EdgeFrameCodec.EDGE_WIRE_VERSION_V2;
    private static final int DELIVERY_BUFFER_CAP = 4096;

    private final WatchTarget target;
    private final SignedChainVerifier verifier;
    private final io.configd.client.HostileServerLimits limits;
    private final CursorStore cursorStore;
    private final String cursorKey;

    private final AtomicLong watchIdSeq = new AtomicLong(1);
    private volatile java.util.function.LongSupplier watchIdMinter = watchIdSeq::getAndIncrement;
    private volatile long watchId;
    private volatile EdgeConnection connection;
    private volatile boolean forceRebootstrap;

    private volatile java.util.function.Consumer<ConfigdException> onWatchTerminal = this::failConnection;
    private volatile java.util.function.LongConsumer onWatchIdAssigned = id -> {
    };
    private volatile boolean resetCursorOnConnect;

    private final ConcurrentHashMap<Integer, Long> cursor = new ConcurrentHashMap<>();
    private final Map<Integer, SnapshotReassembler> catchUp = new ConcurrentHashMap<>();

    private final java.util.concurrent.CompletableFuture<Void> created = new java.util.concurrent.CompletableFuture<>();
    private final java.util.concurrent.CompletableFuture<Void> watchTerminal = new java.util.concurrent.CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final AtomicReference<Flow.Subscriber<? super WatchEvent>> subscriber = new AtomicReference<>();
    private final Object deliverLock = new Object();
    private final ArrayDeque<WatchEvent> buffer = new ArrayDeque<>();
    private long demand;       // guarded by deliverLock
    private boolean cancelled; // guarded by deliverLock
    private final java.util.concurrent.LinkedBlockingQueue<WatchEvent> blockingQueue =
            new java.util.concurrent.LinkedBlockingQueue<>();

    public WatchSession(WatchTarget target, SignedChainVerifier verifier,
                        io.configd.client.HostileServerLimits limits,
                        CursorStore cursorStore, String cursorKey, Optional<WatchCursor> resumeFrom) {
        this.target = target;
        this.verifier = verifier;
        this.limits = limits;
        this.cursorStore = cursorStore;
        this.cursorKey = cursorKey;
        this.demand = Long.MAX_VALUE; // unbounded until a reactive subscriber takes control
        // Seed the cursor: an explicit resume, else the persisted cursor, else from-now (empty).
        WatchCursor start = resumeFrom.orElseGet(() ->
                cursorKey == null ? WatchCursor.fromNow() : cursorStore.load(cursorKey).orElse(WatchCursor.fromNow()));
        for (WatchCursor.Component c : start.components()) {
            cursor.put(c.gid(), c.s());
        }
    }

    /** The current live cursor vector (a snapshot). */
    public WatchCursor cursorVector() {
        return buildCursor();
    }

    /** Completes when the watch is authorized and live (WATCH_CREATED), or exceptionally on a terminal. */
    public java.util.concurrent.CompletableFuture<Void> created() {
        return created;
    }

    /**
     * Completes exceptionally when this watch permanently ends — a per-watch reject, or its (dedicated)
     * connection giving up — and normally on {@link #close()}. Unlike a dedicated session's connection terminal,
     * this fires for a watch terminated on a SHARED connection whose siblings keep streaming.
     */
    public java.util.concurrent.CompletableFuture<Void> watchTerminal() {
        return watchTerminal;
    }

    /** Blocks up to {@code timeoutMs} for the next event, or null if none (drains the blocking facade queue). */
    public WatchEvent poll(long timeoutMs) throws InterruptedException {
        WatchEvent e = blockingQueue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        EdgeConnection c = connection;
        if (c != null) {
            c.wakeReader(); // a drained blocking consumer un-parks the (backpressure-gated) reader
        }
        return e;
    }

    public long watchId() {
        return watchId;
    }

    public void close() {
        closed.set(true);
        watchTerminal.complete(null); // a user cancel is a normal end
        EdgeConnection c = connection;
        if (c != null) {
            try {
                c.send(new EdgeFrame.WatchCancel(watchId), WIRE); // best-effort cancel
            } catch (IOException ignored) {
                // the connection is going away anyway
            }
        }
    }

    /** Called by the client when the session permanently gave up: error the stream. */
    public void onClientGaveUp(ConfigdException error) {
        if (!created.isDone()) {
            created.completeExceptionally(error);
        }
        watchTerminal.completeExceptionally(error);
        failSubscriber(error);
    }

    /**
     * Switches this watch to shared-connection mode: it (re)starts from-now on every (re)connect (no independent
     * resume on a shared drain) and routes its own terminals to {@code perWatchTerminator} — which
     * ends only this watch and leaves the connection + sibling watches alive — instead of failing the
     * whole connection.
     */
    public void shareOn(java.util.function.Consumer<ConfigdException> perWatchTerminator,
                        java.util.function.LongConsumer watchIdSink,
                        java.util.function.LongSupplier watchIdMinter) {
        this.resetCursorOnConnect = true;
        this.onWatchTerminal = perWatchTerminator;
        this.onWatchIdAssigned = watchIdSink;   // register the (fresh) watch_id BEFORE the WATCH_CREATE is sent
        this.watchIdMinter = watchIdMinter;     // mint from the connection-shared sequence (unique per connection)
    }

    /** Ends only this watch's consumer (reactive subscriber / awaitCreated) without touching the connection. */
    public void errorStream(ConfigdException error) {
        closed.set(true);
        if (!created.isDone()) {
            created.completeExceptionally(error);
        }
        watchTerminal.completeExceptionally(error);
        failSubscriber(error);
    }

    /** Re-creates this watch on the SAME connection (a shared-connection Gap/Stale re-bootstrap). */
    public void reBootstrapOnSameConnection(EdgeConnection conn) {
        forceRebootstrap = true;
        onConnected(conn);
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void subscribe(Flow.Subscriber<? super WatchEvent> sub) {
        java.util.Objects.requireNonNull(sub, "subscriber");
        synchronized (deliverLock) {
            if (subscriber.get() != null) {
                sub.onSubscribe(new NoopSubscription());
                sub.onError(new IllegalStateException("a watch supports a single reactive subscriber"));
                return;
            }
            // Move any events buffered before this subscribe (blocking-facade mode) into the reactive buffer,
            // preserving order, THEN publish the subscriber so emit() routes subsequent events to the buffer.
            WatchEvent e;
            while ((e = blockingQueue.poll()) != null) {
                buffer.addLast(e);
            }
            demand = 0;
            subscriber.set(sub);
        }
        sub.onSubscribe(new ReactiveSubscription());
    }

    private final class ReactiveSubscription implements Flow.Subscription {
        @Override
        public void request(long n) {
            if (n <= 0) {
                failSubscriber(new IllegalArgumentException("request(n) requires n > 0"));
                return;
            }
            synchronized (deliverLock) {
                demand = demand + n < 0 ? Long.MAX_VALUE : demand + n;
            }
            drain();
            EdgeConnection c = connection;
            if (c != null) {
                c.wakeReader();
            }
        }

        @Override
        public void cancel() {
            synchronized (deliverLock) {
                cancelled = true;
            }
        }
    }

    private static final class NoopSubscription implements Flow.Subscription {
        @Override
        public void request(long n) {
        }

        @Override
        public void cancel() {
        }
    }

    /** (Re)creates the watch on a freshly authenticated connection (fresh watch_id, saved cursor). */
    public void onConnected(EdgeConnection conn) {
        this.connection = conn;
        conn.pinVersion(WIRE);
        catchUp.clear();
        this.watchId = watchIdMinter.getAsLong();
        onWatchIdAssigned.accept(watchId); // register for demux BEFORE sending, so the reply routes race-free
        int flags = target.flagBits();
        WatchCursor resume;
        if (forceRebootstrap) {
            // Re-bootstrap: drop the cursor, ask for a fresh snapshot + tail (the watch-plane-native recovery).
            forceRebootstrap = false;
            cursor.clear();
            resume = WatchCursor.fromNow();
            flags |= EdgeFrame.WATCH_FLAG_WITH_INITIAL_SNAPSHOT;
        } else if (resetCursorOnConnect) {
            // A shared-connection watch has no independent resume: always (re)start from-now, honoring
            // the target's own flags (e.g. WITH_INITIAL_SNAPSHOT).
            cursor.clear();
            resume = WatchCursor.fromNow();
        } else {
            resume = buildCursor();
        }
        EdgeFrame.WatchCreate create = new EdgeFrame.WatchCreate(
                watchId, target.scope(), target.targetKindByte(), target.pathBytes(), resume, flags);
        try {
            conn.send(create, WIRE);
            conn.armIdleDeadline();
        } catch (IOException io) {
            conn.fail(new io.configd.client.UnavailableException("failed to send WATCH_CREATE: " + io.getMessage(), io));
        }
    }

    @Override
    public void onFrame(EdgeFrame frame) {
        try {
            switch (frame) {
                case EdgeFrame.WatchCreated wc -> handleWatchCreated(wc);
                case EdgeFrame.WatchEvent we -> handleWatchEvent(we);
                case EdgeFrame.WatchProgress wp -> handleWatchProgress(wp);
                case EdgeFrame.WatchSnapshotBegin b -> reassemblerFor(b.gid()).begin(
                        new EdgeFrame.SnapshotBegin(b.snapshotSeq(), b.chunkCount(), b.totalBytes()));
                case EdgeFrame.WatchSnapshotChunk c -> reassemblerFor(c.gid()).chunk(
                        new EdgeFrame.SnapshotChunk(c.index(), c.bytes()));
                case EdgeFrame.WatchSnapshotEnd e -> handleWatchSnapshotEnd(e);
                case EdgeFrame.Notify n -> handleFullChainNotify(n); // full_chain_verify mode
                default -> {
                    // Other frames (SUBSCRIBE_OK etc.) do not belong to the watch plane; ignore benignly.
                }
            }
        } catch (ConfigdException fatal) {
            onWatchTerminal.accept(fatal);
        }
    }

    @Override
    public void onCatchUp() {
        // DEMOTED_TO_CATCHUP: a snapshot flow heals us; keep the connection and ingest it.
    }

    @Override
    public void onPerWatch(ConfigdException watchError) {
        // A WATCH_CANCELED per-watch terminal (NOT_AUTHORIZED / GAP_UNRECOVERABLE / STALE_TOPOLOGY), classified
        // by the connection's dispatcher. On a dedicated connection this fails the whole connection (its only
        // watch) so the EdgeSession reconnect logic decides (Forbidden ⇒ terminal, Gap/Stale ⇒ re-bootstrap);
        // on a shared connection the installed terminator ends only this watch and leaves siblings streaming.
        onWatchTerminal.accept(watchError);
    }

    @Override
    public void onCancelAck() {
        // The expected acknowledgement of our own WATCH_CANCEL (SERVER_SHUTDOWN on WATCH_CANCELED); the watch
        // is done. We already closed the session on cancel(); nothing more to do.
        closed.set(true);
    }

    @Override
    public void onTerminal(ConfigdException terminal) {
        if (terminal instanceof io.configd.client.GapUnrecoverableException
                || terminal instanceof io.configd.client.StaleTopologyException) {
            forceRebootstrap = true;
        }
        catchUp.clear();
    }

    @Override
    public boolean wantsMoreFrames() {
        // Park the reader (→ the server's slow-consumer governor demotes us) when the consumer, reactive or
        // blocking, has fallen a full buffer behind. Only one of the two queues is populated at a time.
        synchronized (deliverLock) {
            return buffer.size() < DELIVERY_BUFFER_CAP && blockingQueue.size() < DELIVERY_BUFFER_CAP;
        }
    }

    private void handleWatchCreated(EdgeFrame.WatchCreated wc) {
        for (EdgeFrame.ShardMode sm : wc.shards()) {
            cursor.putIfAbsent(sm.gid(), 0L); // record the covered shard set (from-now components at 0)
            // A SNAPSHOT_FIRST shard will be caught up by a WATCH_SNAPSHOT_* substream.
        }
        if (!created.isDone()) {
            created.complete(null);
        }
    }

    private void handleWatchEvent(EdgeFrame.WatchEvent we) {
        long known = cursor.getOrDefault(we.gid(), 0L);
        if (we.s() <= known) {
            return; // at-least-once dedup by (gid, S)
        }
        cursor.put(we.gid(), we.s());
        List<ConfigChange> changes = new ArrayList<>(we.changes().size());
        for (EdgeFrame.WatchChange ch : we.changes()) {
            changes.add(ch.isDelete()
                    ? ConfigChange.delete(ch.key(), we.s())
                    : ConfigChange.put(ch.key(), ch.value(), we.s()));
        }
        emit(new WatchEvent(we.gid(), we.s(), we.commitTs(), changes));
        ackAndPersist();
    }

    private void handleWatchProgress(EdgeFrame.WatchProgress wp) {
        for (WatchCursor.Component c : wp.cursor().components()) {
            cursor.merge(c.gid(), c.s(), Math::max); // advance idle components; never regress
        }
        ackAndPersist();
    }

    private void handleWatchSnapshotEnd(EdgeFrame.WatchSnapshotEnd e) {
        SnapshotReassembler r = reassemblerFor(e.gid());
        ConfigSnapshot snapshot = r.end(new EdgeFrame.SnapshotEnd(e.snapshotSeq())); // throws → failConnection
        catchUp.remove(e.gid());
        long seq = e.snapshotSeq();
        long known = cursor.getOrDefault(e.gid(), 0L);
        if (seq < known) {
            return; // backward snapshot — never regress
        }
        // Deliver the snapshot's (prefix-filtered) entries as PUT events for this shard, then resume tailing.
        List<ConfigChange> changes = new ArrayList<>();
        snapshot.data().forEach((key, vv) -> changes.add(ConfigChange.put(key, vv.valueUnsafe(), seq)));
        cursor.put(e.gid(), seq);
        if (!changes.isEmpty()) {
            emit(new WatchEvent(e.gid(), seq, 0L, changes));
        }
        ackAndPersist();
    }

    private void handleFullChainNotify(EdgeFrame.Notify n) {
        for (CommitNotification notification : n.notifications()) {
            ConfigDelta delta = notification.delta();
            verifier.verify(delta); // ChainVerificationException → failConnection (fail-closed)
            verifier.recordApplied(delta);
            List<ConfigChange> matching = new ArrayList<>();
            for (ConfigMutation m : delta.mutations()) {
                if (!target.matches(m.key())) {
                    continue;
                }
                matching.add(switch (m) {
                    case ConfigMutation.Put put -> ConfigChange.put(put.key(), put.valueUnsafe(), delta.toVersion());
                    case ConfigMutation.Delete del -> ConfigChange.delete(del.key(), delta.toVersion());
                });
            }
            // The full-chain plane is single-group today (gid 0); the notification seq is the applied S.
            cursor.merge(0, notification.seq(), Math::max);
            if (!matching.isEmpty()) {
                emit(new WatchEvent(0, notification.seq(), notification.commitTimestampMillis(), matching));
            }
        }
        ackAndPersist();
    }

    private SnapshotReassembler reassemblerFor(int gid) {
        return catchUp.computeIfAbsent(gid, g -> new SnapshotReassembler(limits));
    }

    private void emit(WatchEvent event) {
        synchronized (deliverLock) {
            if (subscriber.get() == null) {
                blockingQueue.offer(event); // blocking-facade mode; bounded via wantsMoreFrames
                return;
            }
            buffer.addLast(event);
        }
        drain();
    }

    private void drain() {
        Flow.Subscriber<? super WatchEvent> sub = subscriber.get();
        synchronized (deliverLock) {
            if (sub == null) {
                return;
            }
            while (!cancelled && demand > 0 && !buffer.isEmpty()) {
                WatchEvent e = buffer.pollFirst();
                demand--;
                try {
                    sub.onNext(e);
                } catch (RuntimeException ex) {
                    cancelled = true;
                    break;
                }
            }
        }
    }

    /** Sends the connection-level CURSOR_ACK (max applied S across shards) and persists the cursor vector. */
    private void ackAndPersist() {
        long maxS = 0L;
        for (long s : cursor.values()) {
            maxS = Math.max(maxS, s);
        }
        EdgeConnection c = connection;
        if (c != null) {
            try {
                c.send(new EdgeFrame.CursorAck(maxS), WIRE);
            } catch (IOException ignored) {
                // reconnect re-creates and re-acks
            }
        }
        if (cursorKey != null) {
            cursorStore.save(cursorKey, buildCursor());
        }
    }

    /** Builds the wire cursor: components sorted by UNSIGNED gid ascending; empty ⇒ from-now. */
    private WatchCursor buildCursor() {
        List<WatchCursor.Component> components = new ArrayList<>(cursor.size());
        cursor.forEach((gid, s) -> components.add(new WatchCursor.Component(gid, s)));
        components.sort(Comparator.comparingLong(c -> Integer.toUnsignedLong(c.gid())));
        return components.isEmpty() ? WatchCursor.fromNow() : new WatchCursor(components);
    }

    private void failConnection(ConfigdException error) {
        EdgeConnection c = connection;
        if (c != null) {
            c.fail(error);
        }
    }

    private void failSubscriber(Throwable error) {
        Flow.Subscriber<? super WatchEvent> sub = subscriber.get();
        synchronized (deliverLock) {
            if (sub != null && !cancelled) {
                cancelled = true;
                sub.onError(error);
            }
        }
    }
}
