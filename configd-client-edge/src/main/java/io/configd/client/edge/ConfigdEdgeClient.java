package io.configd.client.edge;

import io.configd.client.AuthFailedException;
import io.configd.client.ConfigdClientConfig;
import io.configd.client.ConfigdException;
import io.configd.client.CredentialExpiredException;
import io.configd.client.ProtocolViolationException;
import io.configd.client.QuarantinedException;
import io.configd.client.ServerAddress;
import io.configd.client.UnavailableException;
import io.configd.client.edge.session.AuthLifecycle;
import io.configd.client.edge.session.EdgeConnection;
import io.configd.client.edge.session.EdgeConnectionState;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The edge-plane reference client: it owns the credential/TLS/endpoints and drives the connection + auth
 * lifecycle. Gate 1 surface — {@link #connect()}, {@link #authenticate()}, their {@link #connectAndAuthenticate()}
 * composition, proactive {@link #refreshAuthNow()}, and lifecycle-driven reconnect. The reactive watch streams
 * and the subscribe / hydrate path are later gates; their extension point is the {@link InboundFrameHandler}
 * passed to {@link #open(ConfigdClientConfig, InboundFrameHandler)}.
 *
 * <p><b>Reconnect vs. hot-loop (§03 AU4-4 / §07 E4-2 / E7).</b> A terminal keyed to a <i>recoverable</i>
 * reaction reconnects under the {@link ConfigdClientConfig#retryPolicy()} backoff — a fresh connection per
 * attempt, jittered, capped by {@link io.configd.client.RetryPolicy#maxAttempts()}, never a tight loop:
 * {@link CredentialExpiredException} (re-authenticate with a freshly-minted credential — a token presents a
 * fresh {@code AUTH}, an mTLS client re-handshakes with its rotated cert), {@link AuthFailedException} (on the
 * edge an {@code AUTH_FAIL} is <b>not provably permanent</b> — it may be a transient authenticator outage
 * indistinguishable on the wire from a bad credential, so it is recovered via <b>bounded</b>
 * reconnect-with-backoff, §07 E4-2; the single pre-auth {@code AUTH} is never hot-looped on one connection —
 * each retry is a fresh connection after a backoff), {@link UnavailableException} (transient),
 * {@link QuarantinedException} (own bounded backoff after the identity cooldown), and a one-shot reconnect on a
 * {@code FRAME_CORRUPT}. A terminal keyed to a <i>terminal</i> reaction never reconnects — a
 * {@link io.configd.client.ForbiddenException} ({@code NOT_AUTHORIZED} — forbidden for this principal/target) and a bare
 * {@link ProtocolViolationException} fail closed on {@link #terminalFuture()}. When the bounded attempts are
 * exhausted the client gives up with a terminal {@link UnavailableException} wrapping the last cause.
 *
 * <p><b>The reconnect budget resets only on a CONFIRMED-healthy connection (closes a latent hot-loop).</b>
 * Because authentication is optimistic-present, a connection that is accepted + authenticated then
 * <i>immediately</i> sent a retryable terminal (a hostile/buggy server) must not reset the attempt budget —
 * otherwise the ceiling is never reached and the client reconnects forever. The budget is reset (a fresh
 * {@link io.configd.client.RetryPolicy} run) only when a <b>positive server frame</b> (a {@code HEARTBEAT} or
 * a business frame) proves the connection is genuinely up; a connection that never delivers one accrues toward
 * {@link io.configd.client.RetryPolicy#maxAttempts()} and then gives up.
 */
public final class ConfigdEdgeClient implements AutoCloseable {

    private final ConfigdClientConfig config;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AuthMode mode;
    private final InboundFrameHandler userHandler;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger readerSeq = new AtomicInteger();
    private final AtomicInteger reconnectAttempt = new AtomicInteger();
    private final AtomicInteger reconnects = new AtomicInteger();
    private final AtomicInteger endpointCursor = new AtomicInteger();
    private final CompletableFuture<Void> terminal = new CompletableFuture<>();

    private volatile EdgeConnection connection;
    private volatile AuthLifecycle lifecycle;

    private ConfigdEdgeClient(ConfigdClientConfig config, ScheduledExecutorService scheduler,
                              boolean ownsScheduler, InboundFrameHandler userHandler) {
        this.config = config;
        this.scheduler = scheduler;
        this.ownsScheduler = ownsScheduler;
        this.mode = AuthMode.of(config);
        this.userHandler = userHandler == null ? new InboundFrameHandler() {
        } : userHandler;
    }

    /** Opens a standalone edge client that owns its own scheduler (closed with the client). */
    public static ConfigdEdgeClient open(ConfigdClientConfig config) {
        return open(config, null);
    }

    /** Opens a standalone edge client with an inbound-frame handler (the later gates' extension point). */
    public static ConfigdEdgeClient open(ConfigdClientConfig config, InboundFrameHandler handler) {
        return new ConfigdEdgeClient(config, defaultScheduler(), true, handler);
    }

    /**
     * Opens an edge client over a caller-supplied {@code scheduler} (which the client does <b>not</b> own or
     * shut down). This is how the {@link io.configd.client.Configd} facade shares one scheduler across the
     * plane clients it vends; advanced callers may also bring their own.
     */
    public static ConfigdEdgeClient open(ConfigdClientConfig config, InboundFrameHandler handler,
                                         ScheduledExecutorService scheduler) {
        return new ConfigdEdgeClient(config, scheduler, false, handler);
    }

    /** The auth mode this client presents, derived from its configuration. */
    public AuthMode authMode() {
        return mode;
    }

    /** Connects (TCP + TLS handshake) to the next endpoint. For mTLS the handshake authenticates. */
    public CompletableFuture<Void> connect() {
        return CompletableFuture.runAsync(this::doConnect, scheduler);
    }

    /** Presents the credential (token/basic {@code AUTH} frame; a no-op on mTLS/no-auth) and arms refresh. */
    public CompletableFuture<Void> authenticate() {
        return CompletableFuture.runAsync(this::doAuthenticate, scheduler);
    }

    /** Connects then authenticates. */
    public CompletableFuture<Void> connectAndAuthenticate() {
        return connect().thenCompose(v -> authenticate());
    }

    /** Sends a proactive {@code REFRESH_AUTH} now (token/basic only) with a freshly-minted credential. */
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

    /** The current connection state, or {@link EdgeConnectionState#CLOSED} before the first connect. */
    public EdgeConnectionState state() {
        EdgeConnection c = connection;
        return c == null ? EdgeConnectionState.CLOSED : c.state();
    }

    /**
     * Completes exceptionally with the final terminal error when the client gives up (a non-retryable
     * terminal, or reconnect attempts exhausted); completes normally on {@link #close()}. It does <b>not</b>
     * complete on a reconnected recovery — those are transparent.
     */
    public CompletableFuture<Void> terminalFuture() {
        return terminal;
    }

    /** The number of confirmed-healthy reconnects so far (a positive frame followed a reconnect). */
    public int reconnectCount() {
        return reconnects.get();
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
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------

    private void doConnect() {
        if (closed.get()) {
            throw new IllegalStateException("client is closed");
        }
        ServerAddress addr = nextEndpoint();
        AuthLifecycle lc = new AuthLifecycle(mode, config.credentialSource().orElse(null),
                config.tls().orElse(null), scheduler, this::proactiveReconnect);
        EdgeConnection conn = new EdgeConnection(addr, config.tls().orElse(null), config.limits(),
                new TerminalInterceptingHandler(), "configd-edge-reader-" + readerSeq.incrementAndGet());
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
            scheduleReconnect(error);
        } else {
            terminal.completeExceptionally(error);
        }
    }

    /** A recoverable terminal reconnects (bounded backoff); a terminal one fails closed (never hot-loop). */
    private static boolean shouldReconnect(ConfigdException error) {
        // AUTH_FAIL is recovered via bounded reconnect-with-backoff: on the edge it is not provably permanent
        // (it may be a transient authenticator outage indistinguishable on the wire from a bad credential),
        // §07 E4-2. The single pre-auth AUTH is never hot-looped on one connection — each retry is a fresh
        // connection after a jittered backoff, capped by RetryPolicy.maxAttempts.
        if (error instanceof AuthFailedException
                || error instanceof CredentialExpiredException
                || error instanceof UnavailableException
                || error instanceof QuarantinedException) {
            return true;
        }
        // A transient FRAME_CORRUPT gets a bounded reconnect (§07 E7); a persistent one exhausts the budget.
        // A bare ProtocolViolation without FRAME_CORRUPT (e.g. a driver-side framing bug) is terminal, as is a
        // ForbiddenException (NOT_AUTHORIZED — forbidden for this principal/target).
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
            // The client is closing; stop trying.
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
            // an always-rejecting server would otherwise reset it every cycle and reconnect forever. The budget
            // is reset only when a POSITIVE server frame (markHealthy) confirms the connection is genuinely up.
        } catch (ConfigdException reconnectFailure) {
            handleTerminal(reconnectFailure); // recurse: reconnect again or give up
        }
    }

    /**
     * A positive server frame (HEARTBEAT or a business frame) proves the connection is genuinely up — not just
     * optimistically authenticated — so it resets the reconnect budget and counts a confirmed reconnect. This
     * is what closes the hot-loop: a hostile server that accepts + auths then immediately sends a retryable
     * terminal never sends a positive frame, so the budget accrues to maxAttempts and the client gives up.
     */
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

    private static ScheduledExecutorService defaultScheduler() {
        return java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "configd-edge-client");
            t.setDaemon(true);
            return t;
        });
    }

    /** Wraps the user's inbound handler, intercepting the terminal callback for the reconnect decision. */
    private final class TerminalInterceptingHandler implements InboundFrameHandler {
        @Override
        public void onHeartbeat(EdgeFrame.Heartbeat heartbeat) {
            markHealthy(); // a positive server frame confirms the connection is up (not just optimistically authed)
            userHandler.onHeartbeat(heartbeat);
        }

        @Override
        public void onFrame(EdgeFrame frame) {
            markHealthy();
            userHandler.onFrame(frame);
        }

        @Override
        public void onCatchUp() {
            userHandler.onCatchUp();
        }

        @Override
        public void onPerWatch(ConfigdException watchError) {
            userHandler.onPerWatch(watchError);
        }

        @Override
        public void onCancelAck() {
            userHandler.onCancelAck();
        }

        @Override
        public void onTerminal(ConfigdException terminalError) {
            userHandler.onTerminal(terminalError);
            handleTerminal(terminalError);
        }
    }
}
