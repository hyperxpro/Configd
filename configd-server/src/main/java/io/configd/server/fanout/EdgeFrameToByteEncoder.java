package io.configd.server.fanout;

import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;


final class EdgeFrameToByteEncoder extends MessageToByteEncoder<EdgeFrame> {

    
    @FunctionalInterface
    interface WireVersionSupplier {
        byte currentWireVersion();
    }

    private final WireVersionSupplier wireVersion;

    EdgeFrameToByteEncoder(WireVersionSupplier wireVersion) {
        super(true); // preferDirect: encode into a pooled direct buffer (head-to-head best-Netty shape)
        this.wireVersion = wireVersion;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, EdgeFrame frame, ByteBuf out) {
        EdgeFrameCodec.encodeInto(frame, new ByteBufFrameSink(out), wireVersion.currentWireVersion());
    }
}
