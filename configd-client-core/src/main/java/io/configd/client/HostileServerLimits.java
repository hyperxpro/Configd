package io.configd.client;

import io.configd.distribution.wire.EdgeFrameCodec;

/**
 * Inbound bounds enforced on server frames: mirror of server's hostile-client hardening. Codec enforces
 * per-frame bounds; this adds policy bounds (deadlines, snapshot caps). All configurable; defaults are safe.
 * readIdleDeadlineMs is NOT armed before first business frame (fan-out subscriber idle by design until subscribe).
 */
public record HostileServerLimits(
        int maxFrameBytes,
        int connectTimeoutMs,
        int handshakeTimeoutMs,
        int readIdleDeadlineMs,
        long maxSnapshotTotalBytes,
        int maxSnapshotChunks) {

    public HostileServerLimits {
        if (maxFrameBytes < EdgeFrameCodec.HEADER_SIZE + EdgeFrameCodec.TRAILER_SIZE
                || maxFrameBytes > EdgeFrameCodec.MAX_EDGE_FRAME_SIZE) {
            throw new IllegalArgumentException("maxFrameBytes must be in ["
                    + (EdgeFrameCodec.HEADER_SIZE + EdgeFrameCodec.TRAILER_SIZE) + ", "
                    + EdgeFrameCodec.MAX_EDGE_FRAME_SIZE + "]: " + maxFrameBytes);
        }
        requirePositive("connectTimeoutMs", connectTimeoutMs);
        requirePositive("handshakeTimeoutMs", handshakeTimeoutMs);
        requirePositive("readIdleDeadlineMs", readIdleDeadlineMs);
        if (maxSnapshotTotalBytes <= 0) {
            throw new IllegalArgumentException("maxSnapshotTotalBytes must be positive: " + maxSnapshotTotalBytes);
        }
        if (maxSnapshotChunks <= 0) {
            throw new IllegalArgumentException("maxSnapshotChunks must be positive: " + maxSnapshotChunks);
        }
    }

    public static HostileServerLimits defaults() {
        return new HostileServerLimits(
                EdgeFrameCodec.MAX_EDGE_FRAME_SIZE,
                2_000,
                2_000,
                2_000,
                512L * 1024 * 1024,
                65_536);
    }

    private static void requirePositive(String field, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive: " + value);
        }
    }
}
