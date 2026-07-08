package io.configd.server.fanout;

import io.configd.common.auth.Credential;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * A minimal TEST-ONLY edge client that speaks edge protocol v1 over a raw socket: it encodes
 * {@code SUBSCRIBE} / {@code CURSOR_ACK} via {@link EdgeFrameCodec} and decodes server frames
 * with the same peekLength discipline the server uses. It is the counterpart to the live edge
 * (C2) for the {@code FanOutServer} integration test - deliberately tiny so the test exercises
 * the real wire, not a mock.
 */
final class EdgeProtocolClient implements AutoCloseable {

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;

    EdgeProtocolClient(Socket connectedSocket) throws IOException {
        this.socket = connectedSocket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    static EdgeProtocolClient connectPlaintext(int port, int soTimeoutMs) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        s.setSoTimeout(soTimeoutMs);
        return new EdgeProtocolClient(s);
    }

    void subscribeFullStore(String edgeId, long resumeCursor) throws IOException {
        send(new EdgeFrame.Subscribe(true, List.of(), resumeCursor, -1L, edgeId));
    }

    void cursorAck(long seq) throws IOException {
        send(new EdgeFrame.CursorAck(seq));
    }

    /** Presents a bearer token in an AUTH frame (0x04, version-pin exempt) - the token-auth handshake. */
    void authenticateBearer(String token) throws IOException {
        sendRaw(EdgeFrameCodec.encode(
                new EdgeFrame.Auth(new Credential.BearerToken(token)), EdgeFrameCodec.EDGE_WIRE_VERSION_V4));
    }

    /** Presents a bearer token in a REFRESH_AUTH frame (0x04) to extend an authenticated session. */
    void refreshBearer(String token) throws IOException {
        sendRaw(EdgeFrameCodec.encode(
                new EdgeFrame.RefreshAuth(new Credential.BearerToken(token)), EdgeFrameCodec.EDGE_WIRE_VERSION_V4));
    }

    void send(EdgeFrame frame) throws IOException {
        byte[] wire = EdgeFrameCodec.encode(frame);
        out.write(wire);
        out.flush();
    }

    /**
     * Reads one server frame (blocks up to the socket SO_TIMEOUT). Returns null on EOF.
     *
     * @throws java.net.SocketTimeoutException if no frame arrives within the SO_TIMEOUT
     */
    EdgeFrame readFrame() throws IOException {
        int length;
        try {
            length = in.readInt();
        } catch (java.io.EOFException eof) {
            return null;
        }
        byte[] header4 = new byte[]{
                (byte) (length >>> 24), (byte) (length >>> 16),
                (byte) (length >>> 8), (byte) length};
        int total = EdgeFrameCodec.peekLength(header4);
        byte[] frameBytes = new byte[total];
        System.arraycopy(header4, 0, frameBytes, 0, 4);
        in.readFully(frameBytes, 4, total - 4);
        return EdgeFrameCodec.decode(frameBytes);
    }

    /** Sends raw bytes (for the corruption / protocol-violation cases). */
    void sendRaw(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
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
