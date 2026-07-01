package io.configd.server.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.FanOutConnectionDriver;
import io.configd.distribution.fanout.FanOutSessionCore;
import io.configd.distribution.fanout.SlowConsumerGovernor;
import io.configd.distribution.fanout.SlowConsumerPolicyConfig;
import io.configd.distribution.fanout.TransportSink;
import io.configd.distribution.fanout.WatchAuthorizer;
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
 * The live mTLS edge fan-out endpoint. It accepts
 * long-lived edge subscriber connections on {@code --edge-port} and drives the SAME
 * {@link FanOutSessionCore} the simulator drives - no logic exists only on this path.
 *
 * <h2>Stack</h2>
 * JDK {@link ServerSocket} / {@link SSLServerSocket} via {@link TlsManager} (the identical
 * mTLS classes the Raft transport uses - {@code setNeedClientAuth(true)}, TLSv1.3, bounded
 * handshake), virtual-thread-per-connection. When the server has no {@link TlsManager} it
 * accepts plaintext (test / single-node), exactly mirroring the Raft transport's TLS policy.
 *
 * <h2>Per-connection threading (3 virtual threads)</h2>
 * <ul>
 *   <li><b>reader</b>: decodes inbound frames via {@link EdgeFrameCodec} (peekLength
 *       discipline). The first frame MUST be {@code SUBSCRIBE}; the edge identity is bound to
 *       the mTLS client-cert principal (see {@link #resolveEdgeIdentity}). Subsequent frames
 *       are {@code CURSOR_ACK}s routed to the session. Any garbage -> close with the mapped
 *       {@link ErrorCode} ({@code FRAME_CORRUPT} / {@code PROTOCOL_VIOLATION} / ...).</li>
 *   <li><b>writer</b>: drains a per-connection <b>bounded</b> {@link ArrayBlockingQueue}
 *       ({@code edge.fanout.transport.queueFrames}, default 64) of encoded frames onto the
 *       socket. The {@link TransportSink#offer} is {@code queue.offer} (non-blocking; a full
 *       queue returns {@code false}, which the session reads as transport backpressure and
 *       demotes - never an unbounded buffer, never a blocked apply path).</li>
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
public final class FanOutServer implements FanOutEndpoint {

    private static final Logger LOG = Logger.getLogger(FanOutServer.class.getName());

    /** Bounded TLS handshake timeout (ms), mirroring {@code TcpRaftTransport.HANDSHAKE_TIMEOUT_MS}. */
    static final int HANDSHAKE_TIMEOUT_MS = 2_000;

    /** Named config: per-connection outbound transport queue depth (frames). Design section 4. */
    public static final int DEFAULT_TRANSPORT_QUEUE_FRAMES = 64;

    /**
     * Named config {@code edge.fanout.transport.maxSessions} (hard rule 4: no unbounded
     * designs): maximum concurrently accepted edge connections, INCLUDING connections
     * still mid-handshake (the bound is applied BEFORE the handshake, so half-open
     * slowloris connections cannot exhaust file descriptors / virtual threads).
     * Refusals are counted on {@code edge_fanout_sessions_refused_total}.
     */
    public static final int DEFAULT_MAX_SESSIONS = 1_024;

    private final InetSocketAddress bindAddress;
    private final TlsManager tlsManager;
    private final CommitNotificationSource source;
    private final ReplaySource replaySource;
    private final FanOutConfig config;
    private final int transportQueueFrames;
    private final int maxSessions;
    private final SlowConsumerGovernor governor;
    private final RegistryFanOutSessionMetrics metrics;
    private final Clock clock;

    /**
     * The authorization gate, or {@code null} when no principal model is wired. It gates both
     * {@code WATCH_CREATE} (per-target) and the legacy full-store {@code SUBSCRIBE} (whole-store READ).
     * A {@code null} authorizer fails CLOSED for watches (every {@code WATCH_CREATE} ->
     * {@code NOT_AUTHORIZED}) but admits {@code SUBSCRIBE} (auth off). The pre-watch constructors pass
     * {@code null}; {@code ConfigdServer} threads a real authorizer.
     */
    private final WatchAuthorizer authorizer;

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
        this(bindAddress, tlsManager, source, replaySource, config, transportQueueFrames,
                DEFAULT_MAX_SESSIONS, metrics, clock);
    }

    public FanOutServer(InetSocketAddress bindAddress,
                        TlsManager tlsManager,
                        CommitNotificationSource source,
                        ReplaySource replaySource,
                        FanOutConfig config,
                        int transportQueueFrames,
                        int maxSessions,
                        RegistryFanOutSessionMetrics metrics,
                        Clock clock) {
        this(bindAddress, tlsManager, source, replaySource, config, transportQueueFrames,
                maxSessions,
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(),
                        java.util.Objects.requireNonNull(metrics, "metrics")),
                metrics, clock);
    }

    /**
     * Full constructor with an explicit {@link SlowConsumerGovernor} (C4): the
     * per-identity slow-consumer policy consulted at SUBSCRIBE (quarantine/unhealthy
     * refusal, forced snapshot-first readmission) and fed by the per-session demotion /
     * ack-progress / queue-pressure signals. The delegating constructors build one with
     * {@link SlowConsumerPolicyConfig#defaults()}.
     */
    public FanOutServer(InetSocketAddress bindAddress,
                        TlsManager tlsManager,
                        CommitNotificationSource source,
                        ReplaySource replaySource,
                        FanOutConfig config,
                        int transportQueueFrames,
                        int maxSessions,
                        SlowConsumerGovernor governor,
                        RegistryFanOutSessionMetrics metrics,
                        Clock clock) {
        this(bindAddress, tlsManager, source, replaySource, config, transportQueueFrames, maxSessions,
                governor, metrics, clock, null);
    }

    /**
     * Full constructor with the authorization gate ({@code authorizer}). A {@code null} authorizer
     * fails CLOSED for watches (rejected {@code NOT_AUTHORIZED}) and admits the legacy full-store
     * {@code SUBSCRIBE} (auth off); a wired authorizer additionally gates {@code SUBSCRIBE} on
     * whole-store READ. {@code ConfigdServer} threads the {@code AclServiceWatchAuthorizer} here. The
     * JDK transport is retained as a drop-in {@code FanOutEndpoint} (the Netty transport is
     * production); both drive the SAME {@link FanOutConnectionDriver}, so the wiring is identical and
     * the contract proves both.
     */
    public FanOutServer(InetSocketAddress bindAddress,
                        TlsManager tlsManager,
                        CommitNotificationSource source,
                        ReplaySource replaySource,
                        FanOutConfig config,
                        int transportQueueFrames,
                        int maxSessions,
                        SlowConsumerGovernor governor,
                        RegistryFanOutSessionMetrics metrics,
                        Clock clock,
                        WatchAuthorizer authorizer) {
        this.bindAddress = java.util.Objects.requireNonNull(bindAddress, "bindAddress");
        this.tlsManager = tlsManager; // null = plaintext (test/single-node)
        this.source = java.util.Objects.requireNonNull(source, "source");
        this.replaySource = java.util.Objects.requireNonNull(replaySource, "replaySource");
        this.config = java.util.Objects.requireNonNull(config, "config");
        if (transportQueueFrames <= 0) {
            throw new IllegalArgumentException("transportQueueFrames must be positive: " + transportQueueFrames);
        }
        this.transportQueueFrames = transportQueueFrames;
        if (maxSessions <= 0) {
            throw new IllegalArgumentException("maxSessions must be positive: " + maxSessions);
        }
        this.maxSessions = maxSessions;
        this.governor = java.util.Objects.requireNonNull(governor, "governor");
        this.metrics = java.util.Objects.requireNonNull(metrics, "metrics");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.authorizer = authorizer; // nullable => no watch capability => driver fails closed
    }

    /** The slow-consumer governor this endpoint enforces (C4; for tests/diagnostics). */
    public SlowConsumerGovernor governor() {
        return governor;
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
                // Admission bound BEFORE the handshake (hard rule 4): beyond
                // maxSessions the socket is closed immediately - half-open
                // handshakes count, so they cannot exhaust fds/threads.
                if (liveSockets.size() >= maxSessions) {
                    metrics.onSessionRefused();
                    closeQuietly(socket);
                    continue;
                }
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
                // Bounded handshake (no deadline-less blocking).
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
    // Cert identity binding
    // -----------------------------------------------------------------------

    /**
     * Resolves the AUTHORITATIVE edge identity for a connection.
     *
     * <h3>Decision: the mTLS client-cert principal is authoritative</h3>
     * Over an mTLS connection the identity is the verified client-certificate Subject DN
     * ({@code SSLSession.getPeerPrincipal()}). The {@code SUBSCRIBE.edgeId} carried on the wire
     * is attacker-controllable and is therefore treated as <b>advisory only</b>: the server
     * binds the session to the cert principal and, if the wire {@code edgeId} differs, records
     * the cert principal as authoritative (never trusts the wire field for authorization). This
     * is the secure choice - the certificate is verified by the TLS layer against the trust
     * store; the wire field is not. Plaintext mode (no TLS - test/single-node) has no cert, so
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
     * One edge subscriber connection: owns the socket, the reader / writer / session virtual
     * threads, and the bounded outbound queue. All session logic - inbound routing, cert-identity
     * binding, C4 admission, the tick + governor-feed loop, demotion handling - lives in the shared
     * {@link FanOutConnectionDriver}, identical to the Netty fan-out transport's. This
     * class is the JDK-socket <em>body</em>; the driver is the transport-agnostic <em>brain</em>.
     */
    private final class Connection implements TransportSink {

        private final Socket socket;
        private final String edgeIdentity;
        private final ArrayBlockingQueue<byte[]> outbound;
        private final AtomicBoolean alive = new AtomicBoolean(true);

        /** The transport-agnostic session brain (created in {@link #run()} before the reader runs). */
        private volatile FanOutConnectionDriver driver;

        /**
         * Negotiated OUTBOUND edge wire version. Default {@code 0x01} (legacy); flipped to
         * {@code 0x02} by the reader when the FIRST inbound frame is a {@code WATCH_CREATE} (a watch
         * connection), so {@link #offer} stamps {@code 0x02} and a {@code 0x02} client can decode the
         * server's {@code WATCH_*} frames. Written by the reader thread, read by the session
         * thread (in {@code offer}) and teardown -> {@code volatile}. A legacy connection never flips it
         * -> stays {@code 0x01} -> byte-identical.
         */
        private volatile byte wireVersion = EdgeFrameCodec.EDGE_WIRE_VERSION;

        /**
         * The negotiated INBOUND wire version pin, or {@code 0} until the first frame establishes it.
         * Reader-thread-only. The first frame is decoded accepting either version, then pinned
         * to its stamp; subsequent frames decode under the pin (a mismatched version -> BAD_WIRE_VERSION).
         */
        private byte inboundNegotiatedVersion;

        /** Reader-thread-only: whether the connection-type-deciding first inbound frame has been routed. */
        private boolean firstFrameRouted;

        Connection(Socket socket, String edgeIdentity) {
            this.socket = socket;
            this.edgeIdentity = edgeIdentity;
            this.outbound = new ArrayBlockingQueue<>(transportQueueFrames);
        }

        void run() {
            // The driver uses THIS connection as its TransportSink + teardown hook. Created before
            // the reader sees SUBSCRIBE so onSubscribe (run on the session thread) can emit
            // SUBSCRIBE_OK; the driver's demotion arm tears the connection down with the on-wire
            // ErrorCode.QUARANTINED (code 8) + socket close when policy trips.
            this.driver = new FanOutConnectionDriver(source, replaySource, this, config, metrics,
                    clock, governor, edgeIdentity, this::teardown, authorizer);
            metrics.onSubscriberConnected();

            Thread writer = Thread.ofVirtual().name("edge-writer-" + edgeIdentity).unstarted(this::writerLoop);
            Thread sessionThread = Thread.ofVirtual().name("edge-session-" + edgeIdentity)
                    .unstarted(() -> driver.runSessionLoop(() -> alive.get() && running.get()));
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

        // ---- reader thread (decode only; routing is the driver's, never touches the session) ----

        private void readerLoop() {
            try {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                while (alive.get() && running.get()) {
                    EdgeFrame frame = readFrame(in);
                    if (frame == null) {
                        return; // EOF
                    }
                    if (!firstFrameRouted) {
                        firstFrameRouted = true;
                        // Outbound flip: a WATCH_CREATE-first connection is a 0x02 watch connection,
                        // so offer() must stamp 0x02 for the client to decode the server's WATCH_* frames.
                        // A SUBSCRIBE-first legacy connection stays 0x01 (byte-identical). The
                        // flip happens-before any outbound watch frame the session thread later produces
                        // (it is posted as a session command AFTER this flip). (A WATCH_CREATE is always
                        // 0x02-stamped - the codec forbids WATCH_* under 0x01 - so the type-based flip and
                        // the stamp-based inbound pin agree for every real connection.)
                        if (frame instanceof EdgeFrame.WatchCreate) {
                            wireVersion = EdgeFrameCodec.EDGE_WIRE_VERSION_V2;
                        }
                    }
                    driver.onInboundFrame(frame);
                }
            } catch (EdgeFrameCodec.CodecException e) {
                close(e.code(), "decode error: " + e.getMessage());
            } catch (IOException e) {
                // Socket closed / reset - normal teardown path.
                if (alive.get()) {
                    LOG.fine(() -> "edge reader I/O end: " + e.getMessage());
                }
            }
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
            // Bounds-check the declared length BEFORE allocating (peekLength-bounded).
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
            if (inboundNegotiatedVersion == 0) {
                // First frame: accept either version (CRC-validated), then PIN to its stamp.
                EdgeFrame frame = EdgeFrameCodec.decode(frameBytes);
                inboundNegotiatedVersion = EdgeFrameCodec.peekVersion(frameBytes); // known 0x01/0x02
                return frame;
            }
            // Pinned: a frame stamped with the OTHER accepted version -> BAD_WIRE_VERSION (fail closed).
            return EdgeFrameCodec.decode(frameBytes, inboundNegotiatedVersion);
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

        // ---- TransportSink (the only boundary; socket lives here, not in the session) ----

        @Override
        public boolean offer(EdgeFrame frame) {
            if (!alive.get()) {
                return false;
            }
            byte[] encoded;
            try {
                // Stamp the connection's negotiated wire version: 0x01 legacy
                // (byte-identical), 0x02 on a watch connection so the client can decode WATCH_* frames.
                encoded = EdgeFrameCodec.encode(frame, wireVersion);
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
            FanOutConnectionDriver d = driver;
            FanOutSessionCore s = (d != null) ? d.session() : null;
            if (s != null && s.state() != FanOutSessionCore.SessionState.CLOSED) {
                // Best-effort: try to push a final ERROR_CLOSE before the socket dies. Stamp the
                // connection's negotiated version so a 0x02 watch client can decode the bye.
                try {
                    byte[] bye = EdgeFrameCodec.encode(new EdgeFrame.ErrorClose(code, message), wireVersion);
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
