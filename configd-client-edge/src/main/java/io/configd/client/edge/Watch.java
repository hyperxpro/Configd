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
 * Watch handle (reactive or blocking). Each watch runs on its own dedicated connection; per-watch terminal
 * tears down this watch alone. Events are per-key/per-shard-ordered only (never cross-shard).
 */
public final class Watch implements Flow.Publisher<WatchEvent>, AutoCloseable {

    private final WatchSession session;
    private final EdgeSession edgeSession;
    private final boolean fromNow;
    private volatile WatchMultiplexHandler multiplex;

    Watch(WatchSession session, EdgeSession edgeSession, WatchMultiplexHandler multiplex, boolean fromNow) {
        this.session = session;
        this.edgeSession = edgeSession;
        this.multiplex = multiplex;
        this.fromNow = fromNow;
    }

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

    public WatchEvent poll(Duration timeout) throws InterruptedException {
        return session.poll(timeout.toMillis());
    }

    public WatchCursor cursor() {
        return session.cursorVector();
    }

    public long watchId() {
        return session.watchId();
    }

    /** On shared connection: fires for this watch alone; siblings keep streaming. */
    public CompletableFuture<Void> terminalFuture() {
        return session.watchTerminal();
    }

    public void cancel() {
        close();
    }

    @Override
    public void close() {
        session.close();
        WatchMultiplexHandler mux = multiplex;
        if (mux == null) {
            edgeSession.close();
        } else {
            mux.remove(session);
        }
    }
}
