package io.configd.server.fanout;

import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.ErrorCode;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.AttributeKey;

import java.util.List;


final class ByteToEdgeFrameDecoder extends ByteToMessageDecoder {

    
    static final AttributeKey<AuthState> AUTH_STATE = AttributeKey.valueOf("configd.edge.authState");

    
    private byte negotiatedVersion;

    
    private final boolean authGated;
    
    private final int preAuthMaxFrame;

    
    ByteToEdgeFrameDecoder() {
        this(false, EdgeFrameCodec.MAX_EDGE_FRAME_SIZE);
    }

    ByteToEdgeFrameDecoder(boolean authGated, int preAuthMaxFrame) {
        this.authGated = authGated;
        this.preAuthMaxFrame = preAuthMaxFrame;
    }

    
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
            return;
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
