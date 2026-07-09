package io.configd.client.edge.session;

import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.ErrorCode;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * Reads one length-prefixed {@link EdgeFrame} from a stream with the same bounds-before-allocation discipline
 * the server's {@code ByteToEdgeFrameDecoder} uses — the client mirror of the hostile-peer framing hardening.
 * It is a static, socket-free routine so it can be fuzzed directly (feed a {@code ByteArrayInputStream}) and
 * proven to only ever return a frame, signal a clean end, or throw a mapped
 * {@link EdgeFrameCodec.CodecException} — never hang, OOM, or throw anything else.
 *
 * <p>Order (§06 F3): read the 4-byte length prefix → {@link EdgeFrameCodec#peekLength} bounds it to
 * {@code [10, MAX_EDGE_FRAME_SIZE]} <b>and</b> the client's (possibly tighter) {@code maxFrameBytes}
 * <b>before</b> allocating → read exactly {@code length} bytes → {@link EdgeFrameCodec#decode} (CRC before
 * version/type, strict-end). A lying length prefix cannot induce a giant allocation; a truncated frame is
 * corruption, not a silent partial read.
 */
public final class EdgeFrameReader {

    private EdgeFrameReader() {
    }

    /**
     * Reads one complete frame.
     *
     * @param in            the stream (SO_TIMEOUT governs how long a partial read blocks)
     * @param pinnedVersion the connection's pinned business version, or {@code null} to accept any business
     *                      version (the pre-pin phase — Gate 1 never pins, so it passes {@code null})
     * @param maxFrameBytes the client's frame ceiling (≤ the codec's 2 MiB cap)
     * @return the decoded frame, or {@code null} on a clean end-of-stream at a frame boundary
     * @throws EdgeFrameCodec.CodecException on any malformed / oversize / truncated / bad-CRC frame
     * @throws IOException                   on a genuine transport error (reset, read timeout)
     */
    public static EdgeFrame readFrame(DataInputStream in, Byte pinnedVersion, int maxFrameBytes)
            throws IOException {
        int first = in.read();
        if (first < 0) {
            return null; // clean EOF at a frame boundary
        }
        byte[] header = new byte[4];
        header[0] = (byte) first;
        readFullyOrCorrupt(in, header, 1, 3, "frame length prefix");

        int length = EdgeFrameCodec.peekLength(header); // bounds to [10, 2 MiB] BEFORE allocation
        if (length > maxFrameBytes) {
            throw new EdgeFrameCodec.CodecException(ErrorCode.FRAME_TOO_LARGE,
                    "declared frame length " + length + " exceeds the client frame cap " + maxFrameBytes);
        }

        byte[] frame = new byte[length];
        System.arraycopy(header, 0, frame, 0, 4);
        readFullyOrCorrupt(in, frame, 4, length - 4, "frame body");

        return pinnedVersion == null
                ? EdgeFrameCodec.decode(frame)
                : EdgeFrameCodec.decode(frame, pinnedVersion);
    }

    /**
     * Fills {@code buf[off, off+len)} exactly, mapping a mid-frame end-of-stream to {@code FRAME_CORRUPT}: a
     * well-behaved server never truncates a frame, so a partial read is corruption (or a hostile drip), not a
     * clean close. A read timeout / reset propagates as its own {@link IOException} (not an {@code EOFException}).
     */
    private static void readFullyOrCorrupt(DataInputStream in, byte[] buf, int off, int len, String what)
            throws IOException {
        try {
            in.readFully(buf, off, len);
        } catch (EOFException truncated) {
            throw new EdgeFrameCodec.CodecException(ErrorCode.FRAME_CORRUPT,
                    "truncated " + what + " (stream ended mid-frame)");
        }
    }
}
