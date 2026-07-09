package io.configd.client.edge;

import io.configd.client.ConfigdException;
import io.configd.client.edge.session.EdgeSession;
import io.configd.client.edge.session.WatchMultiplexHandler;
import io.configd.client.edge.session.WatchSession;
import io.configd.distribution.wire.WatchCursor;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A live watch handle (§02). Consume it reactively — {@code watch.subscribe(Flow.Subscriber<WatchEvent>)} with
 * {@code request(n)} backpressure — or with the blocking facade ({@link #awaitCreated} + {@link #poll}) for the
 * reference / conformance driver. Each watch runs on its <b>own</b> dedicated connection (§06 F10-1b: one
 * connection per independently-resumed watch), so a per-watch terminal (e.g. a {@code NOT_AUTHORIZED}
 * {@code WATCH_CANCELED}) tears down this watch without affecting any other.
 *
 * <p>The emitted {@link WatchEvent}s are <b>per-key/per-shard-ordered only</b> — never a cross-shard order
 * (see {@link WatchEvent}). {@link #cursor()} is the live per-shard resume vector.
 */
public final class Watch implements Flow.Publisher<WatchEvent>, AutoCloseable {

    private final WatchSession session;
    private final EdgeSession edgeSession;
    private final boolean fromNow;
    /** Non-null once this watch's connection is shared by a multiplex (§06 W6-4); null while dedicated. */
    private volatile WatchMultiplexHandler multiplex;

    Watch(WatchSession session, EdgeSession edgeSession, WatchMultiplexHandler multiplex, boolean fromNow) {
        this.session = session;
        this.edgeSession = edgeSession;
        this.multiplex = multiplex;
        this.fromNow = fromNow;
    }

    // Package-private accessors for the share (multiplex) wiring in ConfigdEdgeClient.
    WatchSession session() {
        return session;
    }

    EdgeSession edgeSession() {
        return edgeSession;
    }

    boolean fromNow() {
        return fromNow;
    }

    WatchMultiplexHandler multiplex() {
        return multiplex;
    }

    void setMultiplex(WatchMultiplexHandler multiplex) {
        this.multiplex = multiplex;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super WatchEvent> subscriber) {
        session.subscribe(subscriber);
    }

    /** Blocks until the watch is authorized and live ({@code WATCH_CREATED}), or throws its terminal cause. */
    public void awaitCreated(Duration timeout) throws InterruptedException, TimeoutException {
        try {
            session.created().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ConfigdException ce) {
                throw ce;
            }
            throw new IllegalStateException("watch failed", e.getCause());
        }
    }

    /** Blocks up to {@code timeout} for the next event via the blocking facade, or null if none arrives. */
    public WatchEvent poll(Duration timeout) throws InterruptedException {
        return session.poll(timeout.toMillis());
    }

    /** The live per-shard resume cursor vector. */
    public WatchCursor cursor() {
        return session.cursorVector();
    }

    /** The current wire {@code watch_id} (a fresh one is minted per (re)create; W2-8 / F10-1a). */
    public long watchId() {
        return session.watchId();
    }

    /**
     * Completes exceptionally when THIS watch permanently ends — a non-retryable per-watch terminal (e.g. a
     * {@code NOT_AUTHORIZED} reject) or, for a dedicated watch, its connection giving up — and normally on
     * {@link #close()}. On a shared connection this fires for this watch alone; the siblings keep streaming (W6-4).
     */
    public CompletableFuture<Void> terminalFuture() {
        return session.watchTerminal();
    }

    /** Cancels the watch ({@code WATCH_CANCEL}) and closes its connection. Idempotent. */
    public void cancel() {
        close();
    }

    @Override
    public void close() {
        session.close(); // best-effort WATCH_CANCEL for this watch
        WatchMultiplexHandler mux = multiplex;
        if (mux == null) {
            edgeSession.close(); // dedicated: close the connection
        } else {
            mux.remove(session); // shared: drop only this watch; the connection + siblings survive
        }
    }
}
