package io.configd.netty;

import io.configd.transport.FrameCodec;
import io.configd.transport.RaftWireProtocol;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.util.zip.CRC32C;

/**
 * Allocation-efficient Netty consensus encoder. A {@link MessageToByteEncoder} runs <b>inside the
 * pipeline, on the event-loop thread</b>, which gives ~0 B/op by construction:
 *
 * <ul>
 *   <li>The framework allocates {@code out} and releases it after the write <b>on the same
 *       event-loop thread</b>, so the pooled-{@code ByteBuf} Recycler fast path engages (no per-op
 *       {@code PooledDirectByteBuf} holder churn). Running the same encoder <em>off</em> the event
 *       loop (e.g. in a plain-thread benchmark) hits ~160 B/op from the Recycler slow path.</li>
 *   <li>The buffer is sized to the <b>exact</b> frame up front, so {@code writeBytes} never
 *       reallocates+copies.</li>
 *   <li>CRC32C is computed over {@link ByteBuf#internalNioBuffer} - the buffer's <b>cached</b> NIO
 *       view - so there is no per-message {@code DirectByteBuffer} view allocation.</li>
 * </ul>
 *
 * <p><b>Byte-identical</b> to {@link RaftWireProtocol#encodeWire}:
 * {@code [4B BE senderId] || FrameCodec frame}, with the sender id folded into the <em>same</em>
 * buffer (one allocation instead of two). The {@code ByteBuf} default order is BIG_ENDIAN, matching
 * the JDK {@code ByteBuffer} the codec uses. A golden-bytes test pins the equivalence.
 */
public final class NettyConsensusFrameEncoder extends MessageToByteEncoder<FrameCodec.Frame> {

    private static final ThreadLocal<CRC32C> CRC = ThreadLocal.withInitial(CRC32C::new);

    private final int senderId;

    /**
     * @param senderId this node's id - a per-channel constant written as the 4-byte big-endian prefix
     */
    public NettyConsensusFrameEncoder(int senderId) {
        super(true); // preferDirect: the framework hands us a pooled DIRECT out buffer
        this.senderId = senderId;
    }

    /**
     * Size {@code out} to the exact frame ({@code [senderId] + FrameCodec frame}) so there is no
     * reallocation+copy on write.
     */
    @Override
    protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, FrameCodec.Frame frame, boolean preferDirect) {
        int size = RaftWireProtocol.SENDER_ID_SIZE + FrameCodec.frameSize(frame.payload().length);
        return preferDirect ? ctx.alloc().ioBuffer(size) : ctx.alloc().heapBuffer(size);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, FrameCodec.Frame frame, ByteBuf out) {
        out.writeInt(senderId); // BIG_ENDIAN - identical to RaftWireProtocol.encodeWire's bit-shifts
        int frameStart = out.writerIndex();
        int totalLength = FrameCodec.frameSize(frame.payload().length);
        out.writeInt(totalLength);
        out.writeByte(FrameCodec.WIRE_VERSION);
        out.writeByte((byte) frame.messageType().code());
        out.writeInt(frame.groupId());
        out.writeLong(frame.term());
        out.writeLong(0L); // reserved epoch - MBZ (dormant); byte-identical to FrameCodec.encode
        out.writeBytes(frame.payload());
        CRC32C crc = CRC.get();
        crc.reset();
        // internalNioBuffer = the buffer's CACHED view (no per-message DirectByteBuffer alloc).
        crc.update(out.internalNioBuffer(frameStart, totalLength - FrameCodec.TRAILER_SIZE));
        out.writeInt((int) crc.getValue());
    }
}
