package io.configd.client.edge.session;

import io.configd.client.AuthFailedException;
import io.configd.client.ConfigdClientConfig;
import io.configd.client.ConfigdException;
import io.configd.client.CredentialExpiredException;
import io.configd.client.GapUnrecoverableException;
import io.configd.client.ProtocolViolationException;
import io.configd.client.QuarantinedException;
import io.configd.client.ServerAddress;
import io.configd.client.StaleTopologyException;
import io.configd.client.UnavailableException;
import io.configd.client.edge.AuthMode;
import io.configd.client.edge.InboundFrameHandler;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * One edge connection's lifecycle — connect + authenticate + bounded reconnect — driving one active
 * {@link InboundFrameHandler}. Extracted from {@code ConfigdEdgeClient} so that each independently-resumed
 * watch can own its <b>own</b> session (its own connection), which is what the single-shared-drain rule
 * (§06 F10-1b) requires: a driver that needs independent per-watch resume MUST use one connection per such
 * watch. A {@code ConfigdEdgeClient} owns a primary session (the Gate-1/2 single-connection surface) and one
 * additional session per independently-resumed watch (Gate 3).
 *
 * <p><b>Reconnect vs. hot-loop (§03 AU4-4 / §07 E4-2 / E7) — behavior preserved verbatim.</b> A recoverable
 * terminal reconnects under the {@link ConfigdClientConfig#retryPolicy()} backoff, capped by
 * {@code maxAttempts}; a terminal one fails closed on {@link #terminalFuture()}. <b>The reconnect budget
 * resets only on a CONFIRMED-healthy positive server frame</b> ({@code HEARTBEAT} or a business frame, via
 * {@link #markHealthy()}) — never on optimistic connect+auth success — so an always-rejecting server accrues
 * to {@code maxAttempts} and gives up rather than reconnecting forever.
 */
public final class EdgeSession implements AutoCloseable {

    private final ConfigdClientConfig config;
    private final ScheduledExecutorService scheduler;
    private final AuthMode mode;
    private final String name;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger readerSeq = new AtomicInteger();
    private final AtomicInteger reconnectAttempt = new AtomicInteger();
    private final AtomicInteger reconnects = new AtomicInteger();
    private final AtomicInteger endpointCursor = new AtomicInteger();
    // Second, markHealthy-independent reconnect bound. The ordinary attempt budget (reconnectAttempt) is reset
    // by any positive frame (markHealthy), which a healthy-but-flapping server legitimately triggers — but that
    // same reset lets a HOSTILE server emit one cheap frame (a HEARTBEAT, or the SUBSCRIBE_OK itself) per
    // connection then drop, pinning the budget at zero and looping the client forever (a self-inflicted DoS;
    // §07 E4-2 / E7 defeated). The discriminator is stability: a genuinely healthy connection stays up a while;
    // the hostile pattern drops almost immediately. So a connection torn down before it has been up
    // MIN_STABLE_MILLIS counts toward rapidFailures, which markHealthy CANNOT reset (only a stable connection
    // does) — after maxAttempts such rapid failures the client gives up. Genuine flapping (each connection up
    // >= MIN_STABLE_MILLIS between drops) resets rapidFailures and is still tolerated without bound.
    private static final long MIN_STABLE_MILLIS = 1000;
    private volatile long connectedAtNanos;
    private final AtomicInteger rapidFailures = new AtomicInteger();
    private final CompletableFuture<Void> terminal = new CompletableFuture<>();

    private volatile InboundFrameHandler activeHandler;
    private volatile Consumer<EdgeConnection> onAuthenticated = c -> {
    };
    private volatile Consumer<ConfigdException> onGaveUp = e -> {
    };

    private volatile EdgeConnection connection;
    private volatile AuthLifecycle lifecycle;

    public EdgeSession(ConfigdClientConfig config, ScheduledExecutorService scheduler, AuthMode mode,
                       String name, InboundFrameHandler handler) {
        this.config = config;
        this.scheduler = scheduler;
        this.mode = mode;
        this.name = name;
        this.activeHandler = handler == null ? new InboundFrameHandler() {
        } : handler;
        // On permanent give-up, notify the driver (a Subscription/Watch) so its stream errors out.
        terminal.whenComplete((v, ex) -> {
            if (ex != null) {
                onGaveUp.accept(toConfigdException(ex));
            }
        });
    }

    /** Replaces the active inbound handler (a Subscription/WatchSession takes over from the default). */
    public void setHandler(InboundFrameHandler handler) {
        this.activeHandler = handler;
    }

    /** Sets the post-(re)authentication hook — a Subscription/WatchSession (re)sends its subscribe/create here. */
    public void setOnAuthenticated(Consumer<EdgeConnection> onAuthenticated) {
        this.onAuthenticated = onAuthenticated;
    }

    /** Sets the give-up callback — invoked with the final terminal error when the session stops reconnecting. */
    public void setOnGaveUp(Consumer<ConfigdException> onGaveUp) {
        this.onGaveUp = onGaveUp;
    }

    public CompletableFuture<Void> connect() {
        return CompletableFuture.runAsync(this::doConnect, scheduler);
    }

    public CompletableFuture<Void> authenticate() {
        return CompletableFuture.runAsync(this::doAuthenticate, scheduler);
    }

    public CompletableFuture<Void> connectAndAuthenticate() {
        return connect().thenCompose(v -> authenticate());
    }

    public CompletableFuture<Void> refreshAuthNow() {
        return CompletableFuture.runAsync(() -> {
            AuthLifecycle lc = lifecycle;
            if (lc == null) {
                throw new IllegalStateException("not authenticated");
            }
            try {
                lc.refreshNow();
            } catch (IOException io) {
                throw new UnavailableException("REFRESH_AUTH failed: " + io.getMessage(), io);
            }
        }, scheduler);
    }

    public EdgeConnectionState state() {
        EdgeConnection c = connection;
        return c == null ? EdgeConnectionState.CLOSED : c.state();
    }

    /** The currently-live connection, or {@code null} before the first connect (used to bind a late multiplex). */
    public EdgeConnection currentConnection() {
        return connection;
    }

    public CompletableFuture<Void> terminalFuture() {
        return terminal;
    }

    public int reconnectCount() {
        return reconnects.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        AuthLifecycle lc = lifecycle;
        if (lc != null) {
            lc.cancel();
        }
        EdgeConnection c = connection;
        if (c != null) {
            c.close();
        }
        terminal.complete(null); // no-op if a terminal already completed it exceptionally
    }

    // -----------------------------------------------------------------------

    private void doConnect() {
        if (closed.get()) {
            throw new IllegalStateException("session is closed");
        }
        // Start the stability clock before the reader can deliver any frame, so markHealthy always sees a real
        // connect time (an unset 0 would read as "long-stable" and wrongly reset the budget).
        connectedAtNanos = System.nanoTime();
        ServerAddress addr = nextEndpoint();
        AuthLifecycle lc = new AuthLifecycle(mode, config.credentialSource().orElse(null),
                config.tls().orElse(null), scheduler, this::proactiveReconnect);
        EdgeConnection conn = new EdgeConnection(addr, config.tls().orElse(null), config.limits(),
                new TerminalInterceptingHandler(), name + "-reader-" + readerSeq.incrementAndGet());
        conn.connect(); // throws AuthFailedException / UnavailableException
        this.connection = conn;
        this.lifecycle = lc;
    }

    private void doAuthenticate() {
        EdgeConnection conn = connection;
        AuthLifecycle lc = lifecycle;
        if (conn == null || lc == null) {
            throw new IllegalStateException("not connected");
        }
        try {
            lc.authenticate(conn);
        } catch (IOException io) {
            throw new UnavailableException("failed to present credential: " + io.getMessage(), io);
        }
        onAuthenticated.accept(conn); // a Subscription/WatchSession (re)sends its create here
    }

    private ServerAddress nextEndpoint() {
        var endpoints = config.endpoints();
        int idx = Math.floorMod(endpointCursor.getAndIncrement(), endpoints.size());
        return endpoints.get(idx);
    }

    /** Reader-thread callback: decide reconnect vs. terminal for a connection-fatal error. */
    private void handleTerminal(ConfigdException error) {
        if (closed.get() || terminal.isDone()) {
            return;
        }
        AuthLifecycle lc = lifecycle;
        if (lc != null) {
            lc.cancel();
        }
        if (shouldReconnect(error)) {
            // A connection torn down before it reached stability counts toward a bound markHealthy cannot reset
            // (see rapidFailures): a healthy connection stays up >= MIN_STABLE_MILLIS and clears the counter,
            // whereas a hostile server emitting one cheap frame per connection then dropping accrues rapid
            // failures until the client gives up — closing the unbounded-reconnect hot-loop.
            if (System.nanoTime() - connectedAtNanos >= TimeUnit.MILLISECONDS.toNanos(MIN_STABLE_MILLIS)) {
                rapidFailures.set(0);
            } else if (rapidFailures.incrementAndGet() > config.retryPolicy().maxAttempts()) {
                terminal.completeExceptionally(new UnavailableException(
                        "edge reconnect gave up: too many connections dropped before reaching stability ("
                                + config.retryPolicy().maxAttempts() + ")", error));
                return;
            }
            scheduleReconnect(error);
        } else {
            terminal.completeExceptionally(error);
        }
    }

    /** A recoverable terminal reconnects (bounded backoff); a terminal one fails closed (never hot-loop). */
    private static boolean shouldReconnect(ConfigdException error) {
        // AUTH_FAIL is recovered via bounded reconnect-with-backoff: on the edge it is not provably permanent
        // (§07 E4-2). GapUnrecoverable / StaleTopology re-bootstrap (the driver re-creates from scratch). A
        // bare ProtocolViolation without FRAME_CORRUPT, ForbiddenException, and ChainVerificationException are
        // terminal (fail-closed).
        if (error instanceof AuthFailedException
                || error instanceof CredentialExpiredException
                || error instanceof UnavailableException
                || error instanceof QuarantinedException
                || error instanceof GapUnrecoverableException
                || error instanceof StaleTopologyException) {
            return true;
        }
        return error instanceof ProtocolViolationException pve
                && pve.edgeCode().orElse(null) == ErrorCode.FRAME_CORRUPT;
    }

    private void scheduleReconnect(ConfigdException cause) {
        int attempt = reconnectAttempt.incrementAndGet();
        if (attempt > config.retryPolicy().maxAttempts()) {
            terminal.completeExceptionally(new UnavailableException(
                    "edge reconnect attempts exhausted (" + config.retryPolicy().maxAttempts() + ")", cause));
            return;
        }
        long delayMs = config.retryPolicy().backoff(attempt).toMillis();
        try {
            scheduler.schedule(this::attemptReconnect, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            // The session is closing; stop trying.
        }
    }

    private void attemptReconnect() {
        if (closed.get()) {
            return;
        }
        try {
            doConnect();
            doAuthenticate();
            // Do NOT reset the attempt budget on optimistic connect+auth success (the wire carries no AUTH-OK):
            // the budget resets only when a POSITIVE server frame (markHealthy) confirms the connection is up.
        } catch (ConfigdException reconnectFailure) {
            handleTerminal(reconnectFailure); // recurse: reconnect again or give up
        }
    }

    /** A positive server frame proves the connection is genuinely up: reset the reconnect budget (count it). */
    private void markHealthy() {
        if (reconnectAttempt.getAndSet(0) > 0) {
            reconnects.incrementAndGet();
        }
    }

    /** The mTLS cert lead-time (or any proactive) reconnect: gracefully replace the connection. */
    private void proactiveReconnect() {
        if (closed.get()) {
            return;
        }
        EdgeConnection c = connection;
        if (c != null) {
            c.close();
        }
        reconnectAttempt.set(0);
        attemptReconnect();
    }

    private static ConfigdException toConfigdException(Throwable ex) {
        Throwable cause = ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null
                ? ex.getCause() : ex;
        return cause instanceof ConfigdException ce
                ? ce : new UnavailableException("edge session failed: " + cause.getMessage(), cause);
    }

    /** Wraps the active inbound handler, intercepting the terminal callback for the reconnect decision. */
    private final class TerminalInterceptingHandler implements InboundFrameHandler {
        @Override
        public void onHeartbeat(EdgeFrame.Heartbeat heartbeat) {
            markHealthy();
            activeHandler.onHeartbeat(heartbeat);
        }

        @Override
        public void onFrame(EdgeFrame frame) {
            markHealthy();
            activeHandler.onFrame(frame);
        }

        @Override
        public void onCatchUp() {
            activeHandler.onCatchUp();
        }

        @Override
        public void onPerWatch(ConfigdException watchError) {
            activeHandler.onPerWatch(watchError);
        }

        @Override
        public void onPerWatch(long watchId, ConfigdException watchError) {
            activeHandler.onPerWatch(watchId, watchError); // forward the watch_id so a multiplex can demux (W6-4)
        }

        @Override
        public void onCancelAck() {
            activeHandler.onCancelAck();
        }

        @Override
        public void onCancelAck(long watchId) {
            activeHandler.onCancelAck(watchId);
        }

        @Override
        public void onTerminal(ConfigdException terminalError) {
            activeHandler.onTerminal(terminalError);
            handleTerminal(terminalError);
        }

        @Override
        public boolean wantsMoreFrames() {
            return activeHandler.wantsMoreFrames();
        }
    }
}
