package io.configd.client.edge.session;

import io.configd.client.AuthFailedException;
import io.configd.client.Carrier;
import io.configd.client.ConfigdException;
import io.configd.client.ErrorClassifier;
import io.configd.client.HostileServerLimits;
import io.configd.client.ProtocolViolationException;
import io.configd.client.Reaction;
import io.configd.client.ServerAddress;
import io.configd.client.UnavailableException;
import io.configd.client.edge.InboundFrameHandler;
import io.configd.client.tls.ClientTls;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;

import javax.net.ssl.SSLException;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One physical edge connection and its state machine over a blocking {@link Socket}/{@code SSLSocket} with a
 * single owner <b>reader thread</b> — the reference-client counterpart to the server's per-connection Netty
 * pipeline, grown from the {@code EdgeProtocolClient} raw-socket seed. The reader thread is the
 * demultiplexer: {@code read → bounds → decode → dispatch}. Application writes ({@code AUTH}/{@code REFRESH_AUTH}
 * now; {@code SUBSCRIBE}/{@code CURSOR_ACK}/{@code WATCH_*} in later gates) are serialized under a single write
 * lock, so the socket has exactly one reader and one writer and no frame interleaving.
 *
 * <p>A connection advances monotonically {@code CONNECTING → TLS_HANDSHAKE → AUTHENTICATING → AUTHENTICATED →
 * CLOSING → CLOSED} and never re-opens — a reconnect is a fresh {@code EdgeConnection} (§06 F10-1). It is
 * hostile-server-hardened by construction: every inbound frame goes through {@link EdgeFrameReader} (bounds
 * before allocation, CRC before interpret, strict-end via the shared codec); handshake / connect / read-idle
 * deadlines bound every blocking step; a decode failure or terminal frame closes cleanly with a classified
 * exception and never hot-loops, hangs, or OOMs; the untrusted server diagnostic is sanitized before it
 * reaches a log or exception (via {@link ErrorClassifier}).
 */
public final class EdgeConnection {

    private final ServerAddress address;
    private final ClientTls tls; // null = a test-only plaintext connection
    private final HostileServerLimits limits;
    private final InboundFrameHandler handler;
    private final String readerThreadName;

    private final Object writeLock = new Object();
    private final AtomicBoolean terminalDelivered = new AtomicBoolean(false);
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

    private volatile EdgeConnectionState state = EdgeConnectionState.CLOSED;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile DataInputStream in;
    private volatile Thread reader;
    private volatile boolean closing;

    /** The pinned business version for inbound decode; {@code null} until a business frame pins it (Gate 2). */
    private volatile Byte pinnedVersion;
    /** Whether a HEARTBEAT-silence read-idle timeout is fatal; disarmed until streaming begins (Gate 2). */
    private volatile boolean idleDeadlineArmed;

    /** Gate that lets the handler pause reads (reactive backpressure): the reader parks when it has no demand. */
    private final java.util.concurrent.locks.ReentrantLock readGate =
            new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.Condition readable = readGate.newCondition();

    public EdgeConnection(ServerAddress address, ClientTls tls, HostileServerLimits limits,
                          InboundFrameHandler handler, String readerThreadName) {
        this.address = address;
        this.tls = tls;
        this.limits = limits;
        this.handler = handler;
        this.readerThreadName = readerThreadName;
    }

