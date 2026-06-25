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
 * <p>On the server the only inbound frames are tiny SUBSCRIBE / CURSOR_ACK (one per connection /
 * per ack), so this path is cold; the hot server→edge NOTIFY path is the {@link EdgeFrameToByteEncoder}.
 */
final class ByteToEdgeFrameDecoder extends ByteToMessageDecoder {

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
        out.add(EdgeFrameCodec.decode(frameBytes));
    }
}
