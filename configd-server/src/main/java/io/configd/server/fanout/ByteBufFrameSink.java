package io.configd.server.fanout;

import io.configd.distribution.wire.FrameSink;
import io.netty.buffer.ByteBuf;

import java.util.zip.CRC32C;


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
