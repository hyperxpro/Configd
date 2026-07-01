package io.configd.server.fanout;

import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Outbound: encodes each {@link EdgeFrame} <b>in the pipeline, on the event loop</b>, straight into
 * the pooled {@code ByteBuf} via the single-pass {@link EdgeFrameCodec#encodeInto}.
 * {@code preferDirect=true} mirrors the head-to-head's best-Netty path (direct pooled buffer), so
 * the production fan-out encode reaches the message-building floor with no intermediate heap arrays.
 *
 * <p><b>Per-connection wire version.</b> The frame is stamped with the connection's
 * negotiated edge wire version, read fresh per frame from the per-connection {@link WireVersionSupplier}
 * (the {@code FanOutConnection.wireVersion} field). A legacy SUBSCRIBE-first connection stays
 * {@code 0x01} (byte-identical to the pre-watch encoder); a {@code WATCH_CREATE}-first watch
 * connection is {@code 0x02}, so the client can decode the server's {@code WATCH_*} frames - and a
 * reused {@code NOTIFY} on a {@code full_chain_verify} watch is stamped {@code 0x02} too. The
 * encoder is stateless across connections; the version lives on the connection.
 *
 * <p>A {@link EdgeFrameCodec.CodecException} (an over-cap frame - a server bug, since the session
 * bounds batch size upstream) propagates as an encoder failure; {@link MessageToByteEncoder}
 * releases the partial buffer and the pipeline's {@code exceptionCaught} tears the connection down.
 */
final class EdgeFrameToByteEncoder extends MessageToByteEncoder<EdgeFrame> {

    /** Supplies the connection's current negotiated outbound edge wire version. */
    @FunctionalInterface
    interface WireVersionSupplier {
        byte currentWireVersion();
    }

    private final WireVersionSupplier wireVersion;

    EdgeFrameToByteEncoder(WireVersionSupplier wireVersion) {
        super(true); // preferDirect: encode into a pooled DIRECT buffer (head-to-head best-Netty shape)
        this.wireVersion = wireVersion;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, EdgeFrame frame, ByteBuf out) {
        EdgeFrameCodec.encodeInto(frame, new ByteBufFrameSink(out), wireVersion.currentWireVersion());
    }
}
