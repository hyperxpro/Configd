package io.configd.edge.node;

import io.configd.common.Clock;
import io.configd.common.auth.Credential;
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
 * The edge node's socket shell: owns connect / mTLS / reconnect on virtual threads, encodes
 * and decodes {@link EdgeFrameCodec} frames, feeds inbound frames to
 * {@link EdgeClientCore#onFrame}, drains the core's {@link EdgeClientCore.FrameSink}
 * (outbound CURSOR_ACKs) and {@link EdgeClientCore.ConnectionDirective} queue. No protocol
 * logic lives here — the core owns the policy; this shell owns the sockets.
 *
 * <h2>Transport stack</h2>
 * JDK sockets via the SAME {@link TlsManager} the control plane uses (mTLS by construction:
 * the client presents its certificate, verifies the server against the trust store, and
 * enforces HTTPS endpoint identification). Connect and handshake are bounded
 * ({@value #CONNECT_TIMEOUT_MS} / {@value #HANDSHAKE_TIMEOUT_MS} ms, mirroring
 * {@code TcpRaftTransport}); no TLS = plaintext (test / single-node), matching the server's
 * policy.
 *
 * <h2>Per-connection threading (mirrors FanOutServer's 3-thread shape)</h2>
 * <ul>
 *   <li><b>session</b> (one long-lived virtual thread, the core's single writer): runs the
 *       connect/subscribe/reconnect state machine, drains inbound frames into
 *       {@code core.onFrame}, drives {@code core.tick}, pumps metrics, and obeys
 *       {@code ReconnectNextEndpoint} directives;</li>
 *   <li><b>reader</b> (per connection): blocking decode with the peekLength
 *       bounds-before-allocation discipline; posts frames to a bounded inbound queue
 *       (a full queue blocks the reader — natural TCP backpressure toward the server,
 *       whose bounded per-subscriber queue then demotes us);</li>
 *   <li><b>writer</b> (per connection): drains a bounded outbound queue to the socket.
 *       {@link EdgeClientCore.FrameSink#offer} is a non-blocking {@code queue.offer};
 *       a refused CURSOR_ACK is retried by the core's next tick (acks are idempotent).</li>
 * </ul>
 *
 * <h2>Failover</h2>
 * On any connection end — socket EOF/error, decode corruption, the core's heartbeat-silence
 * {@code ReconnectNextEndpoint} directive, or the shell's own transport-silence guard — the
 * client advances to the NEXT configured endpoint (round-robin) and re-SUBSCRIBEs carrying
 * the resume cursor ({@code resumeCursor = core.cursor()}; {@code failoverResumeCursor} set
 * once a previous endpoint had been reached, per the section 3 reserved-field contract). Reads
 * keep refusing cursor-behind during catch-up — consistent refusal, enforced by
 * {@link EdgeHttpServer}, never by blocking here. Backoff between attempts is bounded and
 * jittered ({@code edge.reconnect.backoffMs} base, doubling to {@value #MAX_BACKOFF_MS} ms
 * cap, +/-50% jitter).
 *
 * <h2>Poison pill</h2>
 * Apply/snapshot failures no longer escape {@code core.onFrame}: the core's
 * {@link io.configd.edge.PoisonPillPolicy} converts them into directives — bounded retries
 * (resubscribe-at-cursor), then a forced snapshot re-bootstrap (resubscribe at cursor 0), then
 * {@link EdgeClientCore.ConnectionDirective.TerminalFailure}: this shell logs the structured
 * SEVERE event, stops, and runs the injected {@code terminalAction} ({@code EdgeNodeMain} wires
 * {@code System.exit} non-zero — never an infinite hot loop, never a lying green health check).
 * The resume cursor is derived from core state at SUBSCRIBE time (quarantined = 0, else
 * core.cursor()) so a connect failure mid-recovery cannot lose the forced re-bootstrap. The
 * {@code RuntimeException} catch in the session loop remains only as a backstop for non-apply
 * protocol-state defects.
 *
 * <h2>DISCONNECTED re-bootstrap</h2>
 * {@link #requestRebootstrap(String)} is the real orchestration seam: on each transition INTO
 * DISCONNECTED (detected by {@link EdgeNodeMetrics#syncFromCore} — also while no connection
 * exists), the live connection (if any) is torn down and any backoff in progress is cut short,
 * so the client re-SUBSCRIBEs immediately at its current cursor and the server's
 * TAIL/SNAPSHOT_FIRST decision resolves replay vs re-bootstrap.
 */
public final class EdgeStreamClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(EdgeStreamClient.class.getName());

    /** Bounded connect timeout (ms), mirroring {@code TcpRaftTransport.CONNECT_TIMEOUT_MS}. */
    static final int CONNECT_TIMEOUT_MS = 1_000;
    /** Bounded TLS handshake timeout (ms), mirroring {@code TcpRaftTransport}. */
    static final int HANDSHAKE_TIMEOUT_MS = 2_000;
    /** Reconnect backoff cap (ms): the bound on the doubling of {@code edge.reconnect.backoffMs}. */
    static final long MAX_BACKOFF_MS = 10_000;
    /** Bounded outbound (edge-to-server) queue depth in frames; CURSOR_ACK-only traffic. */
    static final int OUTBOUND_QUEUE_FRAMES = 64;
    /** Bounded inbound (server-to-edge) queue depth in frames (full = TCP backpressure). */
    static final int INBOUND_QUEUE_FRAMES = 256;
    /** Session-loop poll cadence (ms): the core tick / directive-drain period. */
    static final long TICK_POLL_MS = 50;

    /** Sentinel posted by the reader when the connection ended. */
    private static final Object CLOSED = new Object();
    /** Sentinel pushed to the outbound queue to unblock the writer's blocking take(). */
    private static final byte[] POISON = new byte[0];

    private final List<InetSocketAddress> endpoints;
    private final String edgeId;
    private final List<String> prefixes;
    /**
     * The outbound edge wire version for this client: {@code 0x03} when the operator opted this
     * prefix-scoped edge into server-side filtering (ADR-0045), else {@code 0x01} (byte-identical
     * legacy). EVERY outbound frame - SUBSCRIBE and CURSOR_ACK - is stamped with it, because the
     * server pins the inbound version from the connection's first frame and fails a later frame
     * stamped otherwise closed.
     */
    private final byte wireVersion;
    private final boolean acceptFiltered;
    /**
     * The credential presented in an {@code AUTH} frame at connect (bearer / basic token auth), or
     * {@code null} for an mTLS-only / plaintext edge - which sends no AUTH frame and is byte-identical
     * to before. Additive: a certificate-only edge configures none.
     */
    private final Credential authCredential;
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
    private EdgeClientCore core; // set in start(); touched only by the session thread after

    /**
     * @param endpoints       ordered fan-out endpoints (non-empty)
     * @param edgeId          the SUBSCRIBE identity (must match the mTLS cert DN; the server
     *                        binds the cert principal authoritatively)
     * @param prefixes        subscription prefixes (empty = full store)
     * @param tlsManager      the mTLS context, or null for plaintext (test / single-node)
     * @param backoffBaseMs   {@code edge.reconnect.backoffMs} (bounded + jittered here)
     * @param silenceFactor   {@code edge.heartbeat.silenceFactor} - also bounds the shell's
     *                        transport-silence guard (no frame at all since subscribe)
     * @param clock           the wall clock (the core's tick clock)
     * @param metrics         the process metric series
     * @param rebootstrapHook an ADDITIONAL observer invoked on each DISCONNECTED entry
     *                        (tests inject recorders; may be null). The re-bootstrap
     *                        orchestration ({@link #requestRebootstrap}) ALWAYS runs first
     *                        - the hook composes, it does not replace.
     * @param terminalAction  the terminal fail-loud action (non-null; {@code EdgeNodeMain}
     *                        wires a non-zero {@code System.exit}, tests inject recorders)
     */
    public EdgeStreamClient(List<InetSocketAddress> endpoints, String edgeId,
                            List<String> prefixes, TlsManager tlsManager,
                            long backoffBaseMs, int silenceFactor,
                            Clock clock, EdgeNodeMetrics metrics, Runnable rebootstrapHook,
                            Runnable terminalAction) {
        this(endpoints, edgeId, prefixes, tlsManager, backoffBaseMs, silenceFactor, clock,
                metrics, rebootstrapHook, terminalAction, false);
    }

    /**
     * @param acceptFiltered opt this edge into server-side prefix filtering (ADR-0045). When true
     *                       AND the subscription is prefix-scoped (non-empty prefixes), the client
     *                       negotiates the {@code 0x03} wire and advertises {@code acceptsFiltered}
     *                       on SUBSCRIBE; a full-store edge always negotiates {@code 0x01}. The
     *                       explicit opt-in (not automatic-by-version) keeps an unconfigured edge
     *                       byte-identical.
     */
    public EdgeStreamClient(List<InetSocketAddress> endpoints, String edgeId,
                            List<String> prefixes, TlsManager tlsManager,
                            long backoffBaseMs, int silenceFactor,
                            Clock clock, EdgeNodeMetrics metrics, Runnable rebootstrapHook,
                            Runnable terminalAction, boolean acceptFiltered) {
        this(endpoints, edgeId, prefixes, tlsManager, backoffBaseMs, silenceFactor, clock,
                metrics, rebootstrapHook, terminalAction, acceptFiltered, null);
    }

    /**
     * @param authCredential the credential to present in an {@code AUTH} frame at connect (a
     *                       {@link Credential.BearerToken} or {@link Credential.BasicCredential}), or
     *                       {@code null} for an mTLS-only / plaintext edge (no AUTH frame,
     *                       byte-identical). The frame is written synchronously before the SUBSCRIBE, so
     *                       the server authenticates the connection before the first business frame.
     */
    public EdgeStreamClient(List<InetSocketAddress> endpoints, String edgeId,
                            List<String> prefixes, TlsManager tlsManager,
                            long backoffBaseMs, int silenceFactor,
                            Clock clock, EdgeNodeMetrics metrics, Runnable rebootstrapHook,
                            Runnable terminalAction, boolean acceptFiltered, Credential authCredential) {
        this.authCredential = authCredential;
        this.endpoints = List.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
        if (this.endpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one endpoint is required");
        }
        this.edgeId = Objects.requireNonNull(edgeId, "edgeId");
        this.prefixes = List.copyOf(Objects.requireNonNull(prefixes, "prefixes"));
        // 0x03 only when the operator opted in AND the edge is prefix-scoped (a full-store edge
        // wants the whole chain, so it stays on the byte-identical 0x01 wire).
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
        // The re-bootstrap orchestration always runs on a DISCONNECTED entry;
        // an injected hook (test recorder) composes after it.
        Runnable injected = rebootstrapHook != null ? rebootstrapHook : () -> { };
        this.rebootstrapHook = () -> {
            requestRebootstrap("disconnected-transition");
            injected.run();
        };
    }

    /**
     * Forces a full re-subscribe NOW: tears down the live connection (if any) so the
     * session loop cycles, and cuts any backoff in progress short. Invoked on each
     * transition INTO DISCONNECTED (the {@code edge_rebootstrap_triggered_total} seam);
     * idempotent and safe from the session thread. While no connection exists the reconnect
     * machinery is already running — this only removes the remaining backoff delay. The
     * re-SUBSCRIBE carries the CURRENT cursor (never 0 — the server decides TAIL vs
     * SNAPSHOT_FIRST; cursor 0 is reserved for the poison-pill forced re-bootstrap path).
     *
     * @param reason diagnostic (structured log)
     */
    void requestRebootstrap(String reason) {
        rebootstrapRequested = true;
        Connection conn = current;
        if (conn != null) {
            LOG.info(() -> "edge re-bootstrap requested (" + reason + ") — forcing re-subscribe");
            conn.teardown("rebootstrap: " + reason);
        }
    }

    /** TEST-ONLY: the composed re-bootstrap hook handed to the metrics pump. */
    Runnable rebootstrapHookForTest() {
        return rebootstrapHook;
    }

    /** TEST-ONLY: whether a re-bootstrap request is pending (cuts the next backoff). */
    boolean rebootstrapRequestedForTest() {
        return rebootstrapRequested;
    }

    /**
     * The outbound seam handed to {@link EdgeClientCore}'s constructor (the client is
     * created before the core; the sink delegates to whatever connection is live).
     * {@code offer} returns false when disconnected or the bounded queue is full — the
     * core retries idempotent CURSOR_ACKs on its next tick.
     */
    public EdgeClientCore.FrameSink sink() {
        return frame -> {
            Connection conn = current;
            return conn != null && conn.offer(frame);
        };
    }

    /** Starts the session loop on a virtual thread. The core must use {@link #sink()}. */
    public void start(EdgeClientCore core) {
        this.core = Objects.requireNonNull(core, "core");
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("EdgeStreamClient already started");
        }
        sessionThread = Thread.ofVirtual().name("edge-session-" + edgeId)
                .start(this::sessionLoop);
    }

    /** Stops the client: ends the session loop and closes any live connection. */
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

    // ---- Session loop (the core's single writer) ----

    private void sessionLoop() {
        int endpointIdx = 0;
        int consecutiveFailures = 0;
        boolean reachedAnEndpoint = false; // gates failoverResumeCursor (section 3 reserved field)

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
                // Protocol-state / decode / apply defect surfaced by the core. Connection-
                // fatal; resubscribe-at-cursor heals transient cases. The poison-pill
                // policy (bounded retry to forced snapshot to terminal fail-loud) replaces
                // this catch site.
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
            // Staleness transitions keep happening WHILE disconnected (that is when
            // DISCONNECTED is reached) — pump them between connections too, or the
            // staleness-violation counter and the re-bootstrap trigger would only fire
            // on a live stream.
            metrics.syncFromCore(core, rebootstrapHook);
            // Reconnect cycle: count it, advance to the NEXT endpoint (round-robin
            // failover), back off bounded + jittered.
            metrics.onReconnect();
            endpointIdx++;
            if (!sawInbound) {
                consecutiveFailures++;
            }
            backoff(consecutiveFailures);
        }
    }

    /**
     * Runs one live connection until it ends. Returns true if at least one inbound frame
     * was observed (used to reset the backoff and mark the endpoint as reached).
     */
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
            // Drain the batch that is already queued, then tick once.
            Object obj = first;
            while (obj != null) {
                if (obj == CLOSED) {
                    return sawInbound; // transport ended (EOF / IO error / corrupt frame)
                }
                core.onFrame((EdgeFrame) obj);
                sawInbound = true;
                lastInboundAt = clock.currentTimeMillis();
                obj = conn.inbound.poll();
            }

            long now = clock.currentTimeMillis();
            core.tick(now);
            metrics.syncFromCore(core, rebootstrapHook);

            // The core's recovery policy: resubscribe directives (heartbeat silence,
            // fatal ERROR_CLOSE, gap/DISCONNECTED/poison retries) end the connection
            // cycle; a TerminalFailure ends the PROCESS.
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
            // Transport-silence guard: the core's silence detector arms only after the
            // first heartbeat; this guard covers a connection that never delivers ANY
            // frame after subscribe (dead endpoint behind an accepted TCP connect).
            long silentFor = now - lastInboundAt;
            if (silentFor > silenceWindowMs) {
                LOG.fine(() -> "edge transport silence (" + silentFor + "ms) — reconnecting");
                return sawInbound;
            }
        }
        return sawInbound;
    }

    /**
     * Terminal fail-loud: the structured SEVERE event (the policy already logged the cause),
     * a final metrics pump (so {@code configd_edge_poison_pill_terminal} is scrape-visible),
     * then stop and run the injected terminal action — in production a non-zero
     * {@code System.exit}; an edge that can neither advance nor re-bootstrap must die
     * visibly, never idle behind a green health check and never hot-loop reconnects.
     */
    private void onTerminalFailure(EdgeClientCore.ConnectionDirective.TerminalFailure t) {
        LOG.severe("EDGE TERMINAL (ADR-0040): " + t.reason()
                + " — edgeId=" + edgeId + " cursor=" + core.cursor()
                + "; exiting non-zero (poison pill: cannot advance, cannot re-bootstrap)");
        metrics.syncFromCore(core, rebootstrapHook);
        running.set(false);
        terminalAction.run();
    }

    /**
     * Bounded + jittered backoff: base doubling per consecutive failure up to
     * {@value #MAX_BACKOFF_MS} ms, +/-50% jitter. Slept in at most 1s slices with a
     * staleness pump per slice so a long backoff cannot delay DISCONNECTED detection by
     * the whole backoff window.
     */
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

    // ---- Connect + subscribe (bounded) ----

    private Connection openAndSubscribe(InetSocketAddress endpoint, boolean failedOver)
            throws IOException {
        Socket socket = createClientSocket(endpoint);
        boolean ok = false;
        try {
            // SUBSCRIBE is the first frame on the wire, written synchronously before the
            // writer thread exists, so frame order is deterministic. Resume cursor = the
            // core's applied cursor; the failover-resume reserved field carries the same
            // cursor once a PREVIOUS endpoint had been reached (section 3 failover clause).
            // While a poison quarantine is in flight the resume cursor is 0 (the forced
            // snapshot re-bootstrap) — derived from core state, not from a one-shot
            // directive memory, so a failed connect attempt cannot lose it.
            long cursor = core.poisonPolicy().quarantinedSeq() >= 0 ? 0L : core.cursor();
            // A4: bind the topology epoch to the resume token. This v1 edge is static-N, so it stamps
            // the single deploy-time epoch (INITIAL_TOPOLOGY_EPOCH); a superseded epoch would be
            // refused STALE_TOPOLOGY, driving a full re-hydrate. A v2 edge would track the live epoch.
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
                // best-effort
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
