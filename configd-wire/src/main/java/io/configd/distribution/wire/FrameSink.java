package io.configd.distribution.wire;

import java.util.zip.CRC32C;

/**
 * Minimal append-plus-patch sink for single-pass EdgeFrameCodec encoding into reused/pooled
 * buffer. Seam for one wire-format impl (encodeInto) to serve both transports without
 * intermediate arrays. Two implementations: HeapFrameSink (heap byte[], Netty-free) and
 * Netty ByteBuf-backed sink (configd-server). Big-endian (matches ByteBuffer/Netty defaults).
 * CRC fed via crc32cInto (no intermediate array for checksum).
 */
public interface FrameSink {

    /** Current write position (codec captures frame start before writing). */
    int writerIndex();

    void writeByte(int b);

    void writeInt(int v);

    void writeLong(long v);

    void writeBytes(byte[] src);

    /**
     * Overwrite 4-byte big-endian int at absolute index: back-patches frame length
     * prefix once payload length known (single pass).
     */
    void setInt(int index, int v);

    /**
     * Feed region [start, start+length) to crc (codec owns reused checksum).
     * Equivalent to crc.update() without exposing or copying backing storage.
     */
    void crc32cInto(CRC32C crc, int start, int length);
}
