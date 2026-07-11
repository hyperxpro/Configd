package io.configd.server.fanout;

import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.ErrorCode;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.AttributeKey;

import java.util.List;

/**
 * Inbound: accumulates the length-prefixed edge wire and emits one {@link EdgeFrame} per complete
 * frame, preserving the JDK reader's {@code peekLength} discipline - <b>the declared length is
 * bounds-checked before the frame buffer is allocated</b> ({@link EdgeFrameCodec#peekLength}), so an
 * adversary cannot induce a giant allocation by lying in the 4-byte prefix. A bad length or a
 * structural decode failure throws {@link EdgeFrameCodec.CodecException}, which the pipeline's
 * {@code exceptionCaught} maps to a teardown with the frame's {@link io.configd.distribution.wire.ErrorCode}
 * - identical to the JDK server's {@code readFrame} -> {@code close(e.code(), ...)} path.
 *
 * <p><b>Per-connection inbound version pin.</b> This decoder is per-channel
 * (stateful), so it tracks the connection's negotiated wire version: the first frame is decoded
 * accepting {@code 0x01}, {@code 0x02}, or {@code 0x03} (CRC-validated), then the connection is pinned
 * to that frame's stamped version; every subsequent frame is decoded under the pin, so a
 * version-mismatched frame mid-connection (a frame stamped with any other accepted version) fails
 * closed with {@link io.configd.distribution.wire.ErrorCode#BAD_WIRE_VERSION}. A legacy
 * SUBSCRIBE-first connection pins to {@code 0x01} and remains byte-identical to the pre-watch path
 * for all real (single-version) traffic; only a mixed-version adversary is newly rejected.
 *
 * <p>On the server the only inbound frames are tiny SUBSCRIBE / CURSOR_ACK / WATCH_CREATE /
 * WATCH_CANCEL (one per connection / per ack / per watch), so this path is cold; the hot server->edge
 * NOTIFY / WATCH_EVENT path is the {@link EdgeFrameToByteEncoder}.
 */
final class ByteToEdgeFrameDecoder extends ByteToMessageDecoder {

    /**
     * Per-connection auth state (set by the {@code EdgeAuthGateHandler}); read here to decide the pre-auth
     * frame ceiling. Absent / {@code UNAUTHENTICATED} means the tighter {@link #preAuthMaxFrame} cap
     * applies; {@code AUTHENTICATED} lifts it to the steady-state {@code MAX_EDGE_FRAME_SIZE}.
     */
    static final AttributeKey<AuthState> AUTH_STATE = AttributeKey.valueOf("configd.edge.authState");

    /**
     * The connection's negotiated inbound wire version, or {@code 0} until the first business frame
     * establishes it (a successfully-decoded 0x01/0x02/0x03 frame; a 0x04 auth-phase frame is version-pin
     * exempt and never sets this). Per-channel state (the decoder is not sharable).
     */
    private byte negotiatedVersion;

    /** True when token/basic auth is configured: the pre-auth frame ceiling applies until AUTHENTICATED. */
    private final boolean authGated;
    /** The pre-auth declared-length ceiling (bytes) while UNAUTHENTICATED (an AUTH frame is small). */
    private final int preAuthMaxFrame;

    /** The mTLS-only / plaintext (non-token) decoder: byte-identical to before - no pre-auth ceiling. */
    ByteToEdgeFrameDecoder() {
        this(false, EdgeFrameCodec.MAX_EDGE_FRAME_SIZE);
    }

    ByteToEdgeFrameDecoder(boolean authGated, int preAuthMaxFrame) {
        this.authGated = authGated;
        this.preAuthMaxFrame = preAuthMaxFrame;
    }

    /** The negotiated inbound wire version ({@code 0} until the first business frame pins it). */
    byte negotiatedVersion() {
        return negotiatedVersion;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) {
            return; // need the 4-byte length prefix first
        }
        // Peek the length without consuming, then bounds-check before allocating the frame buffer.
        byte[] header4 = new byte[4];
        in.getBytes(in.readerIndex(), header4);
        int total = EdgeFrameCodec.peekLength(header4); // throws CodecException if out of range
        // Minimal-allocation-until-authenticated: while unauthenticated on a token-auth connection, a
        // hostile peer cannot induce even a mid-size allocation before proving identity - the declared
        // length is capped at the small pre-auth ceiling, checked here before the frame buffer is sized.
        if (authGated && total > preAuthMaxFrame) {
            AuthState state = ctx.channel().attr(AUTH_STATE).get();
            if (state == null || !state.isAuthenticated()) {
                throw new EdgeFrameCodec.CodecException(ErrorCode.FRAME_TOO_LARGE,
                        "pre-auth frame length " + total + " exceeds the pre-auth ceiling " + preAuthMaxFrame);
            }
        }
        if (in.readableBytes() < total) {
            return; // the full frame has not arrived yet
        }
        byte[] frameBytes = new byte[total];
        in.readBytes(frameBytes);
        if (EdgeFrameCodec.peekVersion(frameBytes) == EdgeFrameCodec.EDGE_WIRE_VERSION_V4) {
            // Auth-phase frame: version-pin exempt. Decode under 0x04 (only AUTH/REFRESH_AUTH are
            // legal there) and never read or set the business-version pin, so it may interleave on a
            // connection pinned to any business version. A bit-flipped version byte still fails the CRC
            // (checked first inside decode) -> FRAME_CORRUPT.
            out.add(EdgeFrameCodec.decode(frameBytes, EdgeFrameCodec.EDGE_WIRE_VERSION_V4));
        } else if (negotiatedVersion == 0) {
            // First business frame: accept 0x01/0x02/0x03 (CRC-validated), then pin to its stamped version.
            out.add(EdgeFrameCodec.decode(frameBytes));
            negotiatedVersion = EdgeFrameCodec.peekVersion(frameBytes); // known 0x01/0x02/0x03 post-decode
        } else {
            // Pinned: a business frame stamped with the other accepted version -> BAD_WIRE_VERSION.
            out.add(EdgeFrameCodec.decode(frameBytes, negotiatedVersion));
        }
    }
}
