package io.configd.distribution.wire;

import java.util.zip.CRC32C;

/**
 * A minimal append-plus-patch byte sink for <b>single-pass</b> {@link EdgeFrameCodec} encoding
 * into a reused or pooled buffer - the seam that lets one wire-format implementation
 * ({@link EdgeFrameCodec#encodeInto}) serve both transports without the status-quo intermediate
 * arrays (the {@code List<byte[]>}, per-notification {@code ByteBuffer}, and payload-then-out
 * double array the head-to-head Surface 3 measured at 69 KB/op for a batch-64 NOTIFY).
 *
 * <p>Two implementations realise the same byte layout:
 * <ul>
 *   <li>{@link HeapFrameSink} - a growable heap {@code byte[]} (this module, kept Netty-free):
 *       the JDK fan-out path, the golden/property/fuzz tests, and the reused-buffer floor bench;</li>
 *   <li>a Netty {@code ByteBuf}-backed sink (in {@code configd-server}): the in-pipeline pooled
 *       encoder, so the production Netty fan-out server reaches the message-building floor with no
 *       intermediate heap arrays.</li>
 * </ul>
 *
 * <p><b>Big-endian, matching {@code ByteBuffer}'s default</b> (and Netty's {@code ByteBuf}
 * default), so the bytes are identical to the status-quo {@code ByteBuffer}-based codec. The CRC
 * trailer is fed from {@link #crc32cInto} so the codec owns one (reused) {@link CRC32C} and the
 * sink merely exposes its written region - no intermediate array for the checksum.
 */
public interface FrameSink {

    /** The current write position (the codec captures the frame start before writing). */
    int writerIndex();

    /** Appends one byte (the low 8 bits of {@code b}). */
    void writeByte(int b);

    /** Appends a 4-byte big-endian int. */
    void writeInt(int v);

    /** Appends an 8-byte big-endian long. */
    void writeLong(long v);

    /** Appends {@code src} in full. */
    void writeBytes(byte[] src);

    /**
     * Overwrites the 4-byte big-endian int at an already-written absolute {@code index} - used to
     * back-patch the frame's length prefix once the payload length is known (single pass).
     */
    void setInt(int index, int v);

    /**
     * Feeds the already-written region {@code [start, start + length)} to {@code crc} (the codec
     * owns the reused checksum). Equivalent to {@code crc.update(bytes, start, length)} but without
     * exposing or copying the backing storage.
     */
    void crc32cInto(CRC32C crc, int start, int length);
}
