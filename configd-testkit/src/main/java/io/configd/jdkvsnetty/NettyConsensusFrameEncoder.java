package io.configd.jdkvsnetty;

import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.util.zip.CRC32C;

/**
 * The <b>genuinely idiomatic</b> Netty consensus encoder — a {@link MessageToByteEncoder} that
 * runs <b>inside the pipeline, on the event-loop thread</b>. This is how a real Netty transport
 * encodes, and it fixes the two harness artifacts the JFR profile exposed in the manual
 * (main-thread alloc + {@code writeAndFlush}) path:
 *
 * <ul>
 *   <li>The framework allocates {@code out} and releases it after the write <b>on the same
 *       event-loop thread</b> → the pooled-{@code ByteBuf} Recycler fast path works (no
 *       cross-thread {@code PooledDirectByteBuf} churn).</li>
 *   <li>CRC32C uses {@link ByteBuf#internalNioBuffer} — the buffer's <b>cached</b> view — instead
 *       of {@code nioBuffer()}, so there is no per-message {@code DirectByteBuffer} view
 *       allocation.</li>
 *   <li>A reused thread-local {@link CRC32C} (event-loop-confined).</li>
 * </ul>
 *
 * Byte-identical to production {@code TcpRaftTransport.encodeWire}: {@code [4B BE senderId] ||
 * FrameCodec frame}.
 */
final class NettyConsensusFrameEncoder extends MessageToByteEncoder<FrameMsg> {

    private static final ThreadLocal<CRC32C> CRC = ThreadLocal.withInitial(CRC32C::new);

    NettyConsensusFrameEncoder() {
        super(true); // preferDirect: framework hands us a pooled DIRECT out buffer
    }

    /**
     * Size the {@code out} buffer to the exact frame up front. Without this, {@code
     * MessageToByteEncoder} allocates a default 256-byte buffer and {@code writeBytes} grows it by
     * reallocation+copy for any larger frame (e.g. a 4 KB AppendEntries: 256→512→…→8192), churning
     * a {@code DirectByteBuffer} per growth — a harness artifact, not a real Netty cost. With the
     * exact size there is no reallocation.
     */
    @Override
    protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, FrameMsg m, boolean preferDirect) {
        int size = 4 + FrameCodec.frameSize(m.payload().length); // [senderId] + frame
        return preferDirect ? ctx.alloc().ioBuffer(size) : ctx.alloc().heapBuffer(size);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, FrameMsg m, ByteBuf out) {
        out.writeInt(m.senderId());
        int frameStart = out.writerIndex();
        int totalLength = FrameCodec.frameSize(m.payload().length);
        out.writeInt(totalLength);
        out.writeByte(FrameCodec.WIRE_VERSION);
        out.writeByte((byte) m.type().code());
        out.writeInt(m.groupId());
        out.writeLong(m.term());
        out.writeLong(0L); // v2/D1 reserved epoch — MBZ (dormant); byte-identical to FrameCodec.encode
        out.writeBytes(m.payload());
        CRC32C crc = CRC.get();
        crc.reset();
        // internalNioBuffer = the buffer's CACHED view (no per-message DirectByteBuffer alloc).
        crc.update(out.internalNioBuffer(frameStart, totalLength - FrameCodec.TRAILER_SIZE));
        out.writeInt((int) crc.getValue());
    }
}
