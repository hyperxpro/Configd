package io.configd.server.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.FanOutSessionCore;
import io.configd.distribution.fanout.TransportSink;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.ErrorCode;
import io.configd.transport.TlsConfig;
import io.configd.transport.TlsManager;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The live mTLS edge fan-out endpoint (C1 design §2 FanOutServer layer; ADR-0037). It accepts
 * long-lived edge subscriber connections on {@code --edge-port} and drives the SAME
 * {@link FanOutSessionCore} the simulator drives — no logic exists only on this path.
 *
 * <h2>Stack (ADR-0037: no Netty)</h2>
 * JDK {@link ServerSocket} / {@link SSLServerSocket} via {@link TlsManager} (the identical
 * mTLS classes the Raft transport uses — {@code setNeedClientAuth(true)}, TLSv1.3, bounded
 * handshake), virtual-thread-per-connection. When the server has no {@link TlsManager} it
 * accepts plaintext (test / single-node), exactly mirroring the Raft transport's TLS policy.
 *
 * <h2>Per-connection threading (3 virtual threads)</h2>
 * <ul>
 *   <li><b>reader</b>: decodes inbound frames via {@link EdgeFrameCodec} (peekLength
 *       discipline). The first frame MUST be {@code SUBSCRIBE}; the edge identity is bound to
 *       the mTLS client-cert principal (see {@link #resolveEdgeIdentity}). Subsequent frames
 *       are {@code CURSOR_ACK}s routed to the session. Any garbage → close with the mapped
 *       {@link ErrorCode} ({@code FRAME_CORRUPT} / {@code PROTOCOL_VIOLATION} / …).</li>
 *   <li><b>writer</b>: drains a per-connection <b>bounded</b> {@link ArrayBlockingQueue}
 *       ({@code edge.fanout.transport.queueFrames}, default 64) of encoded frames onto the
 *       socket. The {@link TransportSink#offer} is {@code queue.offer} (non-blocking; a full
 *       queue returns {@code false}, which the session reads as transport backpressure and
 *       demotes — never an unbounded buffer, never a blocked apply path).</li>
 *   <li><b>session</b>: drives {@code session.tick(clock.millis())} with the
 *       {@link FanOutConfig#idlePollMs()} adaptive backoff (busy re-poll while data flows,
 *       park up to {@code idlePollMs} when idle). It PULLS via {@code readSince}/replay only.</li>
 * </ul>
 *
 * <p><b>Teardown.</b> When any of the three threads exits (EOF, error, session CLOSED), the
 * connection is torn down once: socket closed, session closed, writer/session signalled,
 * {@code onSessionClosed} + {@code onSubscriberDisconnected} metrics fired. Nothing is
 * unbounded; no work ever runs on the Raft apply path (the session only reads
 * {@code readSince}/{@code replaySource}).
 */
public final class FanOutServer {

    private static final Logger LOG = Logger.getLogger(FanOutServer.class.getName());

    /** Bounded TLS handshake timeout (ms), mirroring {@code TcpRaftTransport.HANDSHAKE_TIMEOUT_MS}. */
    static final int HANDSHAKE_TIMEOUT_MS = 2_000;

    /** Named config: per-connection outbound transport queue depth (frames). Design §4. */
    public static final int DEFAULT_TRANSPORT_QUEUE_FRAMES = 64;

    private final InetSocketAddress bindAddress;
    private final TlsManager tlsManager;
    private final CommitNotificationSource source;
    private final ReplaySource replaySource;
    private final FanOutConfig config;
    private final int transportQueueFrames;
    private final RegistryFanOutSessionMetrics metrics;
    private final Clock clock;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> liveSockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket serverSocket;

    public FanOutServer(InetSocketAddress bindAddress,
                        TlsManager tlsManager,
                        CommitNotificationSource source,
                        ReplaySource replaySource,
                        FanOutConfig config,
                        int transportQueueFrames,
                        RegistryFanOutSessionMetrics metrics,
                        Clock clock) {
        this.bindAddress = java.util.Objects.requireNonNull(bindAddress, "bindAddress");
        this.tlsManager = tlsManager; // null = plaintext (test/single-node)
        this.source = java.util.Objects.requireNonNull(source, "source");
        this.replaySource = java.util.Objects.requireNonNull(replaySource, "replaySource");
        this.config = java.util.Objects.requireNonNull(config, "config");
        if (transportQueueFrames <= 0) {
            throw new IllegalArgumentException("transportQueueFrames must be positive: " + transportQueueFrames);
        }
        this.transportQueueFrames = transportQueueFrames;
        this.metrics = java.util.Objects.requireNonNull(metrics, "metrics");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    /** Binds the listen socket and starts the accept loop on a virtual thread. */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("FanOutServer already started");
        }
        serverSocket = createServerSocket();
        serverSocket.bind(bindAddress);
        executor.submit(this::acceptLoop);
        LOG.info(() -> "FanOutServer listening on " + serverSocket.getLocalSocketAddress()
                + (tlsManager != null ? " (mTLS)" : " (PLAINTEXT)"));
    }

    /** The actual bound port (useful when binding to an ephemeral port 0). */
    public int localPort() {
        ServerSocket ss = serverSocket;
        return (ss != null) ? ss.getLocalPort() : -1;
    }

    /** Stops the endpoint: unblocks accept, closes all live connections, drains threads. */
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ServerSocket ss = serverSocket;
        if (ss != null) {
            closeQuietly(ss);
        }
        for (Socket s : liveSockets) {
            closeQuietly(s);
        }
        executor.shutdownNow();
    }

    // -----------------------------------------------------------------------
    // Accept loop
    // -----------------------------------------------------------------------

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                liveSockets.add(socket);
                executor.submit(() -> {
                    try {
                        handleConnection(socket);
                    } finally {
                        liveSockets.remove(socket);
                        closeQuietly(socket);
                    }
                });
            } catch (IOException e) {
                if (running.get()) {
                    LOG.log(Level.WARNING, "FanOutServer accept error", e);
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        String edgeIdentity;
        try {
            if (socket instanceof SSLSocket ssl) {
                // Bounded handshake (no deadline-less blocking — RR-002 discipline).
                ssl.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                ssl.startHandshake();
                ssl.setSoTimeout(0);
            }
            edgeIdentity = resolveEdgeIdentity(socket);
        } catch (IOException e) {
            // A failed/rejected mTLS handshake (no cert, wrong CA) lands here. AUTH_FAIL.
            LOG.fine(() -> "FanOutServer handshake/identity rejected: " + e.getMessage());
            metrics.onSessionClosed(ErrorCode.AUTH_FAIL.name());
            return;
        }

        Connection conn = new Connection(socket, edgeIdentity);
        conn.run();
    }

    // -----------------------------------------------------------------------
    // Cert identity binding (review condition)
    // -----------------------------------------------------------------------

    /**
     * Resolves the AUTHORITATIVE edge identity for a connection.
     *
     * <h3>Decision (review condition): the mTLS client-cert principal is authoritative</h3>
     * Over an mTLS connection the identity is the verified client-certificate Subject DN
     * ({@code SSLSession.getPeerPrincipal()}). The {@code SUBSCRIBE.edgeId} carried on the wire
     * is attacker-controllable and is therefore treated as <b>advisory only</b>: the server
     * binds the session to the cert principal and, if the wire {@code edgeId} differs, records
     * the cert principal as authoritative (never trusts the wire field for authorization). This
     * is the secure choice — the certificate is verified by the TLS layer against the trust
     * store; the wire field is not. Plaintext mode (no TLS — test/single-node) has no cert, so
     * the wire {@code edgeId} is used as-is.
     *
     * @return the authoritative edge identity (cert Subject DN, or {@code "plaintext"} marker)
     * @throws IOException if the peer presented no verifiable certificate over mTLS
     */
    private static String resolveEdgeIdentity(Socket socket) throws IOException {
        if (socket instanceof SSLSocket ssl) {
            try {
                // getPeerPrincipal throws SSLPeerUnverifiedException if no cert was presented;
                // with setNeedClientAuth(true) the handshake already fails first, but this is
                // the fail-closed belt-and-braces.
                return ssl.getSession().getPeerPrincipal().getName();
            } catch (Exception e) {
                throw new IOException("no verifiable client certificate: " + e.getMessage(), e);
            }
        }
        return "plaintext";
    }

    // -----------------------------------------------------------------------
    // Server socket creation (mirrors TcpRaftTransport.createServerSocket)
    // -----------------------------------------------------------------------

    private ServerSocket createServerSocket() throws IOException {
        if (tlsManager != null) {
            SSLServerSocketFactory factory = tlsManager.currentContext().getServerSocketFactory();
            SSLServerSocket ss = (SSLServerSocket) factory.createServerSocket();
            TlsConfig tlsConfig = tlsManager.config();
            if (tlsConfig != null) {
                // mTLS REQUIRED: the edge endpoint always demands a client cert.
                ss.setNeedClientAuth(true);
                if (!tlsConfig.protocols().isEmpty()) {
                    ss.setEnabledProtocols(tlsConfig.protocols().toArray(String[]::new));
                }
                if (!tlsConfig.ciphers().isEmpty()) {
                    ss.setEnabledCipherSuites(tlsConfig.ciphers().toArray(String[]::new));
                }
            } else {
                ss.setNeedClientAuth(true);
            }
            return ss;
        }
        return new ServerSocket();
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
    // Per-connection state machine
    // -----------------------------------------------------------------------

    /**
     * One edge subscriber connection: owns the reader / writer / session virtual threads, the
     * bounded outbound queue, and the {@link FanOutSessionCore}.
     */
    private final class Connection implements TransportSink {

        private final Socket socket;
        private final String edgeIdentity;
        private final ArrayBlockingQueue<byte[]> outbound;
        private final AtomicBoolean alive = new AtomicBoolean(true);

        /**
         * Inbound session commands posted by the reader thread and drained by the session
         * thread. {@link FanOutSessionCore} is single-threaded-per-instance, so EVERY call into
         * it ({@code onSubscribe} / {@code onCursorAck} / {@code tick}) happens ONLY on the
         * session thread — the reader never touches the session directly. This is the
         * R-01-style single-writer discipline applied to the session.
         */
        private final java.util.Queue<java.util.function.Consumer<FanOutSessionCore>> sessionCommands =
                new java.util.concurrent.ConcurrentLinkedQueue<>();

        private volatile FanOutSessionCore session;

        Connection(Socket socket, String edgeIdentity) {
            this.socket = socket;
            this.edgeIdentity = edgeIdentity;
            this.outbound = new ArrayBlockingQueue<>(transportQueueFrames);
        }

        void run() {
            // The session uses THIS connection as its TransportSink. Created before the reader
            // sees SUBSCRIBE so onSubscribe (drained on the session thread) can emit SUBSCRIBE_OK.
            this.session = new FanOutSessionCore(source, replaySource, this, config, metrics, clock);
            metrics.onSubscriberConnected();

            Thread writer = Thread.ofVirtual().name("edge-writer-" + edgeIdentity).unstarted(this::writerLoop);
            Thread sessionThread = Thread.ofVirtual().name("edge-session-" + edgeIdentity).unstarted(this::sessionLoop);
            writer.start();
            sessionThread.start();
            try {
                readerLoop(); // runs on the accept-submitted virtual thread
            } finally {
                teardown(ErrorCode.SERVER_SHUTDOWN, "connection closed");
                // Join the helpers so teardown is complete before the socket is released.
                joinQuietly(writer);
                joinQuietly(sessionThread);
            }
        }

        // ---- reader thread (NEVER touches the session directly — posts commands) ----

        private void readerLoop() {
            try {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                boolean subscribed = false;
                while (alive.get() && running.get()) {
                    EdgeFrame frame = readFrame(in);
                    if (frame == null) {
                        return; // EOF
                    }
                    if (!subscribed) {
                        if (!(frame instanceof EdgeFrame.Subscribe sub)) {
                            close(ErrorCode.PROTOCOL_VIOLATION, "expected SUBSCRIBE first, got " + frame.type());
                            return;
                        }
                        subscribed = true;
                        EdgeFrame.Subscribe bound = bindIdentity(sub);
                        sessionCommands.add(s -> s.onSubscribe(bound));
                    } else {
                        switch (frame) {
                            case EdgeFrame.CursorAck ack -> sessionCommands.add(s -> s.onCursorAck(ack.seq()));
                            // The edge must not send server→edge frames or a second SUBSCRIBE.
                            default -> {
                                close(ErrorCode.PROTOCOL_VIOLATION, "unexpected frame for state: " + frame.type());
                                return;
                            }
                        }
                    }
                }
            } catch (EdgeFrameCodec.CodecException e) {
                close(e.code(), "decode error: " + e.getMessage());
            } catch (IOException e) {
                // Socket closed / reset — normal teardown path.
                if (alive.get()) {
                    LOG.fine(() -> "edge reader I/O end: " + e.getMessage());
                }
            }
        }

        /**
         * Binds the wire SUBSCRIBE's edgeId to the authoritative cert identity (mTLS) — the wire
         * field stays advisory. Over plaintext the wire edgeId is used as-is.
         */
        private EdgeFrame.Subscribe bindIdentity(EdgeFrame.Subscribe wire) {
            if ("plaintext".equals(edgeIdentity)) {
                return wire;
            }
            if (!edgeIdentity.equals(wire.edgeId())) {
                LOG.fine(() -> "SUBSCRIBE edgeId '" + wire.edgeId() + "' overridden by cert identity '"
                        + edgeIdentity + "'");
            }
            return new EdgeFrame.Subscribe(wire.fullStore(), wire.prefixes(), wire.resumeCursor(),
                    wire.failoverResumeCursor(), edgeIdentity);
        }

        /**
         * Reads one frame: length prefix (peekLength-bounded BEFORE allocation), then the body.
         * Returns null on a clean EOF.
         */
        private EdgeFrame readFrame(DataInputStream in) throws IOException {
            int length;
            try {
                length = in.readInt();
            } catch (EOFException eof) {
                return null; // clean stream end
            }
            // Bounds-check the declared length BEFORE allocating (ADR-0037 / peekLength).
            byte[] header4 = new byte[]{
                    (byte) (length >>> 24), (byte) (length >>> 16),
                    (byte) (length >>> 8), (byte) length};
            int total = EdgeFrameCodec.peekLength(header4); // throws CodecException if out of range
            byte[] frameBytes = new byte[total];
            frameBytes[0] = header4[0];
            frameBytes[1] = header4[1];
            frameBytes[2] = header4[2];
            frameBytes[3] = header4[3];
            in.readFully(frameBytes, 4, total - 4);
            return EdgeFrameCodec.decode(frameBytes);
        }

        // ---- writer thread ----

        private void writerLoop() {
            try {
                OutputStream out = socket.getOutputStream();
                while (alive.get()) {
                    byte[] frame = outbound.take(); // blocks until a frame or POISON
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
                // A writer failure tears down the whole connection (no half-open session).
                teardown(ErrorCode.SERVER_SHUTDOWN, "writer ended");
            }
        }

        // ---- session thread ----

        private void sessionLoop() {
            long idleParkNanos = 0;
            try {
                while (alive.get() && running.get()) {
                    // Drain inbound session commands (subscribe / cursor-ack) FIRST so every
                    // session mutation happens on THIS thread (single-writer; the session is not
                    // thread-safe). A posted command counts as progress.
                    boolean drainedCommand = false;
                    java.util.function.Consumer<FanOutSessionCore> cmd;
                    while ((cmd = sessionCommands.poll()) != null) {
                        cmd.accept(session);
                        drainedCommand = true;
                    }

                    int beforeDepth = session.inFlightFrames();
                    long beforeCursor = session.cursor();
                    session.tick(clock.currentTimeMillis());
                    if (session.state() == FanOutSessionCore.SessionState.CLOSED) {
                        return;
                    }
                    boolean madeProgress = drainedCommand
                            || session.cursor() != beforeCursor
                            || session.inFlightFrames() != beforeDepth;
                    if (madeProgress) {
                        idleParkNanos = 0; // active: immediate re-poll
                    } else {
                        // Idle: adaptive backoff capped at idlePollMs (design §4).
                        long capNanos = config.idlePollMs() * 1_000_000L;
                        idleParkNanos = Math.min(capNanos,
                                idleParkNanos == 0 ? 100_000L : idleParkNanos * 2);
                        java.util.concurrent.locks.LockSupport.parkNanos(idleParkNanos);
                    }
                }
            } finally {
                teardown(ErrorCode.SERVER_SHUTDOWN, "session ended");
            }
        }

        // ---- TransportSink (the only boundary; socket lives here, not in the session) ----

        @Override
        public boolean offer(EdgeFrame frame) {
            if (!alive.get()) {
                return false;
            }
            byte[] encoded;
            try {
                encoded = EdgeFrameCodec.encode(frame);
            } catch (EdgeFrameCodec.CodecException e) {
                // An un-encodable frame is a server bug; drop the connection loudly.
                LOG.log(Level.WARNING, "edge frame encode failure", e);
                return false;
            }
            // Non-blocking: a full queue is transport backpressure (the session demotes).
            return outbound.offer(encoded);
        }

        @Override
        public void close(ErrorCode code, String message) {
            teardown(code, message);
        }

        // ---- teardown (idempotent) ----

        private void teardown(ErrorCode code, String message) {
            if (!alive.compareAndSet(true, false)) {
                return; // already torn down
            }
            FanOutSessionCore s = session;
            if (s != null && s.state() != FanOutSessionCore.SessionState.CLOSED) {
                // Best-effort: try to push a final ERROR_CLOSE before the socket dies.
                try {
                    byte[] bye = EdgeFrameCodec.encode(new EdgeFrame.ErrorClose(code, message));
                    socket.getOutputStream().write(bye);
                    socket.getOutputStream().flush();
                } catch (Exception ignored) {
                    // the peer may already be gone; teardown proceeds regardless
                }
            }
            outbound.offer(POISON); // unblock the writer's take()
            closeQuietly(socket);
            metrics.onSessionClosed(code.name());
            metrics.onSubscriberDisconnected();
        }

        private void joinQuietly(Thread t) {
            try {
                t.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Sentinel pushed to the outbound queue to unblock the writer's blocking {@code take()}. */
    private static final byte[] POISON = new byte[0];
}
