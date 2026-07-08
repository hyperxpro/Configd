package io.configd.client.edge;

import io.configd.client.ConfigdClientConfig;
import io.configd.client.ConfigdException;
import io.configd.client.UnavailableException;
import io.configd.client.edge.session.EdgeSession;
import io.configd.client.edge.session.EdgeConnectionState;
import io.configd.client.edge.session.SignedChainVerifier;
import io.configd.client.edge.session.SnapshotReassembler;
import io.configd.client.edge.session.WatchMultiplexHandler;
import io.configd.client.edge.session.WatchSession;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The edge-plane reference client: it owns the credential/TLS/endpoints and vends the streaming surfaces. Gate
 * 1 — {@link #connect()}, {@link #authenticate()}, {@link #connectAndAuthenticate()}, {@link #refreshAuthNow()},
 * lifecycle-driven reconnect; Gate 2 — {@link #subscribeFullStore(SubscribeOptions)} /
 * {@link #subscribePrefixes(List, SubscribeOptions)}; Gate 3 — {@link #watch}. The connection + auth + reconnect
 * lifecycle lives in {@link EdgeSession}: this client drives a <b>primary</b> session (the Gate-1/2
 * single-connection surface) and one <b>additional</b> session per independently-resumed watch (§06 F10-1b:
 * one connection per independently-resumed watch).
 *
 * <p>The reconnect/hot-loop contract is unchanged and documented on {@link EdgeSession}: recoverable terminals
 * reconnect under the {@link ConfigdClientConfig#retryPolicy()} backoff (budget reset only on a positive
 * server frame), terminal ones fail closed on {@link #terminalFuture()}.
 */
public final class ConfigdEdgeClient implements AutoCloseable {

    private final ConfigdClientConfig config;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AuthMode mode;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final EdgeSession primary;
    private final List<EdgeSession> watchSessions = new CopyOnWriteArrayList<>();
    private final AtomicInteger watchSeq = new AtomicInteger();

    private volatile Subscription activeSubscription;

    private ConfigdEdgeClient(ConfigdClientConfig config, ScheduledExecutorService scheduler,
                              boolean ownsScheduler, InboundFrameHandler userHandler) {
        this.config = config;
        this.scheduler = scheduler;
        this.ownsScheduler = ownsScheduler;
        this.mode = AuthMode.of(config);
        this.primary = new EdgeSession(config, scheduler, mode, "configd-edge", userHandler);
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
        return primary.connect();
    }

    /** Presents the credential (token/basic {@code AUTH} frame; a no-op on mTLS/no-auth) and arms refresh. */
    public CompletableFuture<Void> authenticate() {
        return primary.authenticate();
    }

    /** Connects then authenticates. */
    public CompletableFuture<Void> connectAndAuthenticate() {
        return primary.connectAndAuthenticate();
    }

    /** Sends a proactive {@code REFRESH_AUTH} now (token/basic only) with a freshly-minted credential. */
    public CompletableFuture<Void> refreshAuthNow() {
        return primary.refreshAuthNow();
    }

    /** The primary connection state, or {@link EdgeConnectionState#CLOSED} before the first connect. */
    public EdgeConnectionState state() {
        return primary.state();
    }

    /**
     * Completes exceptionally with the final terminal error when the primary session gives up (a non-retryable
     * terminal, or reconnect attempts exhausted); completes normally on {@link #close()}.
     */
    public CompletableFuture<Void> terminalFuture() {
        return primary.terminalFuture();
    }

    /** The number of confirmed-healthy reconnects on the primary session (a positive frame followed a reconnect). */
    public int reconnectCount() {
        return primary.reconnectCount();
    }

    // -----------------------------------------------------------------------
    // Gate 2: subscribe / hydrate
    // -----------------------------------------------------------------------

    /** Subscribes to the whole store and hydrates a verified {@link LocalConfigView}. */
    public Subscription subscribeFullStore(SubscribeOptions options) {
        return subscribe(true, List.of(), options);
    }

    /** Subscribes to a set of key prefixes (server-side or client-side storage-filtered). */
    public Subscription subscribePrefixes(List<String> prefixes, SubscribeOptions options) {
        return subscribe(false, prefixes, options);
    }

    private Subscription subscribe(boolean fullStore, List<String> prefixes, SubscribeOptions options) {
        if (activeSubscription != null) {
            throw new IllegalStateException(
                    "this edge client already has a subscription; open another client for a second stream");
        }
        SignedChainVerifier verifier = buildVerifier();
        LocalConfigView view = fullStore
                ? new LocalConfigView()
                : new LocalConfigView(key -> prefixes.stream().anyMatch(key::startsWith));
        SnapshotReassembler reassembler = new SnapshotReassembler(config.limits());
        Subscription sub = Subscription.create(
                options, fullStore, prefixes, verifier, reassembler, view, config.cursorStore());
        this.activeSubscription = sub;
        primary.setHandler(sub);
        primary.setOnAuthenticated(sub::onConnected);
        primary.setOnGaveUp(sub::onClientGaveUp);
        // Drive the connection; an initial connect/auth failure terminates the subscription (no established
        // connection was ever dropped, so the reconnect path does not apply — surfaced via awaitHydrated).
        primary.connectAndAuthenticate().whenComplete((v, ex) -> {
            if (ex != null) {
                sub.onClientGaveUp(toConfigdException(ex));
            }
        });
        return sub;
    }

    // -----------------------------------------------------------------------
    // Gate 3: watch
    // -----------------------------------------------------------------------

    /**
     * Creates a watch on {@code target}. Each watch runs on its <b>own</b> dedicated connection (§06 F10-1b:
     * one connection per independently-resumed watch), so a single multi-shard watch fans in over that one
     * connection while independent watches never share a drain and never silently drop each other's backfill.
     * The {@link Watch} is returned immediately; use {@link Watch#awaitCreated} or subscribe to its stream.
     */
    public Watch watch(WatchTarget target, WatchOptions options) {
        if (closed.get()) {
            throw new IllegalStateException("client is closed");
        }
        // full_chain_verify streams the verbatim signed chain — the client verifies (VERIFY mode required);
        // the default trusted-edge mode receives pre-verified, pre-filtered WATCH_EVENTs and needs no verifier.
        SignedChainVerifier verifier = target.fullChainVerify()
                ? buildVerifier() : SignedChainVerifier.trustUnverified();
        String cursorKey = options.persistenceKey().orElse(null);
        WatchSession ws = new WatchSession(target, verifier, config.limits(),
                config.cursorStore(), cursorKey, options.resumeFrom());

        if (options.shareConnectionOf().isPresent()) {
            return shareWatch(ws, options, options.shareConnectionOf().get());
        }

        // The default: a dedicated connection (F10-1b holds by construction).
        EdgeSession session = new EdgeSession(config, scheduler, mode,
                "configd-watch-" + watchSeq.incrementAndGet(), ws);
        session.setOnAuthenticated(ws::onConnected);
        session.setOnGaveUp(ws::onClientGaveUp);
        watchSessions.add(session);
        Watch watch = new Watch(ws, session, null, options.isFromNow());
        session.connectAndAuthenticate().whenComplete((v, ex) -> {
            if (ex != null) {
                ws.onClientGaveUp(toConfigdException(ex));
            }
        });
        return watch;
    }

    /**
     * Joins {@code ws} onto {@code host}'s connection (§06 W6-4). Refuses a cursored/persisted share loudly
     * (W8-6a): a shared drain has a single position and cannot honour an independent resume (F10-1b). Converts
     * {@code host}'s dedicated session to a multiplex on the first share (its watch keeps streaming).
     */
    private Watch shareWatch(WatchSession ws, WatchOptions options, Watch host) {
        if (!options.isFromNow()) {
            throw new IllegalStateException("a cursored or persisted watch cannot share a connection (W8-6a): it "
                    + "needs independent resume, which a shared drain cannot honour (F10-1b) — give it its own watch()");
        }
        if (!host.fromNow()) {
            throw new IllegalStateException("cannot share the connection of a cursored or persisted watch: only "
                    + "two from-now watches may share a drain (W8-6a)");
        }
        EdgeSession session = host.edgeSession();
        WatchMultiplexHandler mux = host.multiplex();
        if (mux == null) {
            // First share: convert the host's dedicated session to a multiplex. Bind the live connection, adopt
            // the host's watch (it keeps streaming), then route the session's inbound + lifecycle through the mux.
            mux = new WatchMultiplexHandler();
            mux.bindConnection(session.currentConnection());
            mux.adopt(host.session());
            session.setHandler(mux);
            session.setOnAuthenticated(mux::onConnected);
            session.setOnGaveUp(mux::onClientGaveUp);
            host.setMultiplex(mux);
        }
        mux.add(ws); // creates the new watch on the shared connection now (fresh watch_id)
        return new Watch(ws, session, mux, true);
    }

    /** Builds the signed-chain verifier from config (VERIFY with a leader key, or explicit TRUST-UNVERIFIED). */
    SignedChainVerifier buildVerifier() {
        if (config.leaderVerifyKey().isPresent()) {
            return SignedChainVerifier.verifying(config.leaderVerifyKey().get(), config.epochStore());
        }
        if (config.trustUnverified()) {
            return SignedChainVerifier.trustUnverified();
        }
        throw new IllegalStateException("a subscribe/watch requires a verification mode: configure "
                + "verifyWith(leaderKey) for VERIFY, or trustUnverified() to opt out explicitly");
    }

    ConfigdClientConfig config() {
        return config;
    }

    /** Registers a per-watch session (Gate 3) so {@link #close()} tears it down. */
    void trackWatchSession(EdgeSession session) {
        watchSessions.add(session);
    }

    static ConfigdException toConfigdException(Throwable ex) {
        Throwable cause = ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null
                ? ex.getCause() : ex;
        return cause instanceof ConfigdException ce
                ? ce : new UnavailableException("edge client failed: " + cause.getMessage(), cause);
    }

    boolean isClosed() {
        return closed.get();
    }

    ScheduledExecutorService scheduler() {
        return scheduler;
    }

    AuthMode mode() {
        return mode;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        primary.close();
        for (EdgeSession s : watchSessions) {
            s.close();
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private static ScheduledExecutorService defaultScheduler() {
        return java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "configd-edge-client");
            t.setDaemon(true);
            return t;
        });
    }
}
