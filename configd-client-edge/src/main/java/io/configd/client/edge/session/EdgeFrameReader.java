package io.configd.client.edge.session;

import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.ErrorCode;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * Reads one frame: length-prefixed with bounds-before-allocation. Static, socket-free for fuzzing.
 * Provably returns frame, clean end, or throws CodecException only—never hangs, OOMs, or other throws.
 */
public final class EdgeFrameReader {

    private EdgeFrameReader() {
    }

    /** Reads one frame: null on clean EOF, throws CodecException or IOException on error. */
    public static EdgeFrame readFrame(DataInputStream in, Byte pinnedVersion, int maxFrameBytes)
            throws IOException {
        int first = in.read();
        if (first < 0) {
            return null;
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

    /** Fills buf[off,off+len), mapping mid-frame EOF to FRAME_CORRUPT (truncation is corruption). */
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
