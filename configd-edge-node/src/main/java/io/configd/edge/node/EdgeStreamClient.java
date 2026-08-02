package io.configd.edge.node;

import io.configd.common.Clock;
import io.configd.common.auth.Credential;
import io.configd.common.auth.CredentialExpiryPolicy;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.WatchCursor;
import io.configd.edge.EdgeClientCore;
import io.configd.transport.TlsConfig;
import io.configd.transport.TlsManager;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Socket shell: owns connect/mTLS/reconnect on virtual threads, frames to/from
 * {@link EdgeClientCore}. No protocol logic here — the core owns the policy.
 */
public final class EdgeStreamClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(EdgeStreamClient.class.getName());

    static final int CONNECT_TIMEOUT_MS = 1_000;
    static final int HANDSHAKE_TIMEOUT_MS = 2_000;
    static final long MAX_BACKOFF_MS = 10_000;
    static final int OUTBOUND_QUEUE_FRAMES = 64;
    static final int INBOUND_QUEUE_FRAMES = 256;
    static final long TICK_POLL_MS = 50;

    static final long NO_EXPIRY = Long.MAX_VALUE;

    /** Sentinel posted by the reader when the connection ended. */
    private static final Object CLOSED = new Object();
    /** Sentinel pushed to the outbound queue to unblock the writer's blocking take(). */
    private static final byte[] POISON = new byte[0];

    private final List<InetSocketAddress> endpoints;
    private final String edgeId;
    private final List<String> prefixes;
    private final byte wireVersion;
    private final boolean acceptFiltered;
    private final Credential authCredential;
    private final ProactiveRefresh proactiveRefresh;
    private long tokenExpiresAtMillis;
    private final TlsManager tlsManager; // null = plaintext (test / single-node)
    private final long backoffBaseMs;
    private final long silenceWindowMs;
    private final Clock clock;
    private final EdgeNodeMetrics metrics;
    private final Runnable rebootstrapHook;
    private final Runnable terminalAction;

    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Set by {@link #requestRebootstrap}; cuts a backoff short (cleared when honored). */
    private volatile boolean rebootstrapRequested;
    private volatile Connection current;
    private volatile Thread sessionThread;
    private EdgeClientCore core; // set in start(); touched only by the session thread after that

    public EdgeStreamClient(List<InetSocketAddress> endpoints, String edgeId,
                            List<String> prefixes, TlsManager tlsManager,
                            long backoffBaseMs, int silenceFactor,
                            Clock clock, EdgeNodeMetrics metrics, Runnable rebootstrapHook,
                            Runnable terminalAction) {
        this(endpoints, edgeId, prefixes, tlsManager, backoffBaseMs, silenceFactor, clock,
                metrics, rebootstrapHook, terminalAction, false);
    }

    public EdgeStreamClient(List<InetSocketAddress> endpoints, String edgeId,
                            List<String> prefixes, TlsManager tlsManager,
                            long backoffBaseMs, int silenceFactor,
                            Clock clock, EdgeNodeMetrics metrics, Runnable rebootstrapHook,
                            Runnable terminalAction, boolean acceptFiltered) {
        this(endpoints, edgeId, prefixes, tlsManager, backoffBaseMs, silenceFactor, clock,
                metrics, rebootstrapHook, terminalAction, acceptFiltered, null);
    }

    public EdgeStreamClient(List<InetSocketAddress> endpoints, String edgeId,
                            List<String> prefixes, TlsManager tlsManager,
                            long backoffBaseMs, int silenceFactor,
                            Clock clock, EdgeNodeMetrics metrics, Runnable rebootstrapHook,
                            Runnable terminalAction, boolean acceptFiltered, Credential authCredential) {
        this(endpoints, edgeId, prefixes, tlsManager, backoffBaseMs, silenceFactor, clock, metrics,
                rebootstrapHook, terminalAction, acceptFiltered, authCredential, null);
    }

    public EdgeStreamClient(List<InetSocketAddress> endpoints, String edgeId,
                            List<String> prefixes, TlsManager tlsManager,
                            long backoffBaseMs, int silenceFactor,
                            Clock clock, EdgeNodeMetrics metrics, Runnable rebootstrapHook,
                            Runnable terminalAction, boolean acceptFiltered, Credential authCredential,
                            ProactiveRefresh proactiveRefresh) {
        this.authCredential = authCredential;
        this.proactiveRefresh = proactiveRefresh;
        this.tokenExpiresAtMillis =
                (proactiveRefresh != null) ? proactiveRefresh.initialExpiresAtMillis() : NO_EXPIRY;
        this.endpoints = List.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
        if (this.endpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one endpoint is required");
        }
        this.edgeId = Objects.requireNonNull(edgeId, "edgeId");
        this.prefixes = List.copyOf(Objects.requireNonNull(prefixes, "prefixes"));
        this.acceptFiltered = acceptFiltered && !this.prefixes.isEmpty();
        this.wireVersion = this.acceptFiltered
                ? EdgeFrameCodec.EDGE_WIRE_VERSION_V3 : EdgeFrameCodec.EDGE_WIRE_VERSION;
        this.tlsManager = tlsManager;
        if (backoffBaseMs <= 0) {
            throw new IllegalArgumentException("backoffBaseMs must be > 0: " + backoffBaseMs);
        }
        if (silenceFactor <= 0) {
            throw new IllegalArgumentException("silenceFactor must be > 0: " + silenceFactor);
        }
        this.backoffBaseMs = backoffBaseMs;
        this.silenceWindowMs = silenceFactor * EdgeClientCore.DEFAULT_HEARTBEAT_MS;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.terminalAction = Objects.requireNonNull(terminalAction, "terminalAction");
        Runnable injected = rebootstrapHook != null ? rebootstrapHook : () -> { };
        this.rebootstrapHook = () -> {
            requestRebootstrap("disconnected-transition");
            injected.run();
        };
    }

    // Cuts backoff short and tears down live connection; safe from session thread.
    void requestRebootstrap(String reason) {
        rebootstrapRequested = true;
        Connection conn = current;
        if (conn != null) {
            LOG.info(() -> "edge re-bootstrap requested (" + reason + ") — forcing re-subscribe");
            conn.teardown("rebootstrap: " + reason);
        }
    }

    Runnable rebootstrapHookForTest() {
        return rebootstrapHook;
    }

    boolean rebootstrapRequestedForTest() {
        return rebootstrapRequested;
    }

    public EdgeClientCore.FrameSink sink() {
        return frame -> {
            Connection conn = current;
            return conn != null && conn.offer(frame);
        };
    }

    public void start(EdgeClientCore core) {
        this.core = Objects.requireNonNull(core, "core");
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("EdgeStreamClient already started");
        }
        sessionThread = Thread.ofVirtual().name("edge-session-" + edgeId)
                .start(this::sessionLoop);
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Connection conn = current;
        if (conn != null) {
            conn.teardown("client closed");
        }
        Thread t = sessionThread;
        if (t != null) {
            t.interrupt(); // unblocks a backoff sleep / inbound poll
            try {
                t.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sessionLoop() {
        int endpointIdx = 0;
        int consecutiveFailures = 0;
        boolean reachedAnEndpoint = false; // gates whether failoverResumeCursor is set

        while (running.get()) {
            InetSocketAddress endpoint = endpoints.get(endpointIdx % endpoints.size());
            Connection conn = null;
            boolean sawInbound = false;
            try {
                conn = openAndSubscribe(endpoint, reachedAnEndpoint);
                current = conn;
                core.onReconnected();
                sawInbound = runConnection(conn);
                if (sawInbound) {
                    reachedAnEndpoint = true;
                    consecutiveFailures = 0;
                }
            } catch (IOException e) {
                LOG.fine(() -> "edge connect/subscribe to " + endpoint + " failed: " + e.getMessage());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "edge session error on " + endpoint + " — reconnecting", e);
            } finally {
                current = null;
                if (conn != null) {
                    conn.teardown("session cycle ended");
                }
            }

            if (!running.get()) {
                return;
            }
            metrics.syncFromCore(core, rebootstrapHook);
            metrics.onReconnect();
            endpointIdx++;
            if (!sawInbound) {
                consecutiveFailures++;
            }
            backoff(consecutiveFailures);
        }
    }

    private boolean runConnection(Connection conn) {
        boolean sawInbound = false;
        long lastInboundAt = clock.currentTimeMillis();
        while (running.get() && conn.alive.get()) {
            Object first;
            try {
                first = conn.inbound.poll(TICK_POLL_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return sawInbound;
            }
            Object obj = first;
            while (obj != null) {
                if (obj == CLOSED) {
                    return sawInbound;
                }
                core.onFrame((EdgeFrame) obj);
                sawInbound = true;
                lastInboundAt = clock.currentTimeMillis();
                obj = conn.inbound.poll();
            }

            long now = clock.currentTimeMillis();
            core.tick(now);
            metrics.syncFromCore(core, rebootstrapHook);
            maybeProactiveRefresh(conn, now);

            if (core.hasDirective()) {
                EdgeClientCore.ConnectionDirective directive;
                String reason = "directive";
                while ((directive = core.pollDirective()) != null) {
                    switch (directive) {
                        case EdgeClientCore.ConnectionDirective.ReconnectNextEndpoint r ->
                                reason = r.reason();
                        case EdgeClientCore.ConnectionDirective.TerminalFailure t -> {
                            onTerminalFailure(t);
                            return sawInbound;
                        }
                    }
                }
                final String r = reason;
                LOG.fine(() -> "edge reconnect directive: " + r);
                return sawInbound;
            }
            long silentFor = now - lastInboundAt;
            if (silentFor > silenceWindowMs) {
                LOG.fine(() -> "edge transport silence (" + silentFor + "ms) — reconnecting");
                return sawInbound;
            }
        }
        return sawInbound;
    }

    private void onTerminalFailure(EdgeClientCore.ConnectionDirective.TerminalFailure t) {
        LOG.severe("EDGE TERMINAL (ADR-0040): " + t.reason()
                + " — edgeId=" + edgeId + " cursor=" + core.cursor()
                + "; exiting non-zero (poison pill: cannot advance, cannot re-bootstrap)");
        metrics.syncFromCore(core, rebootstrapHook);
        running.set(false);
        terminalAction.run();
    }

    private void backoff(int consecutiveFailures) {
        long raw = Math.min(MAX_BACKOFF_MS, backoffBaseMs << Math.min(consecutiveFailures, 10));
        long delay = Math.max(1, (long) (raw * (0.5 + ThreadLocalRandom.current().nextDouble())));
        try {
            while (delay > 0 && running.get()) {
                if (rebootstrapRequested) {
                    rebootstrapRequested = false;
                    return; // a DISCONNECTED entry cuts the backoff short
                }
                long slice = Math.min(delay, 1_000);
                Thread.sleep(slice);
                delay -= slice;
                metrics.syncFromCore(core, rebootstrapHook);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sends a proactive {@code REFRESH_AUTH} once the current credential enters its lead-time window, then
     * advances the tracked expiry from the fresh credential - so the server re-arms its expiry and a
     * long-lived stream is never cut off at hard-expiry. Dormant (a no-op) unless a {@link ProactiveRefresh}
     * was supplied and the credential carries an absolute expiry; a cert edge never enters here (a cert
     * reconnects, it does not refresh in-band). Runs on the session thread (the core's single writer).
     */
    private void maybeProactiveRefresh(Connection conn, long now) {
        if (proactiveRefresh == null
                || !shouldRefreshNow(now, tokenExpiresAtMillis, proactiveRefresh.lifetimeMillis(),
                        proactiveRefresh.policy())) {
            return;
        }
        TokenRefresher.Refreshed refreshed = proactiveRefresh.refresher().refresh();
        if (refreshed == null || refreshed.credential() == null) {
            return; // the IdP round-trip failed this tick; retry on the next (still ahead of hard-expiry)
        }
        // REFRESH_AUTH rides the version-pin-exempt 0x04 auth wire, exactly like the connect-time AUTH.
        byte[] frame = EdgeFrameCodec.encode(
                new EdgeFrame.RefreshAuth(refreshed.credential()), EdgeFrameCodec.EDGE_WIRE_VERSION_V4);
        if (conn.offerEncoded(frame)) {
            // Advance our own expiry so we do not re-send until the NEW credential nears its expiry. If the
            // offer was refused (queue full / disconnected) we retry next tick, still within the window.
            tokenExpiresAtMillis = refreshed.expiresAtMillis();
        }
    }

    /**
     * Whether the credential has entered its lead-time refresh window: {@code now >= expiresAt - W}, where
     * {@code W} is the token window for the credential's lifetime. Pure and side-effect-free (unit-tested);
     * a {@link #NO_EXPIRY} expiry never refreshes.
     */
    static boolean shouldRefreshNow(long now, long expiresAtMillis, long lifetimeMillis,
                                    CredentialExpiryPolicy policy) {
        if (expiresAtMillis == NO_EXPIRY) {
            return false;
        }
        long window = policy.tokenRefreshWindowMs(lifetimeMillis);
        return now >= expiresAtMillis - window;
    }

    /**
     * The lead-time token-refresh bundle: how to renew a re-presentable credential ahead of its expiry, and
     * the window model that sizes "how far ahead". Supplied only for a refreshable token that carries an
     * absolute expiry; otherwise the edge sends no proactive {@code REFRESH_AUTH}.
     *
     * @param refresher            produces a fresh credential + its new expiry, ahead of the current expiry
     * @param initialExpiresAtMillis the connect-time credential's absolute expiry (ms since epoch)
     * @param lifetimeMillis       the credential's total lifetime (ms), for window sizing
     * @param policy               the lead-time window model (token fraction/floor/ceil)
     */
    public record ProactiveRefresh(TokenRefresher refresher, long initialExpiresAtMillis,
                                   long lifetimeMillis, CredentialExpiryPolicy policy) {
        public ProactiveRefresh {
            Objects.requireNonNull(refresher, "refresher");
            Objects.requireNonNull(policy, "policy");
            if (lifetimeMillis <= 0) {
                throw new IllegalArgumentException("lifetimeMillis must be > 0: " + lifetimeMillis);
            }
        }
    }

    /** Produces a fresh re-presentable credential + its new absolute expiry, ahead of the current one's expiry. */
    @FunctionalInterface
    public interface TokenRefresher {
        /** @return a fresh credential + its new absolute expiry (ms), or {@code null} if renewal failed this tick. */
        Refreshed refresh();

        record Refreshed(Credential credential, long expiresAtMillis) { }
    }


    private Connection openAndSubscribe(InetSocketAddress endpoint, boolean failedOver)
            throws IOException {
        Socket socket = createClientSocket(endpoint);
        boolean ok = false;
        try {
            // SUBSCRIBE is the first frame on the wire, written synchronously before the
            // writer thread exists, so frame order is deterministic. Resume cursor = the
            // core's applied cursor; the failover-resume reserved field carries the same
            // cursor once a PREVIOUS endpoint had been reached.
            // While a poison quarantine is in flight the resume cursor is 0 (the forced
            // snapshot re-bootstrap) — derived from core state, not from a one-shot
            // directive memory, so a failed connect attempt cannot lose it.
            long cursor = core.poisonPolicy().quarantinedSeq() >= 0 ? 0L : core.cursor();
            // Bind the topology epoch to the resume token. This edge is static-N, so it stamps
            // the single deploy-time epoch (INITIAL_TOPOLOGY_EPOCH); a superseded epoch is
            // refused STALE_TOPOLOGY, driving a full re-hydrate. It does not track live topology
            // changes.
            EdgeFrame.Subscribe subscribe = new EdgeFrame.Subscribe(
                    prefixes.isEmpty(), prefixes, WatchCursor.INITIAL_TOPOLOGY_EPOCH, cursor,
                    failedOver ? cursor : -1L, edgeId, acceptFiltered);
            OutputStream out = socket.getOutputStream();
            if (authCredential != null) {
                // Token / basic auth: present the credential in an AUTH frame (0x04, version-pin exempt)
                // BEFORE the first business frame, so the server authenticates the connection before the
                // SUBSCRIBE. Written synchronously here (before the writer thread exists), so the AUTH
                // deterministically precedes the SUBSCRIBE on the wire. An mTLS-only edge configures no
                // credential -> no AUTH frame -> byte-identical.
                out.write(EdgeFrameCodec.encode(
                        new EdgeFrame.Auth(authCredential), EdgeFrameCodec.EDGE_WIRE_VERSION_V4));
            }
            out.write(EdgeFrameCodec.encode(subscribe, wireVersion));
            out.flush();

            Connection conn = new Connection(socket);
            conn.startThreads();
            ok = true;
            return conn;
        } finally {
            if (!ok) {
                closeQuietly(socket);
            }
        }
    }

    /**
     * Bounded client connect (+ bounded mTLS handshake with HTTPS endpoint identification),
     * mirroring {@code TcpRaftTransport.createClientSocket}.
     */
    private Socket createClientSocket(InetSocketAddress endpoint) throws IOException {
        String host = endpoint.getHostString();
        int port = endpoint.getPort();
        if (tlsManager != null) {
            SSLSocketFactory factory = tlsManager.currentContext().getSocketFactory();
            SSLSocket socket = (SSLSocket) factory.createSocket();
            boolean ok = false;
            try {
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                TlsConfig tlsConfig = tlsManager.config();
                if (tlsConfig != null) {
                    if (!tlsConfig.protocols().isEmpty()) {
                        socket.setEnabledProtocols(tlsConfig.protocols().toArray(String[]::new));
                    }
                    if (!tlsConfig.ciphers().isEmpty()) {
                        socket.setEnabledCipherSuites(tlsConfig.ciphers().toArray(String[]::new));
                    }
                }
                SSLParameters params = socket.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(params);
                socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                socket.startHandshake();
                socket.setSoTimeout(0);
                ok = true;
                return socket;
            } finally {
                if (!ok) {
                    closeQuietly(socket);
                }
            }
        }
        Socket socket = new Socket();
        boolean ok = false;
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            ok = true;
            return socket;
        } finally {
            if (!ok) {
                closeQuietly(socket);
            }
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---- One live connection: reader + writer virtual threads, bounded queues ----

    private final class Connection {

        final Socket socket;
        final ArrayBlockingQueue<Object> inbound = new ArrayBlockingQueue<>(INBOUND_QUEUE_FRAMES);
        final ArrayBlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(OUTBOUND_QUEUE_FRAMES);
        final AtomicBoolean alive = new AtomicBoolean(true);
        private Thread reader;
        private Thread writer;

        Connection(Socket socket) {
            this.socket = socket;
        }

        void startThreads() {
            reader = Thread.ofVirtual().name("edge-reader-" + edgeId).start(this::readerLoop);
            writer = Thread.ofVirtual().name("edge-writer-" + edgeId).start(this::writerLoop);
        }

        /** Non-blocking outbound offer (the {@link EdgeClientCore.FrameSink} contract). */
        boolean offer(EdgeFrame frame) {
            if (!alive.get()) {
                return false;
            }
            byte[] encoded;
            try {
                // Stamp the negotiated wire version so a 0x03 session's CURSOR_ACK is not rejected
                // by the server's inbound version pin (established from the 0x03 SUBSCRIBE).
                encoded = EdgeFrameCodec.encode(frame, wireVersion);
            } catch (EdgeFrameCodec.CodecException e) {
                LOG.log(Level.WARNING, "edge frame encode failure", e);
                return false;
            }
            return outbound.offer(encoded);
        }

        /**
         * Non-blocking offer of ALREADY-encoded wire bytes (the version-pin-exempt 0x04 auth frames, which
         * must not be re-stamped with the connection's business version). A full queue returns
         * {@code false}; the caller retries on the next tick.
         */
        boolean offerEncoded(byte[] encoded) {
            return alive.get() && outbound.offer(encoded);
        }

        private void readerLoop() {
            try {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                while (alive.get()) {
                    EdgeFrame frame = readFrame(in);
                    if (frame == null) {
                        break; // clean EOF
                    }
                    inbound.put(frame); // full queue blocks = TCP backpressure
                }
            } catch (EdgeFrameCodec.CodecException e) {
                LOG.fine(() -> "edge inbound decode error: " + e.getMessage());
            } catch (IOException e) {
                if (alive.get()) {
                    LOG.fine(() -> "edge reader I/O end: " + e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                postClosed();
            }
        }

        /**
         * Reads one frame: 4-byte length prefix, peekLength-bounded BEFORE allocation
         * (bounds-before-allocation discipline), then the body. Returns null on clean EOF.
         */
        private EdgeFrame readFrame(DataInputStream in) throws IOException {
            int length;
            try {
                length = in.readInt();
            } catch (EOFException eof) {
                return null;
            }
            byte[] header4 = new byte[]{
                    (byte) (length >>> 24), (byte) (length >>> 16),
                    (byte) (length >>> 8), (byte) length};
            int total = EdgeFrameCodec.peekLength(header4); // throws CodecException if out of range
            byte[] frameBytes = new byte[total];
            System.arraycopy(header4, 0, frameBytes, 0, 4);
            in.readFully(frameBytes, 4, total - 4);
            return EdgeFrameCodec.decode(frameBytes);
        }

        private void postClosed() {
            // Best-effort: the session loop must learn the transport died even if the
            // bounded inbound queue is full of undrained frames (clear-then-post is safe:
            // the connection is dead, those frames will be re-delivered after resubscribe
            // — the chain is cursor-resumable and stale deltas are idempotently discarded).
            if (!inbound.offer(CLOSED)) {
                inbound.clear();
                inbound.offer(CLOSED);
            }
        }

        private void writerLoop() {
            try {
                OutputStream out = socket.getOutputStream();
                while (alive.get()) {
                    byte[] frame = outbound.take();
                    if (frame == POISON) {
                        return;
                    }
                    out.write(frame);
                    out.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                if (alive.get()) {
                    LOG.fine(() -> "edge writer I/O end: " + e.getMessage());
                }
            } finally {
                teardown("writer ended");
            }
        }

        /** Idempotent teardown: closes the socket and unblocks reader/writer. */
        void teardown(String why) {
            if (!alive.compareAndSet(true, false)) {
                return;
            }
            LOG.fine(() -> "edge connection teardown: " + why);
            closeQuietly(socket);   // unblocks the reader's blocking read
            outbound.offer(POISON); // unblocks the writer's blocking take
            postClosed();           // unblocks the session loop's poll
            // A reader parked on a FULL inbound queue is not unblocked by the socket
            // close — interrupt covers that corner before joining.
            interruptQuietly(reader);
            interruptQuietly(writer);
            joinQuietly(reader);
            joinQuietly(writer);
        }

        private void joinQuietly(Thread t) {
            if (t == null || t == Thread.currentThread()) {
                return;
            }
            try {
                t.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void interruptQuietly(Thread t) {
            if (t != null && t != Thread.currentThread()) {
                t.interrupt();
            }
        }
    }
}
