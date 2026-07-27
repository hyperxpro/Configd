package io.configd.client.edge;

import io.configd.client.ChainVerificationException;
import io.configd.client.ConfigdException;
import io.configd.client.CursorStore;
import io.configd.client.GapUnrecoverableException;
import io.configd.client.ProtocolViolationException;
import io.configd.client.edge.session.EdgeConnection;
import io.configd.client.edge.session.SignedChainVerifier;
import io.configd.client.edge.session.SnapshotReassembler;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.WatchCursor;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One full-store or prefix subscription over one {@link EdgeConnection}. It maintains a verified
 * {@link LocalConfigView} by applying the hydration snapshot and the signed delta chain (via
 * {@link SignedChainVerifier} / {@link SnapshotReassembler}), drives {@code CURSOR_ACK} flow-control, and
 * exposes the change stream two ways:
 *
 * <ul>
 *   <li><b>Reactive</b> — a single-subscriber {@link Flow.Publisher Flow.Publisher&lt;ConfigChange&gt;} whose
 *       {@code request(n)} demand gates the {@code CURSOR_ACK} cadence: a subscriber that stops requesting stops
 *       the client acking, so the server demotes it. No subscriber ⇒ the client drains and acks
 *       promptly (a good transport citizen) and the {@link #view()} stays current.</li>
 *   <li><b>Blocking</b> — {@link #view()} for point reads and {@link #awaitHydrated(Duration)} for the
 *       reference / conformance driver.</li>
 * </ul>
 *
 * <p>Fail-closed on a crypto-verification failure ({@link ChainVerificationException}, tears the connection
 * down); re-bootstrap on a chain gap or a truncated snapshot ({@link GapUnrecoverableException} — the client
 * reconnects and re-{@code SUBSCRIBE}s from scratch). Reconnect/resume are driven by {@link ConfigdEdgeClient}:
 * {@link #onConnected(EdgeConnection)} (re)sends the {@code SUBSCRIBE} at the persisted cursor.
 *
 * <p><b>Server-side filtered ({@code 0x03}) sessions.</b> When the {@code SUBSCRIBE_OK} confirms
 * filtering, the delivered chain is intentionally non-contiguous (the server drops whole out-of-prefix signed
 * deltas), so gap detection relaxes to <b>forward-only</b>: a delta whose {@code fromVersion} jumps <i>ahead</i>
 * of the applied version is the expected shape and is applied, and the cursor tracks a dense covered-S advanced
 * by the {@code HEARTBEAT}; only a position that <i>regresses below</i> the applied version is a genuine gap.
 * Every filtered behaviour is gated on the confirm bit, so a classic ({@code 0x01}/{@code 0x02}) session — or a
 * {@code 0x03} edge whose server is not filtering — keeps the strict-contiguity path byte-for-byte.
 */
public final class Subscription implements InboundFrameHandler, Flow.Publisher<ConfigChange>, AutoCloseable {

    private static final int DEFAULT_DELIVERY_BUFFER = 4096;

    private final SubscribeOptions options;
    private final boolean fullStore;
    private final List<String> prefixes;
    private final byte wireVersion;
    private final SignedChainVerifier verifier;
    private final SnapshotReassembler reassembler;
    private final LocalConfigView view;
    private final CursorStore cursorStore;
    private final String cursorKey;

    private volatile EdgeConnection connection;
    private volatile long cursor;               // highest applied notification seq S (the resume position)
    private volatile long latestServerSeq;      // server frontier, from SUBSCRIBE_OK/HEARTBEAT (staleness)
    private volatile boolean forceRebootstrap;  // re-SUBSCRIBE at 0 after a gap / truncated snapshot
    private volatile boolean snapshotExpected;  // a snapshot flow is coming (SNAPSHOT_FIRST or DEMOTED_TO_CATCHUP)
    private volatile boolean filtered;          // 0x03 server-side-filtered session: forward-only gap

    private final CompletableFuture<Long> hydrated = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Reactive delivery (single subscriber).
    private final AtomicReference<Flow.Subscriber<? super ConfigChange>> subscriber = new AtomicReference<>();
    private final Object deliverLock = new Object();
    private final ArrayDeque<Buffered> deliveryBuffer = new ArrayDeque<>();
    private final int deliveryBufferCap = DEFAULT_DELIVERY_BUFFER;
    private long demand;         // guarded by deliverLock
    private long deliveredSeq;   // highest notification seq fully delivered to the subscriber (guarded)
    private boolean cancelled;   // guarded by deliverLock

    private record Buffered(ConfigChange change, long ackSeq) {
        // ackSeq >= 0 iff this is the last change of its notification (delivering it advances deliveredSeq).
    }

    private Subscription(SubscribeOptions options, boolean fullStore, List<String> prefixes,
                         SignedChainVerifier verifier, SnapshotReassembler reassembler,
                         LocalConfigView view, CursorStore cursorStore) {
        this.options = options;
        this.fullStore = fullStore;
        this.prefixes = List.copyOf(prefixes);
        this.wireVersion = (options.acceptFiltered() && !fullStore)
                ? EdgeFrameCodec.EDGE_WIRE_VERSION_V3 : EdgeFrameCodec.EDGE_WIRE_VERSION;
        this.verifier = verifier;
        this.reassembler = reassembler;
        this.view = view;
        this.cursorStore = cursorStore;
        this.cursorKey = "sub:" + (fullStore ? "full" : String.join(",", this.prefixes));
        this.cursor = cursorStore.load(cursorKey).map(Subscription::scalarOf).orElse(0L);
        this.demand = Long.MAX_VALUE; // unbounded until a reactive subscriber takes control
    }

    static Subscription create(SubscribeOptions options, boolean fullStore, List<String> prefixes,
                               SignedChainVerifier verifier, SnapshotReassembler reassembler,
                               LocalConfigView view, CursorStore cursorStore) {
        return new Subscription(options, fullStore, prefixes, verifier, reassembler, view, cursorStore);
    }

    /** The verified, materialized local store. */
    public LocalConfigView view() {
        return view;
    }

    /** Blocks until the initial state is hydrated (snapshot applied, or a TAIL resume acknowledged). */
    public long awaitHydrated(Duration timeout) throws InterruptedException, TimeoutException {
        try {
            return hydrated.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ConfigdException ce) {
                throw ce;
            }
            throw new IllegalStateException("hydration failed", cause);
        }
    }

    /** The current resume position (highest applied notification seq). */
    public long cursor() {
        return cursor;
    }

    /** The server's latest known seq (frontier), for a staleness estimate. */
    public long serverFrontier() {
        return latestServerSeq;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        EdgeConnection c = connection;
        if (c != null) {
            c.close();
        }
        synchronized (deliverLock) {
            Flow.Subscriber<? super ConfigChange> s = subscriber.get();
            if (s != null && !cancelled) {
                cancelled = true;
            }
        }
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ConfigChange> sub) {
        java.util.Objects.requireNonNull(sub, "subscriber");
        if (!subscriber.compareAndSet(null, sub)) {
            sub.onSubscribe(new NoopSubscription());
            sub.onError(new IllegalStateException("a Subscription supports a single reactive subscriber"));
            return;
        }
        synchronized (deliverLock) {
            demand = 0; // the subscriber now controls demand via request(n)
        }
        sub.onSubscribe(new ReactiveSubscription());
    }

    private final class ReactiveSubscription implements Flow.Subscription {
        @Override
        public void request(long n) {
            if (n <= 0) {
                failSubscriber(new IllegalArgumentException("request(n) requires n > 0 (Reactive Streams §3.9)"));
                return;
            }
            synchronized (deliverLock) {
                demand = demand + n < 0 ? Long.MAX_VALUE : demand + n; // saturate
            }
            long acked = drainToSubscriber();
            EdgeConnection c = connection;
            if (c != null) {
                sendCursorAck(acked);
                c.wakeReader(); // freed buffer space / new demand — resume reads
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

    /** (Re)sends the {@code SUBSCRIBE} on a freshly authenticated connection, at the persisted resume cursor. */
    public void onConnected(EdgeConnection conn) {
        this.connection = conn;
        conn.pinVersion(wireVersion);
        reassembler.reset();
        long resume = forceRebootstrap ? 0L : cursor;
        forceRebootstrap = false;
        EdgeFrame.Subscribe subscribe = new EdgeFrame.Subscribe(
                fullStore, prefixes, WatchCursor.INITIAL_TOPOLOGY_EPOCH, resume, -1L,
                options.edgeId(), options.acceptFiltered() && !fullStore);
        try {
            conn.send(subscribe, wireVersion);
            conn.armIdleDeadline(); // now streaming — a HEARTBEAT-silence gap is a liveness failure
        } catch (IOException io) {
            conn.fail(new io.configd.client.UnavailableException("failed to send SUBSCRIBE: " + io.getMessage(), io));
        }
    }

    /** Called by the client when it has permanently given up (no more reconnects): terminate the stream. */
    public void onClientGaveUp(ConfigdException error) {
        if (!hydrated.isDone()) {
            hydrated.completeExceptionally(error);
        }
        failSubscriber(error);
    }

    @Override
    public void onFrame(EdgeFrame frame) {
        try {
            switch (frame) {
                case EdgeFrame.SubscribeOk ok -> handleSubscribeOk(ok);
                case EdgeFrame.Notify notify -> handleNotify(notify);
                case EdgeFrame.SnapshotBegin begin -> reassembler.begin(begin);
                case EdgeFrame.SnapshotChunk chunk -> reassembler.chunk(chunk);
                case EdgeFrame.SnapshotEnd end -> handleSnapshotEnd(end);
                default -> {
                }
            }
        } catch (ConfigdException fatal) {
            failConnection(fatal);
        }
    }

    @Override
    public void onHeartbeat(EdgeFrame.Heartbeat heartbeat) {
        this.latestServerSeq = heartbeat.latestSeq();
        // On filtered (0x03) session, latestSeq is the drained-through covered-S (everything matching prefixes
        // has been delivered or filtered out, not the raw buffer tip). Advance the dense cursor monotonically
        // so reconnect resumes near head and the ack watermark climbs past filtered-out skips.
        if (filtered && heartbeat.latestSeq() > cursor) {
            cursor = heartbeat.latestSeq();
            persistCursor();
            sendCursorAck(drainToSubscriber());
        }
    }

    @Override
    public void onCatchUp() {
        this.snapshotExpected = true;
    }

    @Override
    public void onTerminal(ConfigdException terminal) {
        // Gap or topology change: force re-bootstrap from cursor 0 on next reconnect.
        if (terminal instanceof GapUnrecoverableException
                || terminal instanceof io.configd.client.StaleTopologyException) {
            forceRebootstrap = true;
        }
        reassembler.reset();
    }

    @Override
    public boolean wantsMoreFrames() {
        synchronized (deliverLock) {
            return deliveryBuffer.size() < deliveryBufferCap;
        }
    }

    private void handleSubscribeOk(EdgeFrame.SubscribeOk ok) {
        this.latestServerSeq = ok.latestSeq();
        // Server's confirm selects filtered-stream mode: dense covered-S cursor and forward-only gap detection.
        // Gate filtered behaviour on this bit so classic sessions keep strict-contiguity byte-for-byte.
        this.filtered = ok.filtered();
        if (ok.mode() == EdgeFrame.Mode.TAIL) {
            if (!hydrated.isDone()) {
                hydrated.complete(cursor);
            }
        } else {
            snapshotExpected = true;
        }
    }

    private void handleSnapshotEnd(EdgeFrame.SnapshotEnd end) {
        ConfigSnapshot snapshot = reassembler.end(end);
        if (snapshot.version() < cursor) {
            sendCursorAck(cursor);
            return;
        }
        view.loadSnapshot(snapshot);
        cursor = snapshot.version();
        deliveredSeq = Math.max(deliveredSeq, cursor);
        snapshotExpected = false;
        persistCursor();
        if (!hydrated.isDone()) {
            hydrated.complete(cursor);
        }
        sendCursorAck(cursor);
    }

    private void handleNotify(EdgeFrame.Notify notify) {
        for (CommitNotification notification : notify.notifications()) {
            ConfigDelta delta = notification.delta();
            verifier.verify(delta);

            long currentVersion = view.currentVersion();
            if (delta.toVersion() <= currentVersion) {
                continue;
            }
            // Classic (0x01/0x02): gap if fromVersion != applied. Filtered (0x03): forward jump is OK
            // (out-of-prefix deltas dropped), only regression below applied is a gap. Forward-applied deltas
            // apply cleanly via applyDelta stamps to toVersion regardless of fromVersion.
            boolean gap = filtered
                    ? delta.fromVersion() < currentVersion
                    : delta.fromVersion() != currentVersion;
            if (gap) {
                forceRebootstrap = true;
                throw new GapUnrecoverableException("chain gap: delta.fromVersion " + delta.fromVersion()
                        + (filtered ? " < applied version " : " != applied version ") + currentVersion
                        + " — re-bootstrapping");
            }
            view.applyDelta(delta, notification.commitTimestampMillis());
            verifier.recordApplied(delta);
            cursor = notification.seq();
            bufferChanges(delta, notification.seq());
        }
        long acked = drainToSubscriber();
        persistCursor();
        sendCursorAck(acked);
    }

    private void bufferChanges(ConfigDelta delta, long notifSeq) {
        List<ConfigMutation> mutations = delta.mutations();
        synchronized (deliverLock) {
            for (int i = 0; i < mutations.size(); i++) {
                ConfigMutation m = mutations.get(i);
                boolean last = i == mutations.size() - 1;
                ConfigChange change = switch (m) {
                    case ConfigMutation.Put put -> ConfigChange.put(put.key(), put.valueUnsafe(), delta.toVersion());
                    case ConfigMutation.Delete del -> ConfigChange.delete(del.key(), delta.toVersion());
                };
                deliveryBuffer.addLast(new Buffered(change, last ? notifSeq : -1L));
            }
            if (mutations.isEmpty()) {
                deliveredSeqNoSubscriber(notifSeq);
            }
        }
    }

    private void deliveredSeqNoSubscriber(long notifSeq) {
        if (subscriber.get() == null) {
            deliveredSeq = Math.max(deliveredSeq, notifSeq);
        }
    }

    private long drainToSubscriber() {
        Flow.Subscriber<? super ConfigChange> sub = subscriber.get();
        synchronized (deliverLock) {
            if (sub == null) {
                deliveryBuffer.clear();
                deliveredSeq = Math.max(deliveredSeq, cursor);
                return cursor;
            }
            while (!cancelled && demand > 0 && !deliveryBuffer.isEmpty()) {
                Buffered b = deliveryBuffer.pollFirst();
                demand--;
                try {
                    sub.onNext(b.change());
                } catch (RuntimeException ex) {
                    cancelled = true;
                    break;
                }
                if (b.ackSeq() >= 0) {
                    deliveredSeq = Math.max(deliveredSeq, b.ackSeq());
                }
            }
            return deliveredSeq;
        }
    }

    private void sendCursorAck(long seq) {
        EdgeConnection c = connection;
        if (c == null || seq < 0) {
            return;
        }
        try {
            c.send(new EdgeFrame.CursorAck(seq), wireVersion);
        } catch (IOException io) {
        }
    }

    private void persistCursor() {
        cursorStore.save(cursorKey, WatchCursor.of(0, cursor));
    }

    private void failConnection(ConfigdException error) {
        EdgeConnection c = connection;
        if (c != null) {
            c.fail(error);
        }
    }

    private void failSubscriber(Throwable error) {
        Flow.Subscriber<? super ConfigChange> sub = subscriber.get();
        synchronized (deliverLock) {
            if (sub != null && !cancelled) {
                cancelled = true;
                sub.onError(error);
            }
        }
    }

    private static long scalarOf(WatchCursor cursor) {
        return cursor.components().isEmpty() ? 0L : cursor.components().get(0).s();
    }

    public Optional<byte[]> get(String key) {
        return view.get(key);
    }
}
