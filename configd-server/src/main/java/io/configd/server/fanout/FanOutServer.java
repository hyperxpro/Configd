package io.configd.server.fanout;

import io.configd.common.Clock;
import io.configd.common.auth.AuthResult;
import io.configd.common.auth.Credential;
import io.configd.common.auth.Principal;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.FanOutConnectionDriver;
import io.configd.distribution.fanout.FanOutSessionCore;
import io.configd.distribution.fanout.ShardResolver;
import io.configd.distribution.fanout.SlowConsumerGovernor;
import io.configd.distribution.fanout.SlowConsumerPolicyConfig;
import io.configd.distribution.fanout.TransportSink;
import io.configd.distribution.fanout.WatchAuthorizer;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.ErrorCode;
import io.configd.distribution.wire.WatchCursor;
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
import java.net.SocketTimeoutException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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

    /**
     * System property: the pre-SUBSCRIBE first-frame deadline (ms) for an admitted (post-mTLS)
     * edge connection (WH-11). Mirrors {@code configd.raft.inboundReadTimeoutMs}; the slow-loris
     * test sets a short value.
     */
    public static final String FIRST_FRAME_DEADLINE_PROP = "configd.edge.firstFrameDeadlineMs";

    /**
     * Default pre-SUBSCRIBE first-frame deadline (ms). Generous ({@value}) so a healthy subscriber
     * always sends its SUBSCRIBE / WATCH_CREATE well within it; a peer that completes mTLS then
     * sends nothing is reaped after this window. AFTER the first routed frame the deadline is
     * DISARMED - an established subscriber is idle by design (server pushes; the existing
     * server->client HEARTBEAT is its liveness), so it is never read-idle-reaped.
     */
    public static final int DEFAULT_FIRST_FRAME_DEADLINE_MS = 10_000;

    /**
     * The configured pre-SUBSCRIBE first-frame deadline (ms), tunable via
     * {@value #FIRST_FRAME_DEADLINE_PROP} (default {@link #DEFAULT_FIRST_FRAME_DEADLINE_MS}).
     * Shared by both the JDK and Netty edge transports.
     */
    public static int firstFrameDeadlineMs() {
        return Integer.getInteger(FIRST_FRAME_DEADLINE_PROP, DEFAULT_FIRST_FRAME_DEADLINE_MS);
    }

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

    /** The single-shard resolver the single-source constructors bind: every target -> gid 0. */
    private static final ShardResolver SINGLE_SHARD = t -> new int[]{0};

    private final InetSocketAddress bindAddress;
    private final TlsManager tlsManager;
    /** gid -> that shard's commit source; single-entry {@code {0 -> source}} for the single-shard ctors. */
    private final Map<Integer, CommitNotificationSource> shardSources;
    /** gid -> that shard's replay source; single-entry for the single-shard ctors. */
    private final Map<Integer, ReplaySource> shardReplaySources;
    /** The connection's shard set, ascending ({@code [0, N)}); {@code {0}} for the single-shard ctors. */
    private final int[] allGids;
    /** Resolves a watch target to its covered shard set; {@link #SINGLE_SHARD} for the single-shard ctors. */
    private final ShardResolver shardResolver;
    /** The server's topology epoch ({@code ShardMap.epoch()}), threaded into every session driver (A4). */
    private final long topologyEpoch;
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

    /**
     * The edge token-authentication posture, or {@code null} for the mTLS-only / plaintext posture
     * (byte-identical to the pre-token edge). When non-null the blocking reader gates every connection
     * on an accepted {@code AUTH} frame (unless a verified client cert authenticated it at the
     * handshake), the frame reader enforces the pre-auth ceiling, and the listen socket is
     * {@code wantClientAuth}.
     */
    private final EdgeAuthConfig edgeAuth;

    /**
     * The edge client-cert validity gate ({@link EdgeCertGate#OFF} = no online revocation + no active cert
     * expiry, byte-identical). Applied at admission on BOTH the mTLS-only path and the token-edge cert path.
     * Never constructed for the Raft interior, so the {@code exemptInterNode} invariant holds by construction.
     */
    private final EdgeCertGate certGate;

    /**
     * The one-shot credential-expiry scheduler, shared by all connections that arm an expiry (token TTL, or
     * mTLS cert {@code notAfter} when {@link EdgeCertGate#enforcesCertExpiry()}); {@code null} when neither
     * is in play (no allocation on the byte-identical path). A fired task closes the socket, so the blocking
     * reader unwinds into the teardown path.
     */
    private final ScheduledExecutorService authExpiryScheduler;

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
        this(Map.of(0, java.util.Objects.requireNonNull(source, "source")),
                Map.of(0, java.util.Objects.requireNonNull(replaySource, "replaySource")),
                new int[]{0}, SINGLE_SHARD, WatchCursor.INITIAL_TOPOLOGY_EPOCH, bindAddress, tlsManager,
                config, transportQueueFrames, maxSessions, governor, metrics, clock, authorizer, null,
                EdgeCertGate.OFF);
    }

    /**
     * The multi-shard constructor: the per-shard commit sources + replay sources + shard set +
     * resolver the fan-out/fan-in coordinator fans a watch across. At {@code N = 1} the single-source
     * constructors delegate here with single-entry maps and the single-shard resolver, so one core is
     * the single-shard drain (byte-identical). {@code ConfigdServer} threads the real per-shard maps.
     *
     * @param edgeAuth the edge token-authentication posture, or {@code null} for the mTLS-only /
     *                 plaintext posture (byte-identical to the pre-token edge)
     */
    public FanOutServer(Map<Integer, CommitNotificationSource> shardSources,
                        Map<Integer, ReplaySource> shardReplaySources,
                        int[] allGids,
                        ShardResolver shardResolver,
                        long topologyEpoch,
                        InetSocketAddress bindAddress,
                        TlsManager tlsManager,
                        FanOutConfig config,
                        int transportQueueFrames,
                        int maxSessions,
                        SlowConsumerGovernor governor,
                        RegistryFanOutSessionMetrics metrics,
                        Clock clock,
                        WatchAuthorizer authorizer,
                        EdgeAuthConfig edgeAuth,
                        EdgeCertGate certGate) {
        this.shardSources = Map.copyOf(java.util.Objects.requireNonNull(shardSources, "shardSources"));
        this.shardReplaySources =
                Map.copyOf(java.util.Objects.requireNonNull(shardReplaySources, "shardReplaySources"));
        this.allGids = java.util.Objects.requireNonNull(allGids, "allGids").clone();
        this.shardResolver = java.util.Objects.requireNonNull(shardResolver, "shardResolver");
        if (topologyEpoch <= WatchCursor.EPOCH_UNSET) {
            throw new IllegalArgumentException(
                    "topologyEpoch must be in [1, 2^63) (0 is reserved-illegal): " + topologyEpoch);
        }
        this.topologyEpoch = topologyEpoch;
        this.bindAddress = java.util.Objects.requireNonNull(bindAddress, "bindAddress");
        this.tlsManager = tlsManager; // null = plaintext (test/single-node)
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
        this.edgeAuth = edgeAuth; // nullable => mTLS-only / plaintext => byte-identical to the pre-token edge
        this.certGate = java.util.Objects.requireNonNullElse(certGate, EdgeCertGate.OFF);
        // Allocate the expiry scheduler only when something can arm an expiry: token auth, or mTLS cert
        // notAfter enforcement. Neither in play => null => no thread => byte-identical.
        this.authExpiryScheduler = (edgeAuth != null || this.certGate.enforcesCertExpiry())
                ? Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "edge-jdk-auth-expiry");
                    t.setDaemon(true);
                    return t;
                })
                : null;
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
        if (authExpiryScheduler != null) {
            authExpiryScheduler.shutdownNow();
        }
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
        boolean authGated;
        long certCloseDeadline = AuthState.NO_EXPIRY;
        try {
            if (socket instanceof SSLSocket ssl) {
                // Bounded handshake (no deadline-less blocking).
                ssl.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                ssl.startHandshake();
                ssl.setSoTimeout(0);
            }
            if (edgeAuth != null) {
                // Token auth: a verified client certificate authenticates at the handshake
                // (byte-identical identity, no AUTH frame); a certificate-less client must present an
                // AUTH frame, so the reader gates it. The listen socket is wantClientAuth, so a certless
                // handshake succeeds and returns no peer certificate.
                List<X509Certificate> chain = verifiedPeerChain(socket);
                if (chain != null) {
                    AuthResult result = edgeAuth.authenticateClientCertificate(chain);
                    if (!(result instanceof AuthResult.Authenticated a)) {
                        LOG.fine(() -> "FanOutServer client certificate rejected");
                        metrics.onSessionClosed(ErrorCode.AUTH_FAIL.name());
                        return;
                    }
                    if (!certGate.admit(chain)) {
                        LOG.fine(() -> "FanOutServer client certificate revoked/unverifiable");
                        metrics.onSessionClosed(ErrorCode.AUTH_FAIL.name());
                        return;
                    }
                    edgeIdentity = a.principal().id();
                    authGated = false;
                    certCloseDeadline = certGate.certCloseDeadlineMillis(chain);
                } else {
                    edgeIdentity = null; // established by the AUTH frame the reader awaits
                    authGated = true;
                }
            } else {
                // mTLS-only / plaintext: revocation + cert-notAfter enforcement apply to the client cert
                // (if any); a plaintext connection has no chain, so both are no-ops (byte-identical).
                List<X509Certificate> chain = verifiedPeerChain(socket);
                if (chain != null && !certGate.admit(chain)) {
                    LOG.fine(() -> "FanOutServer client certificate revoked/unverifiable");
                    metrics.onSessionClosed(ErrorCode.AUTH_FAIL.name());
                    return;
                }
                edgeIdentity = resolveEdgeIdentity(socket); // throws over mTLS if no verifiable cert
                authGated = false;
                certCloseDeadline = certGate.certCloseDeadlineMillis(chain);
            }
        } catch (IOException e) {
            // A failed/rejected mTLS handshake (no cert, wrong CA) lands here. AUTH_FAIL.
            LOG.fine(() -> "FanOutServer handshake/identity rejected: " + e.getMessage());
            metrics.onSessionClosed(ErrorCode.AUTH_FAIL.name());
            return;
        }

        Connection conn = new Connection(socket, edgeIdentity, authGated, certCloseDeadline);
        conn.run();
    }

    /** The verified peer certificate chain, or {@code null} if the peer presented none (certless). */
    private static List<X509Certificate> verifiedPeerChain(Socket socket) {
        if (!(socket instanceof SSLSocket ssl)) {
            return null; // plaintext token connection: certless, must AUTH
        }
        try {
            Certificate[] certs = ssl.getSession().getPeerCertificates();
            List<X509Certificate> chain = new ArrayList<>(certs.length);
            for (Certificate c : certs) {
                if (c instanceof X509Certificate x) {
                    chain.add(x);
                }
            }
            return chain.isEmpty() ? null : chain;
        } catch (Exception e) {
            return null; // no verifiable client certificate
        }
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
            // Token auth relaxes the client cert from REQUIRED to WANTED so a certificate-less token
            // client can connect (a presented cert is still validated; a certless one proceeds to its
            // AUTH frame). Without token auth the edge always demands a client cert (mTLS REQUIRED).
            if (edgeAuth != null) {
                ss.setWantClientAuth(true);
            } else {
                ss.setNeedClientAuth(true);
            }
            if (tlsConfig != null) {
                if (!tlsConfig.protocols().isEmpty()) {
                    ss.setEnabledProtocols(tlsConfig.protocols().toArray(String[]::new));
                }
                if (!tlsConfig.ciphers().isEmpty()) {
                    ss.setEnabledCipherSuites(tlsConfig.ciphers().toArray(String[]::new));
                }
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
        /**
         * The bound edge identity. For a cert-authenticated or non-token connection it is known at
         * construction (byte-identical to before); for a certificate-less token connection it is
         * {@code null} until the {@code AUTH} frame resolves it, at which point the session starts
         * bound to the token principal's id.
         */
        private final String edgeIdentity;
        /**
         * True when this connection must authenticate via an {@code AUTH} frame before any business
         * frame (a certificate-less token connection). The driver + writer + session threads start
         * lazily on that authentication. False for the cert / non-token path, which starts eagerly and
         * is byte-identical to the pre-token reader.
         */
        private final boolean authGated;
        /**
         * The wall-clock close deadline for an mTLS cert connection's {@code notAfter} enforcement, or
         * {@link AuthState#NO_EXPIRY} when disabled (byte-identical). Armed once, eagerly, in {@link #run()}
         * for a cert connection (the token path arms its own deadline on the AUTH frame instead).
         */
        private final long certCloseDeadlineMillis;
        private final ArrayBlockingQueue<byte[]> outbound;
        private final AtomicBoolean alive = new AtomicBoolean(true);

        /** The transport-agnostic session brain (created eagerly, or lazily on token auth). */
        private volatile FanOutConnectionDriver driver;

        /** The writer / session threads (fields so a token connection can start them lazily). */
        private Thread writer;
        private Thread sessionThread;

        /** Whether {@code onSubscriberConnected} was counted (pairs the disconnect; token-gated pre-auth
         * connections never connect, so their teardown must not emit a phantom disconnect). */
        private volatile boolean connectedCounted;

        /** Reader-thread-only: the per-connection auth state (only meaningful when {@link #authGated}). */
        private AuthState authState = AuthState.UNAUTHENTICATED;

        /** Reader-thread-only: whether the first BUSINESS frame (which pins the outbound version) was seen. */
        private boolean firstBusinessRouted;

        /** The token-TTL expiry one-shot, armed on token auth and re-armed on {@code REFRESH_AUTH}. */
        private volatile ScheduledFuture<?> expiryTask;

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

        Connection(Socket socket, String edgeIdentity, boolean authGated, long certCloseDeadlineMillis) {
            this.socket = socket;
            this.edgeIdentity = edgeIdentity;
            this.authGated = authGated;
            this.certCloseDeadlineMillis = certCloseDeadlineMillis;
            this.outbound = new ArrayBlockingQueue<>(transportQueueFrames);
        }

        void run() {
            try {
                if (!authGated) {
                    // Cert / non-token: identity known at the handshake; start eagerly with empty roles
                    // (the ACL resolves the DN's config-bound roles internally) - byte-identical.
                    startSessionThreads(edgeIdentity, Set.of());
                    armCertExpiry(); // mTLS cert notAfter enforcement (NO_EXPIRY = off = no-op)
                }
                // A token-gated connection starts its session lazily when its AUTH frame authenticates.
                readerLoop(); // runs on the accept-submitted virtual thread
            } finally {
                teardown(ErrorCode.SERVER_SHUTDOWN, "connection closed");
                // Join the helpers so teardown is complete before the socket is released (they are null
                // for a token connection that closed before authenticating).
                joinQuietly(writer);
                joinQuietly(sessionThread);
            }
        }

        /**
         * Creates the session brain and starts the writer + session threads, bound to {@code identity}.
         * The driver is created before the reader routes SUBSCRIBE so {@code onSubscribe} (on the
         * session thread) can emit SUBSCRIBE_OK, and its demotion arm tears the connection down with the
         * on-wire {@code QUARANTINED} + socket close when policy trips. Called exactly once per
         * connection - eagerly for a cert / non-token connection, or lazily on the first token auth.
         */
        private void startSessionThreads(String identity, Set<String> roles) {
            this.driver = new FanOutConnectionDriver(shardSources, shardReplaySources, allGids,
                    shardResolver, topologyEpoch, this, config, metrics, clock, governor, identity, roles,
                    this::teardown, authorizer);
            metrics.onSubscriberConnected();
            connectedCounted = true;
            this.writer = Thread.ofVirtual().name("edge-writer-" + identity).unstarted(this::writerLoop);
            this.sessionThread = Thread.ofVirtual().name("edge-session-" + identity)
                    .unstarted(() -> driver.runSessionLoop(() -> alive.get() && running.get()));
            writer.start();
            sessionThread.start();
        }

        // ---- reader thread (decode only; routing is the driver's, never touches the session) ----

        private void readerLoop() {
            try {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                // WH-11 (C3): ABSOLUTE pre-SUBSCRIBE first-frame deadline. A per-read soTimeout is
                // evadable by a slow-loris that dribbles >=1 byte per window - each partial read resets
                // the timer, so the deadline never fires; an absolute wall-clock budget is not evadable.
                // readFrame re-arms the socket timeout to the REMAINING budget before EVERY underlying
                // read until the first frame is routed (see readBounded), and aborts with a
                // SocketTimeoutException once the budget is exhausted regardless of dribbles. A peer
                // that completed mTLS (or connected in plaintext) then stalls - sending nothing OR
                // dribbling forever - is reaped, instead of parking a reader thread + FD + cumulator
                // until the OS reaps it. DISARMED (deadline 0, soTimeout 0) once the first routed frame
                // arrives (an established subscriber is idle by design; liveness rides the HEARTBEAT).
                long firstFrameDeadlineNanos =
                        System.nanoTime() + (long) firstFrameDeadlineMs() * 1_000_000L;
                while (alive.get() && running.get()) {
                    EdgeFrame frame = readFrame(in, firstFrameRouted ? 0L : firstFrameDeadlineNanos);
                    if (frame == null) {
                        return; // EOF
                    }
                    if (!firstFrameRouted) {
                        firstFrameRouted = true;
                        // WH-11 disarm: rely on the server->client HEARTBEAT for liveness now; do NOT
                        // read-idle-reap a healthy subscriber that legitimately stays quiet. On a token
                        // connection the first routed frame is the AUTH frame, so this bounds the
                        // pre-auth window (an authenticated peer that then idles is trusted).
                        socket.setSoTimeout(0);
                    }
                    // Token-auth gate: a certificate-less token connection admits ONLY an AUTH frame
                    // until it authenticates (which starts the session lazily). Once authenticated, its
                    // AUTH-family control frames (REFRESH_AUTH, a stray AUTH) are handled here rather than
                    // routed to the session; business frames fall through to routeBusinessFrame.
                    if (authGated) {
                        if (!authState.isAuthenticated()) {
                            admitPreAuth(frame);
                            continue;
                        }
                        if (frame instanceof EdgeFrame.Auth || frame instanceof EdgeFrame.RefreshAuth) {
                            handlePostAuthControl(frame);
                            continue;
                        }
                    }
                    routeBusinessFrame(frame);
                }
            } catch (SocketTimeoutException e) {
                // WH-11: the first-frame deadline elapsed with no (complete) routed frame - a
                // slow-loris. Reaped + counted; the deadline is disarmed after the first frame,
                // so a post-SUBSCRIBE idle subscriber never reaches here.
                metrics.onFirstFrameTimeout();
                close(ErrorCode.PROTOCOL_VIOLATION, "pre-SUBSCRIBE first-frame deadline elapsed");
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
         * Routes a business frame to the session, flipping the outbound wire version on the FIRST such
         * frame: a WATCH_CREATE-first connection stamps 0x02 (the client decodes the server's WATCH_*
         * frames); a 0x03-stamped SUBSCRIBE stamps 0x03 (the ADR-0045 filtered-fan-out confirm); a plain
         * 0x01 SUBSCRIBE stays 0x01 (byte-identical). On a token connection the first business frame
         * arrives after the AUTH, so the flip is decoupled from the pre-auth first-frame deadline.
         */
        private void routeBusinessFrame(EdgeFrame frame) {
            if (!firstBusinessRouted) {
                firstBusinessRouted = true;
                if (frame instanceof EdgeFrame.WatchCreate) {
                    wireVersion = EdgeFrameCodec.EDGE_WIRE_VERSION_V2;
                } else if (frame instanceof EdgeFrame.Subscribe
                        && inboundNegotiatedVersion == EdgeFrameCodec.EDGE_WIRE_VERSION_V3) {
                    wireVersion = EdgeFrameCodec.EDGE_WIRE_VERSION_V3;
                }
            }
            driver.onInboundFrame(frame);
        }

        /**
         * Admits the single pre-auth frame of a token connection: only an AUTH frame is accepted, and a
         * rejected AUTH closes the connection (so a retry costs a fresh handshake, bounding the pre-auth
         * verification cost). On success the session starts lazily, bound to the token principal's id,
         * and the TTL expiry is armed.
         */
        private void admitPreAuth(EdgeFrame frame) {
            if (frame instanceof EdgeFrame.Auth authFrame) {
                Credential credential = authFrame.credential();
                if (!edgeAuth.credentialWithinCaps(credential)) {
                    teardown(ErrorCode.AUTH_FAIL, "auth credential exceeds the permitted size");
                    return;
                }
                AuthResult result = edgeAuth.resolveFrameCredential(credential);
                if (result instanceof AuthResult.Authenticated a) {
                    Principal principal = a.principal();
                    authState = AuthState.authenticated(principal,
                            edgeAuth.tokenCloseDeadlineMillis(a, clock.currentTimeMillis()));
                    startSessionThreads(principal.id(), principal.roles());
                    armExpiry();
                } else {
                    teardown(ErrorCode.AUTH_FAIL, "authentication failed");
                }
            } else {
                teardown(ErrorCode.PROTOCOL_VIOLATION,
                        "expected an AUTH frame before authenticating, got " + frame.type());
            }
        }

        /**
         * Handles an AUTH-family control frame on an already-authenticated token connection: a
         * REFRESH_AUTH re-resolves the credential and re-arms the expiry (CREDENTIAL_EXPIRED on any
         * non-acceptance); a stray AUTH is a PROTOCOL_VIOLATION. The identity is not re-bound in v1.
         */
        private void handlePostAuthControl(EdgeFrame frame) {
            if (frame instanceof EdgeFrame.RefreshAuth refresh) {
                Credential credential = refresh.credential();
                if (!edgeAuth.credentialWithinCaps(credential)) {
                    teardown(ErrorCode.CREDENTIAL_EXPIRED, "refresh credential exceeds the permitted size");
                    return;
                }
                AuthResult result = edgeAuth.resolveFrameCredential(credential);
                if (result instanceof AuthResult.Authenticated a) {
                    if (authState instanceof AuthState.Authenticated bound
                            && !bound.principal().id().equals(a.principal().id())) {
                        // A refresh renews the SAME identity's token; a different identity is anomalous -
                        // fail closed rather than silently extend (the driver's identity is fixed at
                        // first authentication).
                        teardown(ErrorCode.AUTH_FAIL,
                                "refresh credential resolves to a different identity than the "
                                        + "connection is bound to");
                        return;
                    }
                    authState = AuthState.authenticated(a.principal(),
                            edgeAuth.tokenCloseDeadlineMillis(a, clock.currentTimeMillis()));
                    armExpiry();
                } else {
                    teardown(ErrorCode.CREDENTIAL_EXPIRED, "credential refresh rejected");
                }
            } else {
                teardown(ErrorCode.PROTOCOL_VIOLATION,
                        "AUTH received on an already-authenticated connection");
            }
        }

        /**
         * Arms (or re-arms) the token-expiry one-shot from the connection's {@link AuthState} close
         * deadline: a fired task tears the connection down with the on-wire {@code CREDENTIAL_EXPIRED},
         * which closes the socket and unwinds the blocking reader. The delay is
         * {@code max(0, deadline - now)}; a {@link AuthState#NO_EXPIRY} deadline arms nothing.
         */
        private void armExpiry() {
            cancelExpiry();
            if (!(authState instanceof AuthState.Authenticated a) || a.expiresAtMillis() == AuthState.NO_EXPIRY) {
                return;
            }
            long delay = Math.max(0L, a.expiresAtMillis() - clock.currentTimeMillis());
            expiryTask = authExpiryScheduler.schedule(
                    () -> teardown(ErrorCode.CREDENTIAL_EXPIRED, "token credential expired"),
                    delay, TimeUnit.MILLISECONDS);
        }

        /**
         * Arms the mTLS cert-{@code notAfter} expiry one-shot for a cert connection: a fired task tears the
         * connection down {@code CREDENTIAL_EXPIRED} (a reconnect signal - a cert cannot refresh in-band).
         * {@link AuthState#NO_EXPIRY} (enforcement off) arms nothing - byte-identical to Gate 3.
         */
        private void armCertExpiry() {
            if (certCloseDeadlineMillis == AuthState.NO_EXPIRY) {
                return;
            }
            long delay = Math.max(0L, certCloseDeadlineMillis - clock.currentTimeMillis());
            expiryTask = authExpiryScheduler.schedule(
                    () -> teardown(ErrorCode.CREDENTIAL_EXPIRED, EdgeCertGate.CERT_EXPIRED_MESSAGE),
                    delay, TimeUnit.MILLISECONDS);
        }

        private void cancelExpiry() {
            ScheduledFuture<?> t = expiryTask;
            if (t != null) {
                t.cancel(false);
                expiryTask = null;
            }
        }

        /**
         * Reads one frame: length prefix (peekLength-bounded BEFORE allocation), then the body.
         * Returns null on a clean EOF.
         *
         * @param deadlineNanos the ABSOLUTE first-frame deadline ({@link System#nanoTime()} basis), or
         *                      {@code 0} to read unbounded (post-first-frame, disarmed). When non-zero
         *                      every underlying read is bounded to the REMAINING budget so a byte-per-
         *                      window slow-loris cannot reset the deadline (WH-11 / C3).
         */
        private EdgeFrame readFrame(DataInputStream in, long deadlineNanos) throws IOException {
            byte[] header4 = new byte[4];
            if (deadlineNanos != 0L) {
                // Absolute-deadline first-frame read: bound each underlying read to the remaining budget.
                try {
                    if (!readBounded(in, header4, 0, 4, deadlineNanos)) {
                        return null; // clean EOF before any byte
                    }
                } catch (EOFException eof) {
                    return null; // partial length prefix then EOF: treat as a clean stream end
                }
            } else {
                // Disarmed steady-state read (byte-identical to the pre-C3 path): block indefinitely.
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException eof) {
                    return null; // clean stream end
                }
                header4[0] = (byte) (length >>> 24);
                header4[1] = (byte) (length >>> 16);
                header4[2] = (byte) (length >>> 8);
                header4[3] = (byte) length;
            }
            // Bounds-check the declared length BEFORE allocating (peekLength-bounded).
            int total = EdgeFrameCodec.peekLength(header4); // throws CodecException if out of range
            // Pre-auth ceiling: while a token connection is UNAUTHENTICATED, a hostile peer cannot
            // induce even a mid-size allocation before proving identity - the declared length is capped
            // at the small pre-auth ceiling here, BEFORE the frame buffer is sized (mirrors the Netty
            // decoder). Dormant on the non-token path (byte-identical: authGated == false).
            if (authGated && !authState.isAuthenticated() && total > edgeAuth.preAuthMaxFrameBytes()) {
                throw new EdgeFrameCodec.CodecException(ErrorCode.FRAME_TOO_LARGE,
                        "pre-auth frame length " + total + " exceeds the pre-auth ceiling "
                                + edgeAuth.preAuthMaxFrameBytes());
            }
            byte[] frameBytes = new byte[total];
            frameBytes[0] = header4[0];
            frameBytes[1] = header4[1];
            frameBytes[2] = header4[2];
            frameBytes[3] = header4[3];
            if (deadlineNanos != 0L) {
                readBounded(in, frameBytes, 4, total - 4, deadlineNanos); // truncation => EOFException
            } else {
                in.readFully(frameBytes, 4, total - 4);
            }
            if (EdgeFrameCodec.peekVersion(frameBytes) == EdgeFrameCodec.EDGE_WIRE_VERSION_V4) {
                // Auth-phase frame (AU3-3): version-pin EXEMPT. Decode under 0x04 (only AUTH/REFRESH_AUTH
                // are legal there) and NEVER read or set the business-version pin, so it may interleave on
                // a connection pinned to any business version - symmetric to the Netty decoder. A
                // bit-flipped version byte still fails the CRC (checked first) -> FRAME_CORRUPT.
                return EdgeFrameCodec.decode(frameBytes, EdgeFrameCodec.EDGE_WIRE_VERSION_V4);
            }
            if (inboundNegotiatedVersion == 0) {
                // First business frame: accept 0x01/0x02/0x03 (CRC-validated), then PIN to its stamp.
                EdgeFrame frame = EdgeFrameCodec.decode(frameBytes);
                inboundNegotiatedVersion = EdgeFrameCodec.peekVersion(frameBytes); // known 0x01/0x02/0x03
                return frame;
            }
            // Pinned: a business frame stamped with the OTHER accepted version -> BAD_WIRE_VERSION.
            return EdgeFrameCodec.decode(frameBytes, inboundNegotiatedVersion);
        }

        /**
         * Reads exactly {@code len} bytes into {@code dst[off..off+len)}, re-arming the socket read
         * timeout to the REMAINING first-frame budget before EVERY underlying read (see
         * {@link #armReadBudget}). This makes the WH-11 deadline ABSOLUTE: a slow-loris that dribbles
         * >=1 byte per window cannot reset it, because the budget shrinks monotonically and
         * {@code armReadBudget} throws {@link SocketTimeoutException} once it is exhausted (C3).
         *
         * @return {@code true} if all {@code len} bytes were read; {@code false} iff a clean EOF occurs
         *         BEFORE any byte is read (an idle peer that closed). A partial-then-EOF throws
         *         {@link EOFException} (a truncated frame).
         */
        private boolean readBounded(DataInputStream in, byte[] dst, int off, int len, long deadlineNanos)
                throws IOException {
            int read = 0;
            while (read < len) {
                armReadBudget(deadlineNanos);
                int n = in.read(dst, off + read, len - read);
                if (n < 0) {
                    if (read == 0) {
                        return false; // clean EOF before any byte
                    }
                    throw new EOFException(
                            "truncated first frame: read " + read + " of " + len + " bytes before EOF");
                }
                read += n;
            }
            return true;
        }

        /**
         * Shrinks the socket read timeout to the REMAINING first-frame budget so the WH-11 deadline is
         * absolute (C3). A non-positive remaining budget throws {@link SocketTimeoutException}, reaping
         * the connection. The remaining budget is rounded UP to at least 1 ms so we never set
         * {@code soTimeout(0)} (= infinite) from a sub-millisecond remainder.
         */
        private void armReadBudget(long deadlineNanos) throws IOException {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                throw new SocketTimeoutException("pre-SUBSCRIBE first-frame deadline elapsed");
            }
            long remainingMs = (remainingNanos + 999_999L) / 1_000_000L; // round up, never 0 = infinite
            socket.setSoTimeout((int) Math.min(remainingMs, Integer.MAX_VALUE));
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
            cancelExpiry();
            FanOutConnectionDriver d = driver;
            FanOutSessionCore s = (d != null) ? d.session() : null;
            // Best-effort: try to push a final ERROR_CLOSE before the socket dies, stamping the
            // connection's negotiated version so a 0x02 watch client can decode the bye. A token
            // connection can be torn down BEFORE its session exists (a pre-auth reject / cap / expiry),
            // where d == null; it still deserves the AUTH_FAIL / CREDENTIAL_EXPIRED bye.
            boolean wantBye = (d == null) || (s != null && s.state() != FanOutSessionCore.SessionState.CLOSED);
            if (wantBye) {
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
            if (connectedCounted) {
                // Pairs the onSubscriberConnected the session start counted. A token connection that
                // closed before authenticating never connected, so it must not emit a phantom disconnect.
                metrics.onSubscriberDisconnected();
            }
        }

        private void joinQuietly(Thread t) {
            if (t == null) {
                return; // a token connection may close before its session threads exist
            }
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
