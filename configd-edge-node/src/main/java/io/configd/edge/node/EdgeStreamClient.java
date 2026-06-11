package io.configd.edge.node;

import io.configd.common.Clock;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
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
 * The edge node's socket shell (C2 design §2): owns connect / mTLS / reconnect on virtual
 * threads, encodes and decodes {@link EdgeFrameCodec} frames, feeds inbound frames to
 * {@link EdgeClientCore#onFrame}, drains the core's {@link EdgeClientCore.FrameSink}
 * (outbound CURSOR_ACKs) and {@link EdgeClientCore.ConnectionDirective} queue. NO protocol
 * logic lives here — the core owns the policy; this shell owns the sockets (the C1
 * FanOutServer split, mirrored client-side; ADR-0037: JDK sockets, no Netty).
 *
 * <h2>Stack (ADR-0037 / RR-002 discipline)</h2>
 * JDK sockets via the SAME {@link TlsManager} the control plane uses (mTLS by construction:
 * the client presents its certificate, verifies the server against the trust store, and
 * enforces HTTPS endpoint identification — F-0051). Connect and handshake are bounded
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
 * <h2>Failover (CT-11 / CT-12)</h2>
 * On any connection end — socket EOF/error, decode corruption, the core's heartbeat-silence
 * {@code ReconnectNextEndpoint} directive, or the shell's own transport-silence guard — the
 * client advances to the NEXT configured endpoint (round-robin) and re-SUBSCRIBEs carrying
 * the resume cursor ({@code resumeCursor = core.cursor()}; {@code failoverResumeCursor} set
 * once a previous endpoint had been reached, per the §3 reserved-field contract). Reads keep
 * refusing cursor-behind during catch-up — consistent refusal, enforced by
 * {@link EdgeHttpServer}, never by blocking here. Backoff between attempts is bounded and
 * jittered ({@code edge.reconnect.backoffMs} base, doubling to {@value #MAX_BACKOFF_MS} ms
 * cap, ±50% jitter).
 *
 * <h2>Apply-path exceptions (C3 seam)</h2>
 * A throwing {@code core.onFrame} (protocol-state violation, snapshot decode defect,
 * apply-time defect) is treated as connection-fatal: logged, counted as a reconnect, and the
 * session re-subscribes at the current cursor. A delta that deterministically throws on
 * apply will therefore loop (visible: {@code edge_reconnects_total} climbing,
 * {@code edge_cursor_lag} growing) — the bounded-retry → forced-snapshot → terminal
 * fail-loud poison-pill policy is C3's (ADR-0040), wired at exactly this catch site.
 */
public final class EdgeStreamClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(EdgeStreamClient.class.getName());

    /** Bounded connect timeout (ms), mirroring {@code TcpRaftTransport.CONNECT_TIMEOUT_MS}. */
    static final int CONNECT_TIMEOUT_MS = 1_000;
    /** Bounded TLS handshake timeout (ms), mirroring {@code TcpRaftTransport}. */
    static final int HANDSHAKE_TIMEOUT_MS = 2_000;
    /** Reconnect backoff cap (ms): the bound on the doubling of {@code edge.reconnect.backoffMs}. */
    static final long MAX_BACKOFF_MS = 10_000;
    /** Bounded outbound (edge→server) queue depth in frames; CURSOR_ACK-only traffic. */
    static final int OUTBOUND_QUEUE_FRAMES = 64;
    /** Bounded inbound (server→edge) queue depth in frames (full = TCP backpressure). */
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
    private final TlsManager tlsManager; // null = plaintext (test / single-node)
    private final long backoffBaseMs;
    private final long silenceWindowMs;
    private final Clock clock;
    private final EdgeNodeMetrics metrics;
    private final Runnable rebootstrapHook;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Connection current;
    private volatile Thread sessionThread;
    private EdgeClientCore core; // set in start(); touched only by the session thread after

    /**
     * @param endpoints       ordered fan-out endpoints (non-empty)
     * @param edgeId          the SUBSCRIBE identity (must match the mTLS cert DN; the server
     *                        binds the cert principal authoritatively)
     * @param prefixes        subscription prefixes (empty = full store; ADR-0038)
     * @param tlsManager      the mTLS context, or null for plaintext (test / single-node)
     * @param backoffBaseMs   {@code edge.reconnect.backoffMs} (bounded + jittered here)
     * @param silenceFactor   {@code edge.heartbeat.silenceFactor} — also bounds the shell's
     *                        transport-silence guard (no frame at all since subscribe)
     * @param clock           the wall clock (the core's tick clock)
     * @param metrics         the process metric series
     * @param rebootstrapHook the DISCONNECTED re-bootstrap trigger seam (C3 orchestrates;
     *                        may be a no-op)
     */
    public EdgeStreamClient(List<InetSocketAddress> endpoints, String edgeId,
                            List<String> prefixes, TlsManager tlsManager,
                            long backoffBaseMs, int silenceFactor,
                            Clock clock, EdgeNodeMetrics metrics, Runnable rebootstrapHook) {
        this.endpoints = List.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
        if (this.endpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one endpoint is required");
        }
        this.edgeId = Objects.requireNonNull(edgeId, "edgeId");
        this.prefixes = List.copyOf(Objects.requireNonNull(prefixes, "prefixes"));
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
        this.rebootstrapHook = rebootstrapHook != null ? rebootstrapHook : () -> { };
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

    // -----------------------------------------------------------------------
    // Session loop (the core's single writer)
    // -----------------------------------------------------------------------

    private void sessionLoop() {
        int endpointIdx = 0;
        int consecutiveFailures = 0;
        boolean reachedAnEndpoint = false; // gates failoverResumeCursor (§3 reserved field)

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
                // fatal; resubscribe-at-cursor heals transient cases. C3's ADR-0040
                // poison-pill policy (bounded retry → forced snapshot → terminal
                // fail-loud) replaces this catch site.
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
            // DISCONNECTED is reached) — pump them between connections too, or the CT-04
            // counter and the CT-06 re-bootstrap trigger would only fire on a live stream.
            metrics.syncFromCore(core, rebootstrapHook);
            // Reconnect cycle: count it, advance to the NEXT endpoint (CT-11 failover),
            // back off bounded + jittered.
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

            // The core's reconnect policy (heartbeat silence, fatal ERROR_CLOSE).
            if (core.hasDirective()) {
                EdgeClientCore.ConnectionDirective directive;
                String reason = "directive";
                while ((directive = core.pollDirective()) != null) {
                    if (directive instanceof
                            EdgeClientCore.ConnectionDirective.ReconnectNextEndpoint r) {
                        reason = r.reason();
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
     * Bounded + jittered backoff: base doubling per consecutive failure up to
     * {@value #MAX_BACKOFF_MS} ms, ±50% jitter. Slept in ≤1s slices with a staleness
     * pump per slice so a long backoff cannot delay DISCONNECTED detection by the
     * whole backoff window.
     */
    private void backoff(int consecutiveFailures) {
        long raw = Math.min(MAX_BACKOFF_MS, backoffBaseMs << Math.min(consecutiveFailures, 10));
        long delay = Math.max(1, (long) (raw * (0.5 + ThreadLocalRandom.current().nextDouble())));
        try {
            while (delay > 0 && running.get()) {
                long slice = Math.min(delay, 1_000);
                Thread.sleep(slice);
                delay -= slice;
                metrics.syncFromCore(core, rebootstrapHook);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -----------------------------------------------------------------------
    // Connect + subscribe (bounded; RR-002 discipline)
    // -----------------------------------------------------------------------

    private Connection openAndSubscribe(InetSocketAddress endpoint, boolean failedOver)
            throws IOException {
        Socket socket = createClientSocket(endpoint);
        boolean ok = false;
        try {
            // SUBSCRIBE is the first frame on the wire, written synchronously before the
            // writer thread exists, so frame order is deterministic. Resume cursor = the
            // core's applied cursor; the failover-resume reserved field carries the same
            // cursor once a PREVIOUS endpoint had been reached (§3 failover clause).
            long cursor = core.cursor();
            EdgeFrame.Subscribe subscribe = new EdgeFrame.Subscribe(
                    prefixes.isEmpty(), prefixes, cursor,
                    failedOver ? cursor : -1L, edgeId);
            OutputStream out = socket.getOutputStream();
            out.write(EdgeFrameCodec.encode(subscribe));
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
     * mirroring {@code TcpRaftTransport.createClientSocket} (RR-002 / F-0051).
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

    // -----------------------------------------------------------------------
    // One live connection: reader + writer virtual threads, bounded queues
    // -----------------------------------------------------------------------

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
                encoded = EdgeFrameCodec.encode(frame);
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
         * (ADR-0037 codec discipline), then the body. Returns null on clean EOF.
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
