package io.configd.transport;

import io.configd.common.Clock;
import io.configd.common.NodeId;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * TCP-based Raft transport with optional TLS encryption.
 * <p>
 * Uses persistent TCP connections between nodes, with one virtual thread
 * per connection for reading inbound messages. Outbound connections are
 * established <em>asynchronously</em> on a dedicated connector thread and
 * cached for reuse.
 *
 * <h2>Non-blocking send contract</h2>
 * Per the {@link RaftTransport} contract, {@link #send} is <strong>non-blocking</strong>.
 * Connect/handshake run only on {@link #connectExecutor} (a single dedicated thread),
 * never on the caller. Connect uses a bounded
 * {@link Socket#connect(java.net.SocketAddress, int) connect(addr, timeout)}
 * ({@value #CONNECT_TIMEOUT_MS} ms); the TLS handshake is bounded with
 * {@code setSoTimeout(}{@value #HANDSHAKE_TIMEOUT_MS}{@code )} for its duration
 * (then cleared so steady-state reads block normally). {@link #send} only enqueues:
 * each peer has a bounded outbound queue drained by a dedicated <em>writer</em> task;
 * the caller does a non-blocking {@code offer}. Frames are <b>dropped</b> only when
 * the per-peer queue is full or the connection is closing, incrementing
 * {@link #framesDropped()}. Raft tolerates message loss: the leader re-sends
 * AppendEntries on the next heartbeat, so a bounded queue with drop-on-overflow is
 * correct and far cheaper than unbounded buffering.
 *
 * <h2>Threading</h2>
 * The caller (tick) thread only ever touches transport-internal state:
 * {@link #outbound} (a {@link ConcurrentHashMap}), the per-peer bounded queue
 * (a {@link BlockingQueue}, thread-safe), the per-peer {@code connectInFlight}
 * flag (an {@link AtomicBoolean}), and {@link #connectionManager} (under its own
 * monitor). It never touches a {@link Socket}, a stream, or any RaftNode state.
 * Established sockets are handed to the writer/reader tasks; the only state shared
 * between the tick thread and those tasks is the queue (thread-safe by construction)
 * and the volatile {@code socket}/{@code out} fields, documented on {@link PeerConnection}.
 *
 * <h2>Wire format</h2>
 * <pre>
 *   [4 bytes: sender NodeId]
 *   [N bytes: FrameCodec-encoded frame (starts with 4-byte length)]
 * </pre>
 * <p>
 * If TLS is configured (via {@link TlsManager}), all connections use
 * TLSv1.3 via {@link SSLSocket}/{@link SSLServerSocket}. If the
 * TlsManager is null, plaintext sockets are used (testing only).
 */
public final class TcpRaftTransport implements RaftTransportEndpoint {

    private static final Logger LOG = Logger.getLogger(TcpRaftTransport.class.getName());

    /**
     * Bounded TCP connect timeout (ms). Short because consensus traffic is intra-cluster
     * (low RTT) and a stuck connect simply causes a re-attempt on the next tick. Owned by
     * {@link RaftWireProtocol} so the JDK and Netty transports apply the identical bound.
     */
    static final int CONNECT_TIMEOUT_MS = RaftWireProtocol.CONNECT_TIMEOUT_MS;

    /**
     * Bounded TLS handshake timeout (ms), applied via {@code setSoTimeout}
     * for the duration of {@code startHandshake()} and then cleared. Without
     * it a peer that completes the TCP connect but stalls mid-handshake would
     * park the connector thread indefinitely. Shared via {@link RaftWireProtocol}.
     */
    static final int HANDSHAKE_TIMEOUT_MS = RaftWireProtocol.HANDSHAKE_TIMEOUT_MS;

    /**
     * Per-peer bounded outbound queue capacity (frames). When full, the oldest
     * undeliverable frames are dropped (counted) rather than blocking the
     * caller. Sized to absorb a short replication burst without unbounded growth.
     * Shared via {@link RaftWireProtocol}.
     */
    static final int OUTBOUND_QUEUE_CAPACITY = RaftWireProtocol.OUTBOUND_QUEUE_CAPACITY;

    private final NodeId self;
    private final InetSocketAddress bindAddress;
    private final Map<NodeId, InetSocketAddress> peerAddresses;
    private final TlsManager tlsManager; // nullable for plaintext
    private final Consumer<InboundMessage> inboundHandler;
    private final ConnectionManager connectionManager;

    /** Cert-identity &harr; NodeId binding policy (WH-08/09). Default {@link PeerIdentityPolicy#unenforced()}. */
    private final PeerIdentityPolicy peerIdentityPolicy;
    /** Security-event sink (peer-identity rejections). Default {@link RaftTransportMetrics#NOOP}. */
    private final RaftTransportMetrics transportMetrics;
    /** Guards the one-time "peer-identity verification unconfigured" warning (unenforced-but-TLS posture). */
    private final AtomicBoolean unconfiguredWarningEmitted = new AtomicBoolean(false);

    private final ConcurrentHashMap<NodeId, PeerConnection> outbound = new ConcurrentHashMap<>();
    /** Inbound sockets accepted by {@link #acceptLoop}; closed on {@link #close}. */
    private final java.util.Set<Socket> acceptedSockets =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Reader tasks + writer tasks (virtual threads). */
    private final ExecutorService executor;
    /**
     * The single thread that performs blocking connect/handshake. Isolating it
     * here guarantees a black-holed peer can only ever park THIS thread, never
     * the caller (tick) thread. Scheduled so reconnects can honour
     * {@link ConnectionManager} backoff via a delay instead of busy-waiting.
     * Daemon so it never blocks JVM shutdown.
     */
    private final ScheduledExecutorService connectExecutor;

    /** Frames dropped because no connection was established or the per-peer queue was full. */
    private final AtomicLong framesDropped = new AtomicLong();

    /** Inbound connections refused because the accepted live-set hit {@link #maxInboundConnections}. */
    private final AtomicLong inboundConnectionsRefused = new AtomicLong();

    /**
     * Idle/slow-read deadline (ms) on accepted inbound sockets. A stalled/slow-drip peer then fails
     * its {@code readInt()}/{@code readFully()} with {@link java.net.SocketTimeoutException} instead
     * of parking a reader vthread and holding the FD forever. Default 15 s is well above the
     * steady-state heartbeat interval, so a healthy peer never trips it; tunable via
     * {@code -Dconfigd.raft.inboundReadTimeoutMs} (the slowloris test sets a short value).
     */
    private final int inboundReadTimeoutMs = RaftWireProtocol.inboundReadTimeoutMs();

    /**
     * Max concurrent accepted inbound connections before the accept loop refuses (closes and counts)
     * a new socket - bounds FD/vthread blast radius. Tunable via
     * {@code -Dconfigd.raft.maxInboundConnections} (default 1024).
     */
    private final int maxInboundConnections = RaftWireProtocol.maxInboundConnections();

    private volatile ServerSocket serverSocket;
    private volatile RaftTransport.MessageHandler messageHandler;

    /**
     * Creates a new TCP Raft transport.
     *
     * @param self           this node's identity
     * @param bindAddress    the address to listen on for inbound connections
     * @param peerAddresses  map of peer NodeIds to their listen addresses
     * @param tlsManager     TLS manager for encrypted connections, or null for plaintext
     * @param inboundHandler callback invoked when a message arrives (may be null if using registerHandler)
     */
    public TcpRaftTransport(
            NodeId self,
            InetSocketAddress bindAddress,
            Map<NodeId, InetSocketAddress> peerAddresses,
            TlsManager tlsManager,
            Consumer<InboundMessage> inboundHandler
    ) {
        // Legacy 5-arg constructor: peer-identity binding unenforced, no metrics sink. Byte- and
        // behaviour-identical to the pre-WH-08/09 transport (the enforcement path is dormant until an
        // allow-list policy is supplied via the fuller constructor).
        this(self, bindAddress, peerAddresses, tlsManager, inboundHandler,
                PeerIdentityPolicy.unenforced(), RaftTransportMetrics.NOOP);
    }

    /**
     * Creates a TCP Raft transport with an explicit peer-identity binding policy and metrics sink
     * (WH-08/09). When {@code peerIdentityPolicy} is {@linkplain PeerIdentityPolicy#enforced()
     * enforced}, an accepted peer's TLS cert identity is verified against the allow-list and each
     * frame's {@code senderId} must match the connection's resolved {@link NodeId}; otherwise the
     * transport keeps its CA-chain-only behavior (with a one-time warning when TLS is on).
     *
     * @param peerIdentityPolicy cert-identity&harr;NodeId binding policy (never null)
     * @param transportMetrics   security-event sink (never null; pass {@link RaftTransportMetrics#NOOP})
     */
    public TcpRaftTransport(
            NodeId self,
            InetSocketAddress bindAddress,
            Map<NodeId, InetSocketAddress> peerAddresses,
            TlsManager tlsManager,
            Consumer<InboundMessage> inboundHandler,
            PeerIdentityPolicy peerIdentityPolicy,
            RaftTransportMetrics transportMetrics
    ) {
        this.self = Objects.requireNonNull(self, "self must not be null");
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress must not be null");
        this.peerAddresses = Map.copyOf(Objects.requireNonNull(peerAddresses, "peerAddresses must not be null"));
        this.tlsManager = tlsManager;
        this.inboundHandler = inboundHandler;
        this.peerIdentityPolicy = Objects.requireNonNull(peerIdentityPolicy, "peerIdentityPolicy must not be null");
        this.transportMetrics = Objects.requireNonNull(transportMetrics, "transportMetrics must not be null");
        this.connectionManager = new ConnectionManager(Clock.system());
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.connectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "configd-transport-connector");
            t.setDaemon(true);
            return t;
        });

        for (NodeId peer : peerAddresses.keySet()) {
            connectionManager.addPeer(peer);
        }
    }

    /**
     * Starts the transport: opens the server socket and begins accepting
     * inbound connections on a virtual thread.
     *
     * @throws IOException if the server socket cannot be bound
     */
    @Override
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Transport already started");
        }
        // Fail closed BEFORE binding: an enforced allow-list without mTLS cannot bind identity to a
        // certificate, so it would fail OPEN (every frame unauthenticated). Refuse to start rather than
        // silently downgrade. After this, enforced() implies mTLS on both transports (parity).
        requirePeerIdentityTransportSecurity();

        serverSocket = createServerSocket();
        serverSocket.bind(bindAddress);

        warnIfPeerIdentityUnconfigured();
        executor.submit(this::acceptLoop);
    }

    /**
     * Refuses to start when a peer-identity allow-list is {@linkplain PeerIdentityPolicy#enforced()
     * enforced} but the transport is plaintext (no {@link TlsManager}) - an enforced allow-list without
     * mTLS is a misconfiguration, never a silent downgrade. Resets {@code running} so the failed start
     * does not leave the transport half-open.
     */
    private void requirePeerIdentityTransportSecurity() {
        if (peerIdentityPolicy.enforced() && tlsManager == null) {
            running.set(false);
            throw new IllegalStateException(
                    "Raft peer-identity allow-list is configured but the transport is PLAINTEXT (no "
                            + "TlsManager); enforced identity binding requires mTLS. Refusing to start.");
        }
    }

    /**
     * Emits a loud one-time warning when the transport runs mTLS but no peer-identity allow-list is
     * configured (WH-08/09 enforce-when-configured, warn-when-not). In this posture a cert-valid peer
     * can still forge another node's {@code senderId}; only the CA-chain is checked. No warning for
     * plaintext (test/single-node) or when a policy is enforced.
     */
    private void warnIfPeerIdentityUnconfigured() {
        if (tlsManager != null && !peerIdentityPolicy.enforced()
                && unconfiguredWarningEmitted.compareAndSet(false, true)) {
            System.err.println("WARNING: Raft peer-identity verification is UNCONFIGURED ("
                    + PeerIdentityPolicy.ALLOWED_NODES_PROP + " unset); a cert-valid peer can forge "
                    + "another node's senderId. Configure an allow-list to enforce identity binding.");
        }
    }

    /**
     * Returns the {@link TlsManager} wiring, or {@code null} for plaintext
     * (test-only) mode.
     * <p>
     * Exposed so operational code and regression tests can assert that the
     * Raft transport actually holds a TLS manager when {@code --tls-*}
     * command-line flags are supplied.
     *
     * @return the TlsManager, or null if plaintext
     */
    @Override
    public TlsManager tlsManager() {
        return tlsManager;
    }

    /**
     * Returns the number of outbound frames dropped because no connection was
     * established (or the per-peer queue was full) at send time. Monotonic.
     *
     * @return total dropped frames since construction
     */
    @Override
    public long framesDropped() {
        return framesDropped.get();
    }

    /**
     * Returns the actual port the server socket is bound to.
     * Useful when binding to port 0 for tests.
     *
     * @return the local port number
     */
    @Override
    public int localPort() {
        ServerSocket ss = serverSocket;
        if (ss == null) {
            throw new IllegalStateException("Transport not started");
        }
        return ss.getLocalPort();
    }

    @Override
    public void send(NodeId target, Object message) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(message, "message must not be null");

        if (!running.get()) {
            return;
        }

        if (!(message instanceof FrameCodec.Frame frame)) {
            throw new IllegalArgumentException(
                    "TcpRaftTransport expects FrameCodec.Frame messages, got: " + message.getClass().getName());
        }

        // This method runs on the caller (tick) thread and MUST NOT block.
        // We encode here (cheap, CPU-bound) then hand off to the per-peer queue;
        // all socket I/O (connect, handshake, write) happens on other threads.
        byte[] wire = encodeWire(frame);
        PeerConnection conn = outbound.computeIfAbsent(target, this::createPeerConnection);
        conn.enqueueOrDrop(wire);
    }

    @Override
    public void registerHandler(MessageHandler handler) {
        this.messageHandler = handler;
    }

    /**
     * Gracefully shuts down the transport: closes the server socket,
     * all outbound connections, and the executors.
     */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        // Close server socket to unblock accept()
        closeQuietly(serverSocket);

        // Close accepted inbound sockets to unblock their readers (a blocking
        // readInt ignores thread interrupts) and signal EOF to the remote peer.
        for (Socket s : acceptedSockets) {
            closeQuietly(s);
        }
        acceptedSockets.clear();

        // Close all outbound connections (wakes blocked writers/readers)
        for (PeerConnection conn : outbound.values()) {
            conn.close();
        }
        outbound.clear();

        connectExecutor.shutdownNow();
        executor.shutdownNow();
    }

    /** Inbound connections refused because the accepted live-set hit {@link #maxInboundConnections}. Monotonic. */
    @Override
    public long inboundConnectionsRefused() {
        return inboundConnectionsRefused.get();
    }

    @Override
    public boolean peerIdentityEnforced() {
        return peerIdentityPolicy.enforced();
    }

    /** Best-effort close of a refused / failed inbound socket (it is being discarded). */
    private static void closeQuietly(Socket s) {
        // s is null for a PeerConnection whose handshake never completed (e.g. a
        // rejected-cert connect, or a concurrent connect that lost the race): close()
        // and the disconnect path call this with a null socket field. Mirror the
        // AutoCloseable overload's null-tolerance - "quietly" must include null.
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                // Cap concurrent inbound connections BEFORE submitting a reader vthread, so a flood
                // of half-open sockets cannot exhaust FDs/vthreads. The accept loop is single-threaded,
                // so size()+add are race-free here (removes, on reader vthreads, only shrink the set -
                // a stale-high size conservatively refuses, never over-admits).
                if (acceptedSockets.size() >= maxInboundConnections) {
                    inboundConnectionsRefused.incrementAndGet();
                    closeQuietly(clientSocket);
                    continue;
                }
                // Idle/slow-read deadline: a stalled peer's readInt/readFully then fails with
                // SocketTimeoutException instead of parking the reader and holding the FD forever.
                // Read-idle (resets per read) so long-lived healthy connections are fine.
                try {
                    clientSocket.setSoTimeout(inboundReadTimeoutMs);
                } catch (SocketException e) {
                    closeQuietly(clientSocket);
                    continue;
                }
                // Track accepted sockets so close() can unblock their reader and
                // close them. A blocking readInt() is NOT interrupted by the
                // executor's shutdownNow (classic socket I/O ignores interrupts);
                // without an explicit close, a peer reconnecting on the same port
                // would never observe EOF and could not re-establish promptly.
                acceptedSockets.add(clientSocket);
                executor.submit(() -> {
                    try {
                        // Server-accepted: resolve + pin the peer's cert identity (dialTarget null).
                        handleInboundConnection(clientSocket, true, null);
                    } finally {
                        acceptedSockets.remove(clientSocket);
                    }
                });
            } catch (IOException e) {
                if (running.get()) {
                    // Log and continue; transient errors shouldn't kill the accept loop
                    System.err.println("Accept error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Reads inbound frames on a connection. WH-08/09 identity binding pins the connection's authorized
     * {@link NodeId} (when an allow-list is enforced) and drops any frame whose {@code senderId} differs:
     * <ul>
     *   <li><b>server-accepted</b> ({@code dialTarget == null}): resolve + authorize the peer's TLS cert
     *       identity on this accepted socket (Layer 1), then pin it.</li>
     *   <li><b>outbound-reverse</b> ({@code dialTarget != null}): a peer may reply on a connection WE
     *       dialed; the far end was hostname-verified on connect, so pin the KNOWN {@code dialTarget}
     *       directly. This closes the reverse-path bypass - a Byzantine peer that accepted our connection
     *       cannot write forged-{@code senderId} frames back on it.</li>
     * </ul>
     * Unenforced or plaintext leaves {@code pinnedIdentity} null and the read loop unchanged (legacy).
     */
    private void handleInboundConnection(Socket socket, boolean serverAccepted, NodeId dialTarget) {
        try (socket) {
            NodeId pinnedIdentity = null;
            if (peerIdentityPolicy.enforced()) {
                if (serverAccepted && socket instanceof SSLSocket ssl) {
                    // Layer 1: resolve + authorize the accepted peer's cert identity BEFORE any frame.
                    try {
                        ssl.startHandshake(); // force the handshake so the peer cert is available now
                    } catch (IOException handshakeFailed) {
                        // A failed/rejected handshake is not an authorized peer; drop (counted).
                        transportMetrics.onPeerIdentityRejected();
                        if (running.get()) {
                            LOG.warning(() -> "Peer-identity handshake failed; dropping connection: "
                                    + handshakeFailed.getMessage());
                        }
                        return;
                    }
                    pinnedIdentity = peerIdentityPolicy.resolve(resolveCertIdentity(ssl));
                    if (pinnedIdentity == null) {
                        transportMetrics.onPeerIdentityRejected();
                        if (running.get()) {
                            LOG.warning("Peer certificate identity is not an authorized node; "
                                    + "dropping connection");
                        }
                        return;
                    }
                } else if (!serverAccepted && dialTarget != null) {
                    // Outbound-reverse: the far end is the target we dialed (hostname-verified on connect).
                    pinnedIdentity = dialTarget;
                }
            }
            DataInputStream in = new DataInputStream(socket.getInputStream());
            while (running.get()) {
                // Read sender NodeId
                int senderId = in.readInt();
                NodeId from = NodeId.of(senderId);

                // Layer 2 (WH-08/09): the self-declared senderId prefix must equal the connection's
                // authenticated identity (cert-resolved on accept, or the dialed target on the reverse
                // path). A cert-valid peer forging another node's id is dropped (desync-equivalent) and
                // counted. When enforced, a MISSING pin is also a DENY (fail closed, mirroring the Netty
                // transport) - enforced() implies mTLS (start() refuses plaintext), so a null pin here is
                // an unexpected unauthenticated frame, never a legitimate one.
                if (peerIdentityPolicy.enforced()
                        && (pinnedIdentity == null || !pinnedIdentity.equals(from))) {
                    transportMetrics.onPeerIdentityRejected();
                    if (running.get()) {
                        NodeId expected = pinnedIdentity;
                        LOG.warning(() -> "Peer senderId " + from + " does not match connection identity "
                                + expected + "; dropping connection");
                    }
                    return;
                }

                // Read frame length (first 4 bytes of FrameCodec frame)
                int frameLength = in.readInt();
                // Shared bounds-before-allocation check: identical to the Netty decoder's predicate,
                // so a lying length prefix is rejected the same way by both transports.
                if (!RaftWireProtocol.isValidFrameLength(frameLength)) {
                    throw new IOException("Invalid frame length: " + frameLength);
                }

                // Read the complete frame (length was already consumed, reconstruct)
                byte[] frameBytes = new byte[frameLength];
                // Put the length back at the start
                frameBytes[0] = (byte) (frameLength >>> 24);
                frameBytes[1] = (byte) (frameLength >>> 16);
                frameBytes[2] = (byte) (frameLength >>> 8);
                frameBytes[3] = (byte) frameLength;
                // Read remaining bytes
                in.readFully(frameBytes, 4, frameLength - 4);

                // Decode FIRST. Decode-side throws (CRC, version, type)
                // mean the stream is desynced and the connection must
                // drop. Handler-side throws are dispatched outside this
                // try so they cannot be misclassified as decode errors.
                FrameCodec.Frame frame;
                try {
                    frame = FrameCodec.decode(frameBytes);
                } catch (FrameCodec.UnsupportedWireVersionException e) {
                    if (running.get()) {
                        System.err.println("Inbound wire-version mismatch (observed=0x"
                                + Integer.toHexString(e.observedVersion())
                                + "); dropping connection: " + e.getMessage());
                    }
                    return;
                } catch (IllegalArgumentException e) {
                    if (running.get()) {
                        System.err.println("Inbound frame decode failure ("
                                + e.getClass().getSimpleName()
                                + "); dropping connection: " + e.getMessage());
                    }
                    return;
                }

                // Dispatch to handler. A handler throw is logged but
                // does NOT desync the stream - keep reading.
                try {
                    if (inboundHandler != null) {
                        inboundHandler.accept(new InboundMessage(from, frame));
                    }
                    MessageHandler handler = messageHandler;
                    if (handler != null) {
                        handler.onMessage(from, frame);
                    }
                } catch (RuntimeException e) {
                    if (running.get()) {
                        System.err.println("Inbound handler error from peer "
                                + from + " for frame " + frame.messageType() + ": "
                                + e.getClass().getName() + ": " + e.getMessage());
                        e.printStackTrace(System.err);
                    }
                    // Continue reading; the framing layer is intact.
                }
            }
        } catch (EOFException e) {
            // Peer closed connection - normal during shutdown
        } catch (java.net.SocketTimeoutException e) {
            // Inbound read idle-deadline tripped: a slow-drip/stalled peer. Drop the connection
            // (try-with-resources closes the socket, releasing the reader vthread and FD).
            // Not a desync: the peer simply failed to make read progress within inboundReadTimeoutMs.
            if (running.get()) {
                System.err.println("Inbound read idle-timeout (" + inboundReadTimeoutMs
                        + "ms slowloris guard); dropping connection");
            }
        } catch (SocketException e) {
            if (running.get()) {
                System.err.println("Inbound connection error: " + e.getMessage());
            }
        } catch (IOException e) {
            if (running.get()) {
                System.err.println("Inbound read error: " + e.getMessage());
            }
        }
    }

    /**
     * The verified peer-certificate Subject DN on an established mTLS socket, or {@code null} if no
     * verifiable peer certificate is present (fail-closed). Mirrors the edge plane's
     * {@code resolveCertIdentity}.
     */
    private static String resolveCertIdentity(SSLSocket ssl) {
        try {
            return ssl.getSession().getPeerPrincipal().getName();
        } catch (Exception e) {
            return null; // no verifiable peer certificate
        }
    }

    private PeerConnection createPeerConnection(NodeId target) {
        InetSocketAddress addr = peerAddresses.get(target);
        if (addr == null) {
            throw new IllegalArgumentException("Unknown peer: " + target);
        }
        return new PeerConnection(target, addr);
    }

    /**
     * Pre-encodes a frame into its on-wire byte sequence (sender id + frame), delegating to the
     * shared {@link RaftWireProtocol#encodeWire} so both transports are byte-identical by
     * construction.
     */
    private byte[] encodeWire(FrameCodec.Frame frame) {
        return RaftWireProtocol.encodeWire(self.id(), frame);
    }

    /**
     * Establishes a client socket to {@code address} with bounded connect and
     * (for TLS) bounded handshake. Runs ONLY on {@link #connectExecutor} - never
     * on the caller (tick) thread.
     */
    private Socket createClientSocket(InetSocketAddress address) throws IOException {
        if (tlsManager != null) {
            SSLContext ctx = tlsManager.currentContext();
            SSLSocketFactory factory = ctx.getSocketFactory();
            // Use the hostname (not InetAddress) so the JDK keeps the SNI name and performs
            // HTTPS endpoint identification against it (hostname verification).
            String host = address.getHostString();
            // Create UNCONNECTED, then connect with a bounded timeout.
            // factory.createSocket(host, port) would connect synchronously with no timeout.
            SSLSocket socket = (SSLSocket) factory.createSocket();
            boolean ok = false;
            try {
                socket.connect(new InetSocketAddress(host, address.getPort()), CONNECT_TIMEOUT_MS);
                TlsConfig tlsConfig = tlsManager.config();
                if (tlsConfig != null) {
                    if (!tlsConfig.protocols().isEmpty()) {
                        socket.setEnabledProtocols(tlsConfig.protocols().toArray(String[]::new));
                    }
                    if (!tlsConfig.ciphers().isEmpty()) {
                        socket.setEnabledCipherSuites(tlsConfig.ciphers().toArray(String[]::new));
                    }
                }
                // Enforce HTTPS endpoint identification: validates the peer's certificate against
                // the hostname, not just the trust anchor.
                SSLParameters params = socket.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(params);
                // Bound the handshake so a peer that connects but stalls mid-handshake cannot
                // park the connector thread forever. Cleared afterwards so steady-state reads
                // use normal (blocking) semantics.
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
        } else {
            // Bounded plaintext connect via no-arg constructor then connect(addr, timeout).
            Socket socket = new Socket();
            boolean ok = false;
            try {
                socket.connect(new InetSocketAddress(address.getAddress(), address.getPort()),
                        CONNECT_TIMEOUT_MS);
                ok = true;
                return socket;
            } finally {
                if (!ok) {
                    closeQuietly(socket);
                }
            }
        }
    }

    private ServerSocket createServerSocket() throws IOException {
        if (tlsManager != null) {
            SSLContext ctx = tlsManager.currentContext();
            SSLServerSocketFactory factory = ctx.getServerSocketFactory();
            SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket();
            TlsConfig tlsConfig = tlsManager.config();
            if (tlsConfig != null) {
                if (tlsConfig.requireClientAuth()) {
                    serverSocket.setNeedClientAuth(true);
                }
                if (!tlsConfig.protocols().isEmpty()) {
                    serverSocket.setEnabledProtocols(tlsConfig.protocols().toArray(String[]::new));
                }
                if (!tlsConfig.ciphers().isEmpty()) {
                    serverSocket.setEnabledCipherSuites(tlsConfig.ciphers().toArray(String[]::new));
                }
            }
            return serverSocket;
        } else {
            return new ServerSocket();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * Manages a single outbound TCP connection to a peer.
     *
     * <h3>Threading</h3>
     * <ul>
     *   <li>The caller (tick) thread only calls {@link #enqueueOrDrop} - a
     *       non-blocking {@code offer} onto {@link #queue} plus, when no
     *       connection exists, scheduling an async connect. It never touches a
     *       socket or stream.</li>
     *   <li>{@link #connectExecutor} runs {@link #connectAndStartWriter}: the
     *       bounded connect/handshake, then publishes {@code socket}/{@code out}
     *       and starts the writer.</li>
     *   <li>A single <em>writer</em> task (on {@link #executor}) drains
     *       {@link #queue} and writes to {@code out}. It is the only writer of a
     *       given stream, so no send lock is needed.</li>
     * </ul>
     * {@code socket}/{@code out} are {@code volatile}: published by the connector,
     * read by the writer and by {@link #enqueueOrDrop} (to decide connected-ness).
     * {@code connectInFlight} (an {@link AtomicBoolean}) ensures at most one
     * in-flight connect attempt per peer at a time.
     */
    private final class PeerConnection {
        private final NodeId target;
        private final InetSocketAddress address;
        private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);
        private final AtomicBoolean connectInFlight = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile Socket socket;
        private volatile DataOutputStream out;

        PeerConnection(NodeId target, InetSocketAddress address) {
            this.target = target;
            this.address = address;
        }

        /**
         * Non-blocking. Called on the caller (tick) thread. If a connection is
         * established, the wire bytes are queued for the writer; otherwise an
         * async connect is scheduled (subject to {@link ConnectionManager}
         * backoff) and the frame is dropped (counted). Never blocks on I/O.
         */
        void enqueueOrDrop(byte[] wire) {
            if (closed.get()) {
                framesDropped.incrementAndGet();
                return;
            }
            // offer() is non-blocking; if the writer is connected it will drain.
            if (!queue.offer(wire)) {
                // Queue full: drop the OLDEST frame to favour fresher Raft state,
                // then retry once. Still bounded and non-blocking.
                queue.poll();
                framesDropped.incrementAndGet();
                if (!queue.offer(wire)) {
                    framesDropped.incrementAndGet();
                }
            }
            if (out == null) {
                // No established connection yet: schedule an async connect. The
                // frame stays queued and the writer delivers it once connected;
                // it is only lost if the queue overflows or the peer stays down
                // long enough for a newer send to evict it (drop-on-overflow).
                scheduleConnect();
            }
        }

        /**
         * Schedules an asynchronous connect on {@link #connectExecutor} if one is
         * not already in flight, honouring {@link ConnectionManager} backoff by
         * delaying the attempt rather than dropping it. Returns immediately - the
         * (possibly long) connect runs only on the connector thread.
         */
        private void scheduleConnect() {
            if (closed.get() || !running.get()) {
                return;
            }
            if (!connectInFlight.compareAndSet(false, true)) {
                return; // a connect is already in flight or scheduled for this peer
            }
            long delayMs;
            synchronized (connectionManager) {
                delayMs = connectionManager.backoffRemainingMs(target);
            }
            try {
                connectExecutor.schedule(this::connectAndStartWriter, delayMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // Executor shutting down; relinquish the in-flight flag.
                connectInFlight.set(false);
            }
        }

        /**
         * Runs ONLY on {@link #connectExecutor}. Performs the bounded
         * connect/handshake, publishes the socket, starts reader + writer, and
         * updates {@link ConnectionManager}. On failure it records backoff and, if
         * frames are still queued, reschedules a (now longer-delayed) reconnect so
         * queued frames are eventually delivered without needing a new send.
         */
        private void connectAndStartWriter() {
            boolean connected = false;
            try {
                if (closed.get() || !running.get()) {
                    return;
                }
                Socket s = createClientSocket(address);
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                DataOutputStream o = new DataOutputStream(s.getOutputStream());

                // Publish before starting tasks so the writer/enqueue see them.
                this.socket = s;
                this.out = o;

                // Reader for any messages the peer sends back on this socket. When
                // the peer closes (EOF/RST), the reader returns; we tear the
                // connection down so a reconnect re-establishes. This is how a
                // dropped peer is detected promptly even if no write has failed yet.
                executor.submit(() -> {
                    try {
                        // Outbound-reverse reader: a peer may reply on this connection WE dialed. Bind its
                        // frames to the KNOWN target (hostname-verified on connect) so a Byzantine peer
                        // cannot write forged-senderId frames back on it (WH-08/09 reverse-path binding).
                        handleInboundConnection(s, false, target);
                    } finally {
                        teardown(s);
                    }
                });
                // Writer drains the queue onto this stream.
                executor.submit(() -> writerLoop(s, o));

                synchronized (connectionManager) {
                    connectionManager.markConnected(target);
                }
                connected = true;
            } catch (IOException e) {
                synchronized (connectionManager) {
                    connectionManager.markDisconnected(target);
                }
                if (running.get() && !closed.get()) {
                    System.err.println("Connect to peer " + target + " failed: " + e.getMessage());
                }
            } finally {
                connectInFlight.set(false);
                // If the connect failed and frames are still waiting, retry after
                // the (now larger) backoff. Released the in-flight flag above so
                // scheduleConnect's CAS succeeds.
                if (!connected && !closed.get() && running.get() && !queue.isEmpty()) {
                    scheduleConnect();
                }
            }
        }

        /**
         * The sole writer of {@code o}. Drains the queue and writes frames. On an
         * I/O error the frame currently in hand is returned to the queue (so a
         * dropped connection does not silently lose the in-flight frame) and the
         * connection is torn down; {@link #teardown} then schedules a reconnect if
         * frames remain, so delivery resumes without waiting for the next send.
         */
        private void writerLoop(Socket s, DataOutputStream o) {
            try {
                while (running.get() && !closed.get() && !s.isClosed()) {
                    byte[] wire = queue.poll(1, TimeUnit.SECONDS);
                    if (wire == null) {
                        continue; // idle; re-check liveness
                    }
                    try {
                        o.write(wire);
                        o.flush();
                    } catch (IOException e) {
                        // Return the unsent frame for re-delivery by the next
                        // connection (bounded; drop if the queue is full).
                        if (!queue.offer(wire)) {
                            framesDropped.incrementAndGet();
                        }
                        throw e;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                if (running.get() && !closed.get()) {
                    System.err.println("Write to peer " + target + " failed: " + e.getMessage());
                }
            } finally {
                teardown(s);
            }
        }

        /**
         * Resets this connection so a subsequent send re-connects. Idempotent and
         * safe to call from both the reader and the writer task (whichever notices
         * the drop first). Only clears the published fields if {@code s} is still
         * the live socket, so it never clobbers a newer connection that a
         * concurrent connect may have published. If frames are still queued, a
         * reconnect is scheduled so delivery resumes without a new send arriving
         * (subject to {@link ConnectionManager} backoff).
         */
        private void teardown(Socket s) {
            boolean wasLive = (this.socket == s);
            if (wasLive) {
                this.out = null;
                this.socket = null;
            }
            closeQuietly(s);
            if (wasLive && !closed.get()) {
                synchronized (connectionManager) {
                    connectionManager.markDisconnected(target);
                }
                if (!queue.isEmpty() && running.get()) {
                    scheduleConnect();
                }
            }
        }

        void close() {
            closed.set(true);
            closeQuietly(out);
            closeQuietly(socket);
            out = null;
            socket = null;
            queue.clear();
        }
    }
}