    /**
     * Connects (TCP + TLS handshake) and starts the reader thread. Blocking. On success the connection is in
     * {@link EdgeConnectionState#AUTHENTICATING} (for mTLS the handshake already authenticated; the caller's
     * auth lifecycle advances it to {@link EdgeConnectionState#AUTHENTICATED}).
     *
     * @throws AuthFailedException   if the TLS/mTLS handshake fails (a rejected client cert, or an
     *                               unverifiable server endpoint — F9)
     * @throws UnavailableException  if the TCP connect is refused or times out (a capacity/transport
     *                               condition — retry with backoff, §06 F10-2)
     */
    public void connect() {
        closing = false;
        state = EdgeConnectionState.CONNECTING;
        Socket s;
        try {
            if (tls != null) {
                state = EdgeConnectionState.TLS_HANDSHAKE;
                s = tls.connect(address.host(), address.port(),
                        limits.connectTimeoutMs(), limits.handshakeTimeoutMs());
            } else {
                s = new Socket();
                s.connect(new InetSocketAddress(address.host(), address.port()), limits.connectTimeoutMs());
            }
        } catch (SSLException tlsFailure) {
            // A handshake failure is an authentication failure (our cert rejected, or the server endpoint
            // unverifiable) — re-authenticate / fix the material; not a codec bug (§03 AU3-2 / §06 F9).
            throw new AuthFailedException("edge TLS handshake failed: " + tlsFailure.getMessage(), tlsFailure);
        } catch (IOException connectFailure) {
            // A pre-handshake connect refusal / timeout is a capacity condition (the silent session-cap
            // close, §06 F10-2), retryable — never a protocol error.
            throw new UnavailableException(
                    "edge connect to " + address + " failed: " + connectFailure.getMessage(), connectFailure);
        }
        this.socket = s;
        try {
            s.setSoTimeout(limits.readIdleDeadlineMs());
            this.out = s.getOutputStream();
            // The input stream is taken only AFTER the handshake completed, so no pre-handshake bytes are ever
            // read or interpreted as a frame (libpq CVE-2021-23214/23222 lesson).
            this.in = new DataInputStream(s.getInputStream());
        } catch (IOException io) {
            closeSocketQuietly();
            state = EdgeConnectionState.CLOSED;
            throw new UnavailableException("edge stream setup failed: " + io.getMessage(), io);
        }
        state = EdgeConnectionState.AUTHENTICATING;
        startReader();
    }

    /** Writes one frame under the single write lock. Used by the auth lifecycle (AUTH/REFRESH_AUTH, 0x04). */
    public void send(EdgeFrame frame, byte version) throws IOException {
        byte[] wire = EdgeFrameCodec.encode(frame, version);
        synchronized (writeLock) {
            OutputStream o = out;
            if (o == null || closing) {
                throw new IOException("connection is not writable (state=" + state + ")");
            }
            o.write(wire);
            o.flush();
        }
    }

    /** Marks the connection authenticated (mTLS handshake done, or the AUTH frame written). */
    public void markAuthenticated() {
        if (state == EdgeConnectionState.AUTHENTICATING) {
            state = EdgeConnectionState.AUTHENTICATED;
        }
    }

    /** Pins the inbound business version (Gate 2, once the client sends its first business frame). */
    public void pinVersion(byte version) {
        this.pinnedVersion = version;
    }

    /** Arms the HEARTBEAT-silence read-idle deadline as fatal (Gate 2, once streaming begins). */
    public void armIdleDeadline() {
        this.idleDeadlineArmed = true;
    }

    public EdgeConnectionState state() {
        return state;
    }

    /**
     * Completes when the connection closes: normally on a caller {@link #close()}, or exceptionally with the
     * classified terminal {@link ConfigdException} on a server close / decode failure / transport drop.
     */
    public CompletableFuture<Void> closedFuture() {
        return closed;
    }

    /** True while the reader thread is alive — for leak assertions after {@link #close()}. */
    public boolean readerAlive() {
        Thread r = reader;
        return r != null && r.isAlive();
    }

    /** Tears the connection down with a classified terminal error (the handler's fail-closed / re-bootstrap path). */
    public void fail(ConfigdException error) {
        deliverTerminal(error);
    }

    /** Wakes a reader parked for backpressure (the handler regained demand, or the connection is closing). */
    public void wakeReader() {
        readGate.lock();
        try {
            readable.signalAll();
        } finally {
            readGate.unlock();
        }
    }

