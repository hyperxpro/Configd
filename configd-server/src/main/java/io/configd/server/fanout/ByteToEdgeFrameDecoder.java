package io.configd.server.fanout;

import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Inbound: accumulates the length-prefixed edge wire and emits one {@link EdgeFrame} per complete
 * frame, preserving the JDK reader's {@code peekLength} discipline — <b>the declared length is
 * bounds-checked BEFORE the frame buffer is allocated</b> ({@link EdgeFrameCodec#peekLength}), so an
 * adversary cannot induce a giant allocation by lying in the 4-byte prefix. A bad length or a
 * structural decode failure throws {@link EdgeFrameCodec.CodecException}, which the pipeline's
 * {@code exceptionCaught} maps to a teardown with the frame's {@link io.configd.distribution.wire.ErrorCode}
 * — identical to the JDK server's {@code readFrame} → {@code close(e.code(), …)} path.
 *
 * <p><b>Per-connection inbound version pin (W1-3 / W5-11 / §6a).</b> This decoder is per-channel
 * (stateful), so it tracks the connection's negotiated wire version: the FIRST frame is decoded
 * accepting either {@code 0x01} or {@code 0x02} (CRC-validated), then the connection is PINNED to
 * that frame's stamped version; every subsequent frame is decoded under the pin, so a
 * version-mismatched frame mid-connection ({@code 0x02} on a {@code 0x01}-pinned connection or vice
 * versa) fails closed with {@link io.configd.distribution.wire.ErrorCode#BAD_WIRE_VERSION}. A legacy
 * SUBSCRIBE-first connection pins to {@code 0x01} and remains byte-identical to the pre-watch path
 * for all real (single-version) traffic; only a mixed-version adversary is newly rejected.
 *
 * <p>On the server the only inbound frames are tiny SUBSCRIBE / CURSOR_ACK / WATCH_CREATE /
 * WATCH_CANCEL (one per connection / per ack / per watch), so this path is cold; the hot server→edge
 * NOTIFY / WATCH_EVENT path is the {@link EdgeFrameToByteEncoder}.
 */
final class ByteToEdgeFrameDecoder extends ByteToMessageDecoder {

    /**
     * The connection's negotiated inbound wire version, or {@code 0} until the first frame establishes
     * it (a successfully-decoded frame stamps {@code 0x01} or {@code 0x02}, never {@code 0}, so {@code 0}
     * is an unambiguous "not yet negotiated" sentinel). Per-channel state (the decoder is not sharable).
     */
    private byte negotiatedVersion;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) {
            return; // need the 4-byte length prefix first
        }
        // Peek the length without consuming, then bounds-check BEFORE allocating the frame buffer.
        byte[] header4 = new byte[4];
        in.getBytes(in.readerIndex(), header4);
        int total = EdgeFrameCodec.peekLength(header4); // throws CodecException if out of range
        if (in.readableBytes() < total) {
            return; // the full frame has not arrived yet
        }
        byte[] frameBytes = new byte[total];
        in.readBytes(frameBytes);
        if (negotiatedVersion == 0) {
            // First frame: accept either version (CRC-validated), then PIN to its stamped version (W5-11).
            out.add(EdgeFrameCodec.decode(frameBytes));
            negotiatedVersion = EdgeFrameCodec.peekVersion(frameBytes); // known 0x01/0x02 post-decode
        } else {
            // Pinned: a frame stamped with the OTHER accepted version → BAD_WIRE_VERSION (fail closed).
            out.add(EdgeFrameCodec.decode(frameBytes, negotiatedVersion));
        }
    }
}
