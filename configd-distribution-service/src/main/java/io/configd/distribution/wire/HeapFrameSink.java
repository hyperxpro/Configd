package io.configd.distribution.wire;

import java.util.Arrays;
import java.util.zip.CRC32C;

/**
 * A growable heap {@code byte[]} {@link FrameSink} — the JDK-side / test-side backend for the
 * single-pass {@link EdgeFrameCodec#encodeInto}. Big-endian, matching {@code ByteBuffer}'s default.
 *
 * <p><b>Reuse is the point.</b> Constructed once and {@link #reset()} between frames, the backing
 * array grows only to a connection's high-water frame and is then reused, so the steady-state
 * per-frame allocation of the framing itself is ~0 — what remains is exactly the codec-internal
 * message-building floor ({@code CommandCodec.encodeBatch} + signature/nonce clones). That is the
 * 25,520 B/op (batch 64) the head-to-head proved is transport-independent. A fresh sink per call
 * (as {@link EdgeFrameCodec#encode(EdgeFrame)} uses for the cold/test path) is also supported.
 *
 * <p>Not thread-safe: one sink per writer (the JDK fan-out writer thread reuses one per connection).
 */
public final class HeapFrameSink implements FrameSink {

    private byte[] buf;
    private int writerIndex;

    /** Creates a sink with the given initial capacity (a floor of 16 bytes is enforced). */
    public HeapFrameSink(int initialCapacity) {
        this.buf = new byte[Math.max(16, initialCapacity)];
    }

    /** Resets the write position to 0 so the backing array is reused for the next frame. */
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

    /** Returns an exact-size copy of the written bytes {@code [0, writerIndex)}. */
    public byte[] toByteArray() {
        return Arrays.copyOf(buf, writerIndex);
    }
}
