package io.configd.transport;

import java.util.Objects;

/**
 * The transport-agnostic consensus wire protocol + framing discipline + admission policy shared by
 * every {@link RaftTransportEndpoint} implementation (M4 / DR-N16). Extracted from
 * {@link TcpRaftTransport} so the JDK adapter and the Netty adapter encode/parse/admit
 * <b>identically by construction</b> — not by hopeful re-implementation. The wire byte-identity is
 * pinned by a golden-bytes test; this class holds the single source of truth.
 *
 * <h2>On-wire format</h2>
 * <pre>
 *   [4 bytes: sender NodeId, big-endian]
 *   [N bytes: FrameCodec-encoded frame (itself starting with a 4-byte length)]
 * </pre>
 * The 4-byte sender id prefixes each {@link FrameCodec} frame so the receiver knows the origin
 * without trusting payload-internal identity. (Folding it into the same output buffer — rather than
 * the historical second {@code byte[]} of {@link TcpRaftTransport#send} — is the DR-5 #2 / DR-N17
 * single-allocation fix the Netty in-pipeline encoder realises.)
 *
 * <h2>Frame discipline (the adversary-facing rules both transports enforce)</h2>
 * <ul>
 *   <li><b>Length bounds before allocation</b> — {@link #isValidFrameLength(int)} bounds the
 *       declared frame length to {@code [HEADER_SIZE + TRAILER_SIZE, MAX_FRAME_SIZE]} so a peer
 *       cannot induce a giant allocation by lying in the 4-byte length prefix.</li>
 *   <li><b>Decode-first</b> — {@link FrameCodec#decode} verifies the CRC32C (and version/type)
 *       <em>before</em> any field is trusted; a decode failure means the stream is desynced and the
 *       connection must drop. A <em>handler</em> throw, by contrast, does not desync the framing
 *       layer, so the reader keeps going.</li>
 * </ul>
 *
 * <h2>Policy constants</h2>
 * The bounded connect/handshake timeouts (RR-002), the per-peer outbound queue capacity
 * (drop-on-overflow), and the slowloris admission cap + inbound read deadline (F-S7-FUZZ-1) are
 * defined here so both transports apply the same numbers (and honour the same {@code -D} overrides).
 */
public final class RaftWireProtocol {

    private RaftWireProtocol() {
        // utility class
    }

    /** Size of the big-endian sender-id prefix that precedes each {@link FrameCodec} frame. */
    public static final int SENDER_ID_SIZE = 4;

    /**
     * Bounded TCP connect timeout (ms). Replaces a timeout-less connect whose only bound was the
     * ~127 s OS SYN timeout (RR-002). Short because consensus traffic is intra-cluster (low RTT) and
     * a stuck connect simply re-attempts on the next tick.
     */
    public static final int CONNECT_TIMEOUT_MS = 1_000;

    /**
     * Bounded TLS handshake timeout (ms). Without it a peer that completes the TCP connect but stalls
     * mid-handshake would park the connector indefinitely (RR-002).
     */
    public static final int HANDSHAKE_TIMEOUT_MS = 2_000;

    /**
     * Per-peer bounded outbound queue capacity (frames). When full, the oldest undeliverable frames
     * are dropped (counted) rather than blocking the caller. Raft re-sends on the next heartbeat, so
     * a bounded queue with drop-on-overflow is correct and far cheaper than unbounded buffering.
     */
    public static final int OUTBOUND_QUEUE_CAPACITY = 1_024;

    /** System property: inbound idle/slow-read deadline (ms). Default 15 s. */
    public static final String INBOUND_READ_TIMEOUT_PROP = "configd.raft.inboundReadTimeoutMs";

    /** System property: max concurrent accepted inbound connections before refuse. Default 1024. */
    public static final String MAX_INBOUND_CONNECTIONS_PROP = "configd.raft.maxInboundConnections";

    /**
     * F-S7-FUZZ-1 idle/slow-read deadline (ms) on accepted inbound sockets. A stalled/slow-drip peer
     * then fails its read with a timeout instead of parking a reader and holding the FD forever.
     * Default 15 s ≫ the ≤50 ms steady-state heartbeat interval, so a healthy peer never trips it;
     * tunable via {@value #INBOUND_READ_TIMEOUT_PROP} (the slowloris test sets a short value).
     */
    public static int inboundReadTimeoutMs() {
        return Integer.getInteger(INBOUND_READ_TIMEOUT_PROP, 15_000);
    }

    /**
     * F-S7-FUZZ-1 max concurrent accepted inbound connections before the listener refuses (closes +
     * counts) a new connection — bounds FD/thread blast radius. Tunable via
     * {@value #MAX_INBOUND_CONNECTIONS_PROP} (default 1024).
     */
    public static int maxInboundConnections() {
        return Integer.getInteger(MAX_INBOUND_CONNECTIONS_PROP, 1_024);
    }

    /**
     * Encodes a frame into its on-wire byte sequence: {@code [4B BE senderId] || FrameCodec frame}.
     * The single source of truth for the JDK adapter's outbound bytes; the Netty in-pipeline encoder
     * writes the byte-identical sequence directly into a pooled {@code ByteBuf} (DR-N17), pinned by
     * the golden-bytes test.
     *
     * @param senderId this node's id (big-endian prefix)
     * @param frame    the frame to encode
     * @return the wire bytes (sender id + encoded frame)
     */
    public static byte[] encodeWire(int senderId, FrameCodec.Frame frame) {
        Objects.requireNonNull(frame, "frame must not be null");
        byte[] encoded = FrameCodec.encode(
                frame.messageType(), frame.groupId(), frame.term(), frame.payload());
        byte[] wire = new byte[SENDER_ID_SIZE + encoded.length];
        wire[0] = (byte) (senderId >>> 24);
        wire[1] = (byte) (senderId >>> 16);
        wire[2] = (byte) (senderId >>> 8);
        wire[3] = (byte) senderId;
        System.arraycopy(encoded, 0, wire, SENDER_ID_SIZE, encoded.length);
        return wire;
    }

    /**
     * Whether a declared frame length is within the {@code [HEADER_SIZE + TRAILER_SIZE,
     * MAX_FRAME_SIZE]} range {@link FrameCodec#decode} accepts. Both transports bounds-check the
     * 4-byte length prefix with this predicate <em>before</em> allocating the frame buffer; each then
     * fails in its own idiom (the JDK reader throws {@code IOException} to drop the connection, the
     * Netty decoder closes the channel).
     *
     * @param frameLength the length read from the 4-byte frame prefix
     * @return true if the length is in-bounds and safe to allocate
     */
    public static boolean isValidFrameLength(int frameLength) {
        return frameLength >= FrameCodec.HEADER_SIZE + FrameCodec.TRAILER_SIZE
                && frameLength <= FrameCodec.MAX_FRAME_SIZE;
    }
}