    /** Closes the socket and stops the reader thread (bounded join). Idempotent. */
    public void close() {
        closing = true;
        state = EdgeConnectionState.CLOSING;
        closeSocketQuietly(); // unblocks the reader's blocking read promptly
        wakeReader();         // unblock a reader parked for backpressure
        Thread r = reader;
        if (r != null && r != Thread.currentThread()) {
            try {
                r.join(limits.handshakeTimeoutMs());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        state = EdgeConnectionState.CLOSED;
        closed.complete(null); // no-op if a terminal already completed it exceptionally
    }

    // -----------------------------------------------------------------------
    // reader thread
    // -----------------------------------------------------------------------

    private void startReader() {
        Thread t = new Thread(this::runReader, readerThreadName);
        t.setDaemon(true);
        this.reader = t;
        t.start();
    }

    private void runReader() {
        while (!closing) {
            // Reactive backpressure: if the handler has no demand for more frames, park until it regains some
            // (or the connection closes). This stops draining the socket, so the server sees the outbound
            // queue back up and demotes a genuinely slow consumer (§06 F10-3), without us buffering unbounded.
            if (!handler.wantsMoreFrames()) {
                readGate.lock();
                try {
                    if (!closing && !handler.wantsMoreFrames()) {
                        readable.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    readGate.unlock();
                }
                continue;
            }
            EdgeFrame frame;
            try {
                frame = EdgeFrameReader.readFrame(in, pinnedVersion, limits.maxFrameBytes());
            } catch (EdgeFrameCodec.CodecException ce) {
                // A malformed / oversize / truncated / bad-CRC frame: fail clean, never a misparse (§06 F3).
                deliverTerminal(new ProtocolViolationException(
                        "malformed server frame: " + ce.getMessage(), ce.code(), null));
                return;
            } catch (SocketTimeoutException idle) {
                if (idleDeadlineArmed) {
                    deliverTerminal(new UnavailableException(
                            "read-idle deadline elapsed with no server HEARTBEAT"));
                    return;
                }
                continue; // a fan-out subscriber is idle by design until it streams; keep waiting
            } catch (IOException io) {
                if (!closing) {
                    deliverTerminal(new UnavailableException("edge transport error: " + io.getMessage(), io));
                }
                return;
            }
            if (frame == null) { // clean end-of-stream at a frame boundary
                if (!closing) {
                    deliverTerminal(new UnavailableException("edge connection closed by server"));
                }
                return;
            }
            if (!dispatch(frame)) {
                return; // a fatal frame closed the connection
            }
        }
    }

    /** Dispatches one decoded frame; returns {@code false} iff the connection is now fatally closed. */
    private boolean dispatch(EdgeFrame frame) {
        switch (frame) {
            case EdgeFrame.ErrorClose ec -> {
                return react(ErrorClassifier.classify(ec.code(), Carrier.ERROR_CLOSE, ec.message()));
            }
            case EdgeFrame.WatchCanceled wc -> {
                // Carry the watch_id so a multiplexed handler can terminate only this watch (W6-4).
                return reactWatch(wc.watchId(), ErrorClassifier.classify(wc.code(), Carrier.WATCH_CANCELED, wc.message()));
            }
            case EdgeFrame.Heartbeat hb -> handler.onHeartbeat(hb);
            case EdgeFrame.Auth ignored -> {
                deliverTerminal(new ProtocolViolationException(
                        "server sent a client-only AUTH frame"));
                return false;
            }
            case EdgeFrame.RefreshAuth ignored -> {
                deliverTerminal(new ProtocolViolationException(
                        "server sent a client-only REFRESH_AUTH frame"));
                return false;
            }
            // Business / watch frames (SUBSCRIBE_OK, NOTIFY, SNAPSHOT_*, WATCH_*) belong to the later gates;
            // the reader is a real demultiplexer, not a stub — it routes them to the extension seam.
            default -> handler.onFrame(frame);
        }
        return true;
    }

    /** Applies a classified reaction; returns {@code false} iff it was connection-fatal. */
    private boolean react(Reaction reaction) {
        switch (reaction) {
            case Reaction.Fatal f -> {
                deliverTerminal(f.exception());
                return false;
            }
            case Reaction.PerWatch p -> handler.onPerWatch(p.exception());
            case Reaction.CatchUp c -> handler.onCatchUp();
            case Reaction.CancelAck c -> handler.onCancelAck();
        }
        return true;
    }

    /** As {@link #react} but for a {@code WATCH_CANCELED}: routes the per-watch reaction with its {@code watch_id}. */
    private boolean reactWatch(long watchId, Reaction reaction) {
        switch (reaction) {
            case Reaction.Fatal f -> {
                deliverTerminal(f.exception());
                return false;
            }
            case Reaction.PerWatch p -> handler.onPerWatch(watchId, p.exception());
            case Reaction.CatchUp c -> handler.onCatchUp();
            case Reaction.CancelAck c -> handler.onCancelAck(watchId);
        }
        return true;
    }

    /** Delivers a terminal error exactly once: notifies the handler, fails {@link #closed}, closes the socket. */
    private void deliverTerminal(ConfigdException error) {
        if (!terminalDelivered.compareAndSet(false, true)) {
            return;
        }
        state = EdgeConnectionState.CLOSING;
        try {
            handler.onTerminal(error);
        } finally {
            closed.completeExceptionally(error);
            closeSocketQuietly();
            state = EdgeConnectionState.CLOSED;
        }
    }

    private void closeSocketQuietly() {
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }
}
