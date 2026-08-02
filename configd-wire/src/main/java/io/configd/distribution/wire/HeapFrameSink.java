package io.configd.distribution.wire;

import java.util.Arrays;
import java.util.zip.CRC32C;

/**
 * Growable heap byte[] FrameSink: JDK/test backend for single-pass EdgeFrameCodec.encodeInto.
 * Big-endian (matches ByteBuffer default). Reuse is the point: reset() between frames, backing
 * array grows to high-water and reuses. Steady-state per-frame allocation ~0 (only codec-internal
 * message-building floor remains: CommandCodec.encodeBatch + signature/nonce clones). Not
 * thread-safe: one sink per writer.
 */
public final class HeapFrameSink implements FrameSink {

    private byte[] buf;
    private int writerIndex;

    /** Minimum initial capacity of 16 bytes enforced. */
    public HeapFrameSink(int initialCapacity) {
        this.buf = new byte[Math.max(16, initialCapacity)];
    }

    public void reset() {
        writerIndex = 0;
    }

    @Override
    public int writerIndex() {
        return writerIndex;
    }

    private void ensure(int extra) {
        int need = writerIndex + extra;
        if (need < 0) { // overflow guard (a frame can never legitimately exceed 2 GiB)
            throw new IllegalStateException("frame size overflow");
        }
        if (need > buf.length) {
            int newCap = buf.length;
            while (newCap < need) {
                newCap <<= 1;
                if (newCap < 0) { // grew past Integer.MAX_VALUE
                    newCap = need;
                    break;
                }
            }
            buf = Arrays.copyOf(buf, newCap);
        }
    }

    @Override
    public void writeByte(int b) {
        ensure(1);
        buf[writerIndex++] = (byte) b;
    }

    @Override
    public void writeInt(int v) {
        ensure(4);
        buf[writerIndex++] = (byte) (v >>> 24);
        buf[writerIndex++] = (byte) (v >>> 16);
        buf[writerIndex++] = (byte) (v >>> 8);
        buf[writerIndex++] = (byte) v;
    }

    @Override
    public void writeLong(long v) {
        ensure(8);
        buf[writerIndex++] = (byte) (v >>> 56);
        buf[writerIndex++] = (byte) (v >>> 48);
        buf[writerIndex++] = (byte) (v >>> 40);
        buf[writerIndex++] = (byte) (v >>> 32);
        buf[writerIndex++] = (byte) (v >>> 24);
        buf[writerIndex++] = (byte) (v >>> 16);
        buf[writerIndex++] = (byte) (v >>> 8);
        buf[writerIndex++] = (byte) v;
    }

    @Override
    public void writeBytes(byte[] src) {
        ensure(src.length);
        System.arraycopy(src, 0, buf, writerIndex, src.length);
        writerIndex += src.length;
    }

    @Override
    public void setInt(int index, int v) {
        buf[index] = (byte) (v >>> 24);
        buf[index + 1] = (byte) (v >>> 16);
        buf[index + 2] = (byte) (v >>> 8);
        buf[index + 3] = (byte) v;
    }

    @Override
    public void crc32cInto(CRC32C crc, int start, int length) {
        crc.update(buf, start, length);
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, writerIndex);
    }
}
