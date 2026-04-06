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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * TCP-based Raft transport with optional TLS encryption.
 * <p>
 * Uses persistent TCP connections between nodes, with one virtual thread
 * per connection for reading inbound messages. Outbound connections are
 * created lazily on first send and cached for reuse.
 * <p>
 * Wire format per message on the stream:
 * <pre>
 *   [4 bytes: sender NodeId]
 *   [N bytes: FrameCodec-encoded frame (starts with 4-byte length)]
 * </pre>
 * <p>
 * If TLS is configured (via {@link TlsManager}), all connections use
 * TLSv1.3 via {@link SSLSocket}/{@link SSLServerSocket}. If the
 * TlsManager is null, plaintext sockets are used (testing only).
 * <p>
 * Reconnection on failure is managed through {@link ConnectionManager},
 * which applies exponential backoff between reconnection attempts.
 */
public final class TcpRaftTransport implements RaftTransport, AutoCloseable {

    private final NodeId self;
    private final InetSocketAddress bindAddress;
    private final Map<NodeId, InetSocketAddress> peerAddresses;
    private final TlsManager tlsManager; // nullable for plaintext
    private final Consumer<InboundMessage> inboundHandler;
    private final ConnectionManager connectionManager;

    private final ConcurrentHashMap<NodeId, PeerConnection> outbound = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor;

    private volatile ServerSocket serverSocket;
    private volatile RaftTransport.MessageHandler messageHandler;

    /**
     * An inbound message with the sender's identity and the decoded frame.
     *
     * @param from  the sending node
     * @param frame the decoded wire frame
     */
    public record InboundMessage(NodeId from, FrameCodec.Frame frame) {
        public InboundMessage {
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(frame, "frame must not be null");
        }
    }

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
    public TlsManager tlsManager() {
        return tlsManager;
    }

    /**
     * Returns the actual port the server socket is bound to.
     * Useful when binding to port 0 for tests.
     *
     * @return the local port number
     */
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

        PeerConnection conn = outbound.computeIfAbsent(target, this::createPeerConnection);
        try {
            conn.sendFrame(frame);
        } catch (IOException e) {
            handleSendFailure(target, conn, e);
        }
    }

    @Override
    public void registerHandler(MessageHandler handler) {
        this.messageHandler = handler;
    }

    /**
     * Gracefully shuts down the transport: closes the server socket,
     * all outbound connections, and the virtual thread executor.
     */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        // Close server socket to unblock accept()
        closeQuietly(serverSocket);

        // Close all outbound connections
        for (var entry : outbound.entrySet()) {
            entry.getValue().close();
        }
        outbound.clear();

        executor.shutdownNow();
    }

    // ---- Server accept loop ----

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                executor.submit(() -> handleInboundConnection(clientSocket));
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
                if (frameLength < FrameCodec.HEADER_SIZE + FrameCodec.TRAILER_SIZE
                        || frameLength > FrameCodec.MAX_FRAME_SIZE) {
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

    private void handleSendFailure(NodeId target, PeerConnection conn, IOException e) {
        conn.close();
        outbound.remove(target, conn);
        synchronized (connectionManager) {
            connectionManager.markDisconnected(target);
        }
    }

    private Socket createClientSocket(InetSocketAddress address) throws IOException {
        if (tlsManager != null) {
            SSLContext ctx = tlsManager.currentContext();
            SSLSocketFactory factory = ctx.getSocketFactory();
            // F-0051 fix: use the hostname (not InetAddress) so the JDK keeps
            // the SNI name and performs HTTPS endpoint identification against
            // the hostname supplied here.
            String host = address.getHostString();
            SSLSocket socket = (SSLSocket) factory.createSocket(host, address.getPort());
            TlsConfig tlsConfig = tlsManager.config();
            if (tlsConfig != null) {
                if (!tlsConfig.protocols().isEmpty()) {
                    socket.setEnabledProtocols(tlsConfig.protocols().toArray(String[]::new));
                }
                if (!tlsConfig.ciphers().isEmpty()) {
                    socket.setEnabledCipherSuites(tlsConfig.ciphers().toArray(String[]::new));
                }
            }
            // F-0051 fix: enforce hostname verification on the client side.
            // Without this, any certificate signed by the trust store is
            // accepted regardless of SAN/CN, which defeats peer-pinning.
            SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.startHandshake();
            return socket;
        } else {
            return new Socket(address.getAddress(), address.getPort());
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
     * Manages a single outbound TCP connection to a peer, with lazy
     * connection establishment and thread-safe sending via a lock.
     */
    private final class PeerConnection {
        private final NodeId target;
        private final InetSocketAddress address;
        private final ReentrantLock sendLock = new ReentrantLock();
        private volatile Socket socket;
        private volatile DataOutputStream out;

        PeerConnection(NodeId target, InetSocketAddress address) {
            this.target = target;
            this.address = address;
        }

        void sendFrame(FrameCodec.Frame frame) throws IOException {
            byte[] encoded = FrameCodec.encode(
                    frame.messageType(), frame.groupId(), frame.term(), frame.payload());

            sendLock.lock();
            try {
                ensureConnected();
                // Write sender NodeId
                out.writeInt(self.id());
                // Write the encoded frame (includes its own length prefix)
                out.write(encoded);
                out.flush();
            } finally {
                sendLock.unlock();
            }
        }

        private void ensureConnected() throws IOException {
            if (socket == null || socket.isClosed()) {
                close();
                socket = createClientSocket(address);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                out = new DataOutputStream(socket.getOutputStream());

                // Start a reader thread for this outbound connection too,
                // in case the peer sends messages back on the same socket
                Socket readerSocket = socket;
                executor.submit(() -> handleInboundConnection(readerSocket));

                synchronized (connectionManager) {
                    connectionManager.markConnected(target);
                }
            }
        }

        void close() {
            sendLock.lock();
            try {
                closeQuietly(out);
                closeQuietly(socket);
                out = null;
                socket = null;
            } finally {
                sendLock.unlock();
            }
        }
    }
}
