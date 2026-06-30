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

/**
 * TCP-based Raft transport with optional TLS encryption.
 * <p>
 * Uses persistent TCP connections between nodes, with one virtual thread
 * per connection for reading inbound messages. Outbound connections are
 * established <em>asynchronously</em> on a dedicated connector thread and
 * cached for reuse.
 *
 * <h2>RR-002: connect/handshake never blocks the caller (the tick thread)</h2>
 * Per the {@link RaftTransport} contract, {@link #send} is <strong>non-blocking</strong>.
 * Historically it was not: {@code send} reached a timeout-less
 * {@code new Socket(addr, port)} / {@code startHandshake()} on the caller's
 * thread. In this server the caller is the single {@code configd-tick} thread
 * that owns all RaftNode state (R-01); a black-holed peer (SYNs dropped) parked
 * that thread for the full OS SYN timeout (~127 s), freezing tick, inbound,
 * propose and reads — a node-wide freeze under a routine network fault.
 * <p>
 * The fix decouples establishment from sending:
 * <ul>
 *   <li><b>Connect/handshake run only on {@link #connectExecutor}</b> (a single
 *       dedicated thread), never on the caller. Connect uses a bounded
 *       {@link Socket#connect(java.net.SocketAddress, int) connect(addr, timeout)}
 *       ({@value #CONNECT_TIMEOUT_MS} ms); the TLS handshake is bounded with
 *       {@code setSoTimeout(}{@value #HANDSHAKE_TIMEOUT_MS}{@code )} for its
 *       duration (then cleared so steady-state reads block normally).</li>
 *   <li><b>{@link #send} only enqueues.</b> Each peer has a bounded outbound
 *       queue drained by a dedicated <em>writer</em> task; the caller does a
 *       non-blocking {@code offer}. A send to a peer with no established
 *       connection enqueues the frame (bounded) and schedules an async connect
 *       (gated by {@link ConnectionManager} backoff); the writer delivers the
 *       queued frames once connected. Frames are <b>dropped</b> only when the
 *       per-peer queue is full or the connection is closing, incrementing
 *       {@link #framesDropped()}. Raft tolerates message loss: the leader
 *       re-sends AppendEntries on the next heartbeat, so a bounded queue with
 *       drop-on-overflow is correct and far cheaper than unbounded buffering.
 *       (Turning the drop counter into real metrics/alerting is RR-054, owned by
 *       a later session; this change adds the counter without regressing it.)</li>
 * </ul>
 *
 * <h2>Threading / R-01</h2>
 * The caller (tick) thread only ever touches transport-internal state:
 * {@link #outbound} (a {@link ConcurrentHashMap}), the per-peer bounded queue
 * (a {@link BlockingQueue}, thread-safe), the per-peer {@code connectInFlight}
 * flag (an {@link AtomicBoolean}), and {@link #connectionManager} (under its own
 * monitor). It never touches a {@link Socket}, a stream, or any RaftNode state.
 * Established sockets are handed to the writer/reader tasks; the only state
 * shared between the tick thread and those tasks is the queue (thread-safe by
 * construction) and the volatile {@code socket}/{@code out} fields, whose
 * publication/visibility is documented on {@link PeerConnection}.
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

    /**
     * Bounded TCP connect timeout (ms). Replaces the timeout-less
     * {@code new Socket(addr, port)} whose only bound was the ~127 s OS SYN
     * timeout. Kept short because consensus traffic is intra-cluster (low RTT)
     * and a stuck connect simply causes a re-attempt on the next tick.
     * <p>M4 (DR-N16): the value is owned by {@link RaftWireProtocol} so the JDK and
     * Netty transports apply the identical bound.
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

    /** Frames dropped because no connection was established (RR-002/RR-054 metric seam). */
    private final AtomicLong framesDropped = new AtomicLong();

    /**
     * F-S7-FUZZ-1 (slowloris / FD-exhaustion): inbound connections refused because the accepted
     * live-set had already reached {@link #maxInboundConnections}. Metric seam for the negative test.
     */
    private final AtomicLong inboundConnectionsRefused = new AtomicLong();

    /**
     * F-S7-FUZZ-1: idle/slow-read deadline (ms) on accepted inbound sockets. A stalled/slow-drip peer
     * then fails its {@code readInt()}/{@code readFully()} with {@link java.net.SocketTimeoutException}
     * instead of parking a reader vthread and holding the FD forever. Default 15 s ≫ the ≤50 ms
     * steady-state heartbeat interval, so a healthy peer never trips it; tunable via
     * {@code -Dconfigd.raft.inboundReadTimeoutMs} (the test sets a short value).
     */
    private final int inboundReadTimeoutMs = RaftWireProtocol.inboundReadTimeoutMs();

    /**
     * F-S7-FUZZ-1: max concurrent accepted inbound connections before the accept loop refuses (closes
     * + counts) a new socket — bounds FD/vthread blast radius. Mirrors {@code FanOutServer}'s
     * admission cap; tunable via {@code -Dconfigd.raft.maxInboundConnections} (default 1024).
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
        this.self = Objects.requireNonNull(self, "self must not be null");
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress must not be null");
        this.peerAddresses = Map.copyOf(Objects.requireNonNull(peerAddresses, "peerAddresses must not be null"));
        this.tlsManager = tlsManager;
        this.inboundHandler = inboundHandler;
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

        serverSocket = createServerSocket();
        serverSocket.bind(bindAddress);

        executor.submit(this::acceptLoop);
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
     * Exposed for tests and as a metric seam (RR-054).
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

        // RR-002: this method runs on the caller (tick) thread and MUST NOT block.
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

    // ---- Server accept loop ----

    /** Inbound connections refused because the accepted live-set hit {@link #maxInboundConnections}
     *  (F-S7-FUZZ-1 metric / negative-test seam). Monotonic. */
    @Override
    public long inboundConnectionsRefused() {
        return inboundConnectionsRefused.get();
    }

    /** Best-effort close of a refused / failed inbound socket (it is being discarded). */
    private static void closeQuietly(Socket s) {
        // s is null for a PeerConnection whose handshake never completed (e.g. a
        // rejected-cert connect, or a concurrent connect that lost the race): close()
        // and the disconnect path call this with a null socket field. Mirror the
        // AutoCloseable overload's null-tolerance — "quietly" must include null.
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
                // F-S7-FUZZ-1 (slowloris / FD-exhaustion): cap concurrent inbound connections BEFORE
                // submitting a reader vthread, so a flood of half-open sockets cannot exhaust FDs /
                // vthreads. The accept loop is single-threaded, so size()+add are race-free here
                // (removes, on reader vthreads, only shrink the set → a stale-high size conservatively
                // refuses, never over-admits). RR-002-safe: transport vthread, never configd-tick.
                if (acceptedSockets.size() >= maxInboundConnections) {
                    inboundConnectionsRefused.incrementAndGet();
                    closeQuietly(clientSocket);
                    continue;
                }
                // Bounded idle/slow-read deadline: a stalled peer's readInt/readFully then fails with
                // SocketTimeoutException (handled below) instead of parking the reader + holding the
                // FD forever. Read-idle (resets per read) so long-lived healthy connections are fine.
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
                        handleInboundConnection(clientSocket);
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

    private void handleInboundConnection(Socket socket) {
        try (socket) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            while (running.get()) {
                // Read sender NodeId
                int senderId = in.readInt();
                NodeId from = NodeId.of(senderId);

                // Read frame length (first 4 bytes of FrameCodec frame)
                int frameLength = in.readInt();
                // Shared bounds-before-allocation check (RaftWireProtocol / DR-N16): identical to
                // the Netty decoder's predicate, so a lying length prefix is rejected the same way.
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
                // does NOT desync the stream — keep reading.
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
            // F-S7-FUZZ-1: the inbound read idle-deadline tripped — a slow-drip / stalled peer. Drop
            // the connection (the try-with-resources closes the socket → releases the reader vthread +
            // FD). NOT a desync: the peer simply failed to make read progress within inboundReadTimeoutMs.
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

    // ---- Outbound connection management ----

    private PeerConnection createPeerConnection(NodeId target) {
        InetSocketAddress addr = peerAddresses.get(target);
        if (addr == null) {
            throw new IllegalArgumentException("Unknown peer: " + target);
        }
        return new PeerConnection(target, addr);
    }

    /**
     * Pre-encodes a frame into its on-wire byte sequence (sender id + frame), delegating to the
     * shared {@link RaftWireProtocol#encodeWire} so the JDK and Netty transports are byte-identical
     * by construction (M4 / DR-N16).
     */
    private byte[] encodeWire(FrameCodec.Frame frame) {
        return RaftWireProtocol.encodeWire(self.id(), frame);
    }

    /**
     * Establishes a client socket to {@code address} with bounded connect and
     * (for TLS) bounded handshake. Runs ONLY on {@link #connectExecutor} — never
     * on the caller (tick) thread.
     */
    private Socket createClientSocket(InetSocketAddress address) throws IOException {
        if (tlsManager != null) {
            SSLContext ctx = tlsManager.currentContext();
            SSLSocketFactory factory = ctx.getSocketFactory();
            // F-0051: use the hostname (not InetAddress) so the JDK keeps the SNI
            // name and performs HTTPS endpoint identification against it.
            String host = address.getHostString();
            // RR-002: create UNCONNECTED, then connect with a bounded timeout.
            // factory.createSocket(host, port) would connect synchronously with
            // no timeout, reintroducing the freeze.
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
                // F-0051: enforce hostname verification on the client side.
                SSLParameters params = socket.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(params);
                // RR-002: bound the handshake so a peer that connects but stalls
                // mid-handshake cannot park the connector thread forever. Cleared
                // afterwards so steady-state reads use normal (blocking) semantics.
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
            // RR-002: bounded plaintext connect (was: new Socket(addr, port)).
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

    // ---- Peer connection wrapper ----

    /**
     * Manages a single outbound TCP connection to a peer.
     *
     * <h3>Threading</h3>
     * <ul>
     *   <li>The caller (tick) thread only calls {@link #enqueueOrDrop} — a
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
         * delaying the attempt rather than dropping it. Returns immediately — the
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
                        handleInboundConnection(s);
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
