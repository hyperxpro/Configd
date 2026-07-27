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


public final class FanOutServer implements FanOutEndpoint {

    private static final Logger LOG = Logger.getLogger(FanOutServer.class.getName());

    
    static final int HANDSHAKE_TIMEOUT_MS = 2_000;

    
    static final int BYE_WRITE_TIMEOUT_MS = 2_000;

    
    public static final String FIRST_FRAME_DEADLINE_PROP = "configd.edge.firstFrameDeadlineMs";

    
    public static final int DEFAULT_FIRST_FRAME_DEADLINE_MS = 10_000;

    
    public static int firstFrameDeadlineMs() {
        return Integer.getInteger(FIRST_FRAME_DEADLINE_PROP, DEFAULT_FIRST_FRAME_DEADLINE_MS);
    }

    
    public static final int DEFAULT_TRANSPORT_QUEUE_FRAMES = 64;

    
    public static final int DEFAULT_MAX_SESSIONS = 1_024;

    
    private static final ShardResolver SINGLE_SHARD = t -> new int[]{0};

    private final InetSocketAddress bindAddress;
    private final TlsManager tlsManager;
    
    private final Map<Integer, CommitNotificationSource> shardSources;
    
    private final Map<Integer, ReplaySource> shardReplaySources;
    
    private final int[] allGids;
    
    private final ShardResolver shardResolver;
    
