package io.configd.server.fanout;

import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Outbound: encodes each {@link EdgeFrame} <b>in the pipeline, on the event loop</b>, straight into
 * the pooled {@code ByteBuf} via the single-pass {@link EdgeFrameCodec#encodeInto} (DR-N10/DR-N12).
 * {@code preferDirect=true} mirrors the head-to-head's best-Netty path (direct pooled buffer), so
 * the production fan-out encode reaches the message-building floor with no intermediate heap arrays.
 *
 * <p>A {@link EdgeFrameCodec.CodecException} (an over-cap frame — a server bug, since the session
 * bounds batch size upstream) propagates as an encoder failure; {@link MessageToByteEncoder}
 * releases the partial buffer and the pipeline's {@code exceptionCaught} tears the connection down.
 */
final class EdgeFrameToByteEncoder extends MessageToByteEncoder<EdgeFrame> {

    EdgeFrameToByteEncoder() {
        super(true); // preferDirect: encode into a pooled DIRECT buffer (head-to-head best-Netty shape)
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, EdgeFrame frame, ByteBuf out) {
        EdgeFrameCodec.encodeInto(frame, new ByteBufFrameSink(out));
    }
}
