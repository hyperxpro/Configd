package io.configd.server.fanout;

import io.configd.distribution.wire.FrameSink;
import io.netty.buffer.ByteBuf;

import java.util.zip.CRC32C;

/**
 * The Netty {@code ByteBuf} backend for the single-pass {@link io.configd.distribution.wire.EdgeFrameCodec#encodeInto}.
 * Used by {@link EdgeFrameToByteEncoder} to write a frame straight into the pooled,
 * reference-counted pipeline buffer on the event loop - no intermediate heap arrays.
 *
 * <p>{@code ByteBuf}'s default byte order is BIG_ENDIAN, matching the {@code ByteBuffer}-based
 * status-quo codec, so the bytes are identical (proven by the {@code FanOutServerContract} byte
 * checks across transports). The CRC trailer is computed over a zero-copy {@code nioBuffer} view of
 * the written region - no intermediate array.
 */
final class ByteBufFrameSink implements FrameSink {

    private final ByteBuf buf;

    ByteBufFrameSink(ByteBuf buf) {
        this.buf = buf;
    }

    @Override
    public int writerIndex() {
        return buf.writerIndex();
    }

    @Override
    public void writeByte(int b) {
        buf.writeByte(b);
    }

    @Override
    public void writeInt(int v) {
        buf.writeInt(v);
    }

    @Override
    public void writeLong(long v) {
        buf.writeLong(v);
    }

    @Override
    public void writeBytes(byte[] src) {
        buf.writeBytes(src);
    }

    @Override
    public void setInt(int index, int v) {
        buf.setInt(index, v);
    }

    @Override
    public void crc32cInto(CRC32C crc, int start, int length) {
        crc.update(buf.nioBuffer(start, length));
    }
}
