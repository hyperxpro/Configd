package io.configd.client.edge;

import io.configd.client.edge.session.EdgeFrameReader;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A scriptable loopback edge server for the client tests — the counterpart to the live raw-socket probes run
 * against the server's Netty edge. It accepts connections on an ephemeral loopback port and runs a
 * per-connection {@link Handler} that can behave well (read the {@code AUTH}, confirm with a {@code HEARTBEAT})
 * or hostilely (emit an oversize length prefix, a bad CRC, garbage, a control-character diagnostic, or stall).
 * It encodes/decodes with the real {@link EdgeFrameCodec}, so the client is exercised against the real wire in
 * both directions, not a mock codec.
 *
 * <p>Plaintext by default (fast; the auth/framing logic is transport-agnostic); a {@link #startTls} variant
 * drives the TLS cases. Every {@link Handler} runs on its own thread; the server records every frame it
 * decoded from the client so a test can assert "exactly one {@code AUTH}", "the refreshed token", etc.
 */
final class MockEdgeServer implements AutoCloseable {

    /** A per-connection server script. Throwing/returning ends that connection. */
    interface Handler {
        void handle(Conn conn) throws Exception;
    }

    private final ServerSocket serverSocket;
    private final boolean tls;
    private final Handler handler;
    private final Thread acceptThread;
    private final List<EdgeFrame> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private volatile boolean closed;

    private MockEdgeServer(ServerSocket serverSocket, boolean tls, Handler handler) {
        this.serverSocket = serverSocket;
        this.tls = tls;
        this.handler = handler;
        this.acceptThread = new Thread(this::acceptLoop, "mock-edge-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    static MockEdgeServer startPlaintext(Handler handler) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress("127.0.0.1", 0));
        return new MockEdgeServer(ss, false, handler);
    }

    static MockEdgeServer startTls(SSLContext context, boolean needClientAuth, boolean wantClientAuth,
                                   Handler handler) throws IOException {
        SSLServerSocket ss = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
        ss.bind(new InetSocketAddress("127.0.0.1", 0));
        ss.setEnabledProtocols(new String[]{"TLSv1.3"});
        ss.setEnabledCipherSuites(new String[]{"TLS_AES_256_GCM_SHA384", "TLS_AES_128_GCM_SHA256"});
        ss.setNeedClientAuth(needClientAuth);
        ss.setWantClientAuth(wantClientAuth);
        return new MockEdgeServer(ss, true, handler);
    }

    /** A plain TCP server that accepts then never speaks — a handshake / first-frame slow-loris source. */
    static MockEdgeServer startSilentTcp() throws IOException {
        return startPlaintext(conn -> {
            // Hold the socket open and read nothing back: a TLS client's handshake or a reader's first read
            // stalls, and the client's own deadline must fire.
            conn.parkUntilClosed();
        });
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    /** Every frame decoded from a client, across all connections, in arrival order. */
    List<EdgeFrame> received() {
        return List.copyOf(received);
    }

    long authFrameCount() {
        return received.stream().filter(f -> f instanceof EdgeFrame.Auth).count();
    }

    int connectionCount() {
        return connectionCount.get();
    }

    @Override
    public void close() {
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // best-effort
        }
        acceptThread.interrupt();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                return; // server closed
            }
            int index = connectionCount.incrementAndGet();
            Thread t = new Thread(() -> serve(socket, index), "mock-edge-conn-" + index);
            t.setDaemon(true);
            t.start();
        }
    }

    private void serve(Socket socket, int index) {
        try (Conn conn = new Conn(socket, index, tls)) {
            handler.handle(conn);
        } catch (Exception ignored) {
            // the connection ended (client closed, script threw, or a deliberate hostile close)
        }
    }

    /** The per-connection API a {@link Handler} scripts against. */
    final class Conn implements AutoCloseable {
        private final Socket socket;
        private final DataInputStream in;
        private final OutputStream out;
        final int index;

        Conn(Socket socket, int index, boolean tls) throws IOException {
            this.socket = socket;
            this.index = index;
            if (tls) {
                ((SSLSocket) socket).startHandshake(); // fail here if a required client cert is absent
            }
            this.in = new DataInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        /** Reads and records one client frame (bounds-before-alloc like the real server), or null on EOF. */
        EdgeFrame readFrame() throws IOException {
            EdgeFrame f = EdgeFrameReader.readFrame(in, null, EdgeFrameCodec.MAX_EDGE_FRAME_SIZE);
            if (f != null) {
                received.add(f);
            }
            return f;
        }

        void send(EdgeFrame frame) throws IOException {
            out.write(EdgeFrameCodec.encode(frame));
            out.flush();
        }

        /** Sends a frame stamped with a specific wire version (0x02 for a watch connection, etc.). */
        void send(EdgeFrame frame, byte version) throws IOException {
            out.write(EdgeFrameCodec.encode(frame, version));
            out.flush();
        }

        void sendRaw(byte[] bytes) throws IOException {
            out.write(bytes);
            out.flush();
        }

        /** Drains client frames until the client closes — keeps the connection open while sending nothing. */
        void parkUntilClosed() throws IOException {
            while (readFrame() != null) {
                // keep reading (and recording) until EOF; the server sends nothing back
            }
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }
}