    private final long topologyEpoch;
    private final FanOutConfig config;
    private final int transportQueueFrames;
    private final int maxSessions;
    private final SlowConsumerGovernor governor;
    private final RegistryFanOutSessionMetrics metrics;
    private final Clock clock;

    
    private final WatchAuthorizer authorizer;

    
    private final EdgeAuthConfig edgeAuth;

    
    private final EdgeCertGate certGate;

    
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

    
    public SlowConsumerGovernor governor() {
        return governor;
    }

    
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

    
    public int localPort() {
        ServerSocket ss = serverSocket;
        return (ss != null) ? ss.getLocalPort() : -1;
    }

    
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

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                // Admission bound BEFORE the handshake: beyond maxSessions the socket is closed
                // immediately - half-open handshakes count, so they cannot exhaust fds/threads.
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
                if (chain != null && edgeAuth.mtlsConfigured()) {
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
                    // Certificate-less, OR a cert on a token-only edge (mtls not in the chain): do NOT
                    // auto-authenticate the presented cert - the identity is established by the AUTH frame
                    // the reader awaits.
                    edgeIdentity = null;
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

    // Mirrors TcpRaftTransport.createServerSocket.
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

    
    private final class Connection implements TransportSink {

        private final Socket socket;
        
        private final String edgeIdentity;
        
        private final boolean authGated;
        
        private final long certCloseDeadlineMillis;
        private final ArrayBlockingQueue<byte[]> outbound;
        private final AtomicBoolean alive = new AtomicBoolean(true);

        
        private volatile FanOutConnectionDriver driver;

        
        private Thread writer;
        private Thread sessionThread;

        
        private volatile boolean connectedCounted;

        
        private AuthState authState = AuthState.UNAUTHENTICATED;

        
        private boolean firstBusinessRouted;

        
        private volatile ScheduledFuture<?> expiryTask;

        
        private volatile byte wireVersion = EdgeFrameCodec.EDGE_WIRE_VERSION;

        
        private byte inboundNegotiatedVersion;

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

        // Reader thread: decode only. Routing is the driver's; it never touches the session directly.
        private void readerLoop() {
            try {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                // ABSOLUTE pre-SUBSCRIBE first-frame deadline. A per-read soTimeout is evadable by a
                // slow-loris that dribbles >=1 byte per window - each partial read resets the timer, so
                // the deadline never fires; an absolute wall-clock budget is not evadable. readFrame
                // re-arms the socket timeout to the REMAINING budget before EVERY underlying read until
                // the first frame is routed (see readBounded), and aborts with a SocketTimeoutException
                // once the budget is exhausted regardless of dribbles. A peer that completed mTLS (or
                // connected in plaintext) then stalls - sending nothing OR dribbling forever - is reaped,
                // instead of parking a reader thread + FD + cumulator until the OS reaps it. The deadline
                // is DISARMED (deadline 0, soTimeout 0) once the first BUSINESS frame is routed - not the
                // first frame - so a token connection stays bounded across BOTH its pre-auth AUTH window
                // and (after re-arm on auth) its pre-SUBSCRIBE window; an established subscriber is idle
                // by design and its liveness rides the HEARTBEAT.
                long deadlineNanos =
                        System.nanoTime() + (long) firstFrameDeadlineMs() * 1_000_000L;
                while (alive.get() && running.get()) {
                    EdgeFrame frame = readFrame(in, deadlineNanos);
                    if (frame == null) {
                        return; // EOF
                    }
                    // Token-auth gate: a certificate-less token connection admits ONLY an AUTH frame
                    // until it authenticates (which starts the session lazily). Once authenticated, its
                    // AUTH-family control frames (REFRESH_AUTH, a stray AUTH) are handled here rather than
                    // routed to the session; business frames fall through to routeBusinessFrame.
                    if (authGated) {
                        if (!authState.isAuthenticated()) {
                            admitPreAuth(frame);
                            if (authState.isAuthenticated()) {
                                // RE-ARM a fresh pre-SUBSCRIBE window on authentication so an
                                // authed-but-never-SUBSCRIBE token connection is reaped, rather than
                                // parking a reader thread + FD with soTimeout 0 (parity with the Netty
                                // transport, which re-arms its first-frame deadline in startSession).
                                deadlineNanos =
                                        System.nanoTime() + (long) firstFrameDeadlineMs() * 1_000_000L;
                            }
                            continue;
                        }
                        if (frame instanceof EdgeFrame.Auth || frame instanceof EdgeFrame.RefreshAuth) {
                            handlePostAuthControl(frame);
                            continue;
                        }
                    }
                    // A business frame (SUBSCRIBE / WATCH_CREATE): DISARM the pre-SUBSCRIBE deadline
                    // before routing - an established subscriber is idle by design and relies on the
                    // server->client HEARTBEAT for liveness. On a non-token connection this is the first
                    // routed frame (byte-identical to the prior first-frame disarm); on a token
                    // connection it fires only after AUTH + the re-armed pre-SUBSCRIBE window.
                    if (!firstBusinessRouted) {
                        socket.setSoTimeout(0);
                        deadlineNanos = 0L;
                    }
                    routeBusinessFrame(frame);
                }
            } catch (SocketTimeoutException e) {
                // The first-frame deadline elapsed with no (complete) routed frame - a slow-loris.
                // Reaped + counted; the deadline is disarmed after the first frame, so a
                // post-SUBSCRIBE idle subscriber never reaches here.
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

        
        private void admitPreAuth(EdgeFrame frame) {
            if (frame instanceof EdgeFrame.Auth authFrame) {
                Credential credential = authFrame.credential();
                if (!edgeAuth.credentialWithinCaps(credential)) {
                    teardown(ErrorCode.AUTH_FAIL, "auth credential exceeds the permitted size");
                    return;
                }
                AuthResult result = edgeAuth.resolveFrameCredential(credential);
                credential.wipeSecret(); // zero any Basic password char[] once verification is done
                if (result instanceof AuthResult.Authenticated a) {
                    Principal principal = a.principal();
                    authState = AuthState.authenticated(principal,
                            edgeAuth.tokenCloseDeadlineMillis(a, clock.currentTimeMillis()));
                    startSessionThreads(principal.id(), principal.roles());
                    armExpiry();
                } else if (result instanceof AuthResult.Unavailable) {
                    // The authenticator's backend was unreachable (a down IdP locking out legitimate
                    // clients) - a distinct metric series from a bad credential. Wire stays AUTH_FAIL.
                    teardown(ErrorCode.AUTH_FAIL, "authentication temporarily unavailable", "AUTH_UNAVAILABLE");
                } else {
                    teardown(ErrorCode.AUTH_FAIL, "authentication failed");
                }
            } else {
                teardown(ErrorCode.PROTOCOL_VIOLATION,
                        "expected an AUTH frame before authenticating, got " + frame.type());
            }
        }

        
        private void handlePostAuthControl(EdgeFrame frame) {
            if (frame instanceof EdgeFrame.RefreshAuth refresh) {
                Credential credential = refresh.credential();
                if (!edgeAuth.credentialWithinCaps(credential)) {
                    teardown(ErrorCode.CREDENTIAL_EXPIRED, "refresh credential exceeds the permitted size");
                    return;
                }
                AuthResult result = edgeAuth.resolveFrameCredential(credential);
                credential.wipeSecret(); // zero any Basic password char[] once verification is done
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

        
        private void armExpiry() {
            cancelExpiry();
            if (!(authState instanceof AuthState.Authenticated a) || a.expiresAtMillis() == AuthState.NO_EXPIRY) {
                return;
            }
            long delay = Math.max(0L, a.expiresAtMillis() - clock.currentTimeMillis());
            expiryTask = authExpiryScheduler.schedule(
                    () -> tearDownOffScheduler(ErrorCode.CREDENTIAL_EXPIRED, "token credential expired"),
                    delay, TimeUnit.MILLISECONDS);
        }

        
        private void armCertExpiry() {
            if (certCloseDeadlineMillis == AuthState.NO_EXPIRY) {
                return;
            }
            long delay = Math.max(0L, certCloseDeadlineMillis - clock.currentTimeMillis());
            expiryTask = authExpiryScheduler.schedule(
                    () -> tearDownOffScheduler(ErrorCode.CREDENTIAL_EXPIRED, EdgeCertGate.CERT_EXPIRED_MESSAGE),
                    delay, TimeUnit.MILLISECONDS);
        }

        
        private void tearDownOffScheduler(ErrorCode code, String message) {
            Thread.ofVirtual().name("edge-expiry-teardown").start(() -> teardown(code, message));
        }

        private void cancelExpiry() {
            ScheduledFuture<?> t = expiryTask;
            if (t != null) {
                t.cancel(false);
                expiryTask = null;
            }
        }

        
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
                // Disarmed steady-state read: block indefinitely.
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
                // Auth-phase frame: version-pin EXEMPT. Decode under 0x04 (only AUTH/REFRESH_AUTH
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

        
        private void armReadBudget(long deadlineNanos) throws IOException {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                throw new SocketTimeoutException("pre-SUBSCRIBE first-frame deadline elapsed");
            }
            long remainingMs = (remainingNanos + 999_999L) / 1_000_000L; // round up, never 0 = infinite
            socket.setSoTimeout((int) Math.min(remainingMs, Integer.MAX_VALUE));
        }

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

        // TransportSink is the only boundary here: the socket lives in this class, not in the session.
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

        private void teardown(ErrorCode code, String message) {
            teardown(code, message, code.name());
        }

        
        private void teardown(ErrorCode code, String message, String metricReason) {
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
                // The bye-write is a BLOCKING socket write and Java has no write-timeout: a stuck peer
                // (full send buffer, not draining) would block this thread. An expiry-fired teardown is
                // dispatched OFF the shared single-threaded authExpiryScheduler (see tearDownOffScheduler)
                // so the scheduler stays free, and a watchdog force-closes the socket after a bounded
                // deadline, which unblocks a stuck write (it throws, caught below) so the FD is reclaimed
                // rather than parked until the OS reaps it. No scheduler (mTLS-only, no expiry configured)
                // => no watchdog, byte-identical to before.
                ScheduledFuture<?> writeWatchdog = (authExpiryScheduler != null)
                        ? authExpiryScheduler.schedule(
                                () -> closeQuietly(socket), BYE_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        : null;
                try {
                    byte[] bye = EdgeFrameCodec.encode(new EdgeFrame.ErrorClose(code, message), wireVersion);
                    socket.getOutputStream().write(bye);
                    socket.getOutputStream().flush();
                } catch (Exception ignored) {
                    // the peer may already be gone; teardown proceeds regardless
                } finally {
                    if (writeWatchdog != null) {
                        writeWatchdog.cancel(false);
                    }
                }
            }
            outbound.offer(POISON); // unblock the writer's take()
            closeQuietly(socket);
            metrics.onSessionClosed(metricReason);
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

    
    private static final byte[] POISON = new byte[0];
}
