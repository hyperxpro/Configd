package io.configd.netty;

import io.configd.transport.FrameCodec;
import io.configd.transport.RaftWireProtocol;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.util.zip.CRC32C;

/**
 * The idiomatic, allocation-~0 Netty consensus encoder (M4 / DR-N17) — productionized from the
 * head-to-head prototype that the [verdict.md](../../../../../docs/jdk-vs-netty/verdict.md) §Surface 4
 * proved <b>ties the JDK at ~0 B/op</b>. A {@link MessageToByteEncoder} runs <b>inside the pipeline,
 * on the event-loop thread</b>, so it sidesteps the two artifacts that made the naive "Netty loses at
 * 160 B/op" microbench number:
 *
 * <ul>
 *   <li>The framework allocates {@code out} and releases it after the write <b>on the same
 *       event-loop thread</b> → the pooled-{@code ByteBuf} Recycler fast path engages (no per-op
 *       {@code PooledDirectByteBuf} holder churn — the source of the 160).</li>
 *   <li>The buffer is sized to the <b>exact</b> frame up front, so {@code writeBytes} never
 *       reallocates+copies (the 4 KB AppendEntries 256→512→…→8192 grow-churn artifact).</li>
 *   <li>CRC32C is computed over {@link ByteBuf#internalNioBuffer} — the buffer's <b>cached</b> NIO
 *       view — so there is no per-message {@code DirectByteBuffer} view allocation; the
 *       {@link CRC32C} itself is a reused event-loop-confined {@link ThreadLocal}.</li>
 * </ul>
 *
 * <p><b>Byte-identical</b> to {@link RaftWireProtocol#encodeWire} (the JDK adapter's bytes):
 * {@code [4B BE senderId] || FrameCodec frame}, with the sender id folded into the <em>same</em>
 * buffer (one allocation — the DR-5 #2 / DR-N17 fix of the JDK {@code encodeWire}'s second
 * {@code byte[]}). The {@code ByteBuf} default order is BIG_ENDIAN, matching the JDK
 * {@code ByteBuffer} the codec uses. A golden-bytes test pins the equivalence.
 */
public final class NettyConsensusFrameEncoder extends MessageToByteEncoder<FrameCodec.Frame> {

    private static final ThreadLocal<CRC32C> CRC = ThreadLocal.withInitial(CRC32C::new);

    private final int senderId;

    /**
     * @param senderId this node's id — a per-channel constant written as the 4-byte big-endian prefix
     */
    public NettyConsensusFrameEncoder(int senderId) {
        super(true); // preferDirect: the framework hands us a pooled DIRECT out buffer
        this.senderId = senderId;
    }

    /**
     * Size {@code out} to the exact frame ({@code [senderId] + FrameCodec frame}) so there is no
     * reallocation+copy on write — the head-to-head's 4 KB grow-churn artifact, not a real Netty cost.
     */
    @Override
    protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, FrameCodec.Frame frame, boolean preferDirect) {
        int size = RaftWireProtocol.SENDER_ID_SIZE + FrameCodec.frameSize(frame.payload().length);
        return preferDirect ? ctx.alloc().ioBuffer(size) : ctx.alloc().heapBuffer(size);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, FrameCodec.Frame frame, ByteBuf out) {
        out.writeInt(senderId); // BIG_ENDIAN → identical to RaftWireProtocol.encodeWire's bit-shifts
        int frameStart = out.writerIndex();
        int totalLength = FrameCodec.frameSize(frame.payload().length);
        out.writeInt(totalLength);
        out.writeByte(FrameCodec.WIRE_VERSION);
        out.writeByte((byte) frame.messageType().code());
        out.writeInt(frame.groupId());
        out.writeLong(frame.term());
        out.writeLong(0L); // v2/D1 reserved epoch — MBZ (dormant); byte-identical to FrameCodec.encode
        out.writeBytes(frame.payload());
        CRC32C crc = CRC.get();
        crc.reset();
        // internalNioBuffer = the buffer's CACHED view (no per-message DirectByteBuffer alloc).
        crc.update(out.internalNioBuffer(frameStart, totalLength - FrameCodec.TRAILER_SIZE));
        out.writeInt((int) crc.getValue());
    }
}
