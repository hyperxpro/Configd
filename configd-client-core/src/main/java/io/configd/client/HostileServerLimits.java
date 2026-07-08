package io.configd.client;

import io.configd.distribution.wire.EdgeFrameCodec;

/**
 * The inbound bounds the client enforces on <b>server</b> frames — the mirror of the server's hostile-client
 * hardening. Because the client shares {@link EdgeFrameCodec}, the per-frame bounds (length-before-alloc, CRC
 * before interpret, version pin, type↔version legality, strict-end, every inner length/count) are enforced by
 * the same code the server runs; this record adds the <b>client-side policy</b> bounds the state machine
 * layers on top: connection/handshake/idle deadlines and the cross-frame snapshot accumulation caps.
 *
 * <p>All values are configurable; the defaults are safe and track the RFC (§06 F2 / F10-1d / WH-13/15).
 *
 * @param maxFrameBytes         the per-frame ceiling; a declared length above it is a bounded reject before
 *                              any allocation ({@link EdgeFrameCodec#MAX_EDGE_FRAME_SIZE} = 2 MiB — the frozen
 *                              wire constant; a driver MAY lower it but never raise it above the codec cap)
 * @param connectTimeoutMs      the TCP connect timeout (a stalled connect must not hang)
 * @param handshakeTimeoutMs    the TLS handshake deadline (a slow-loris that never completes the handshake
 *                              times out rather than parking the reader — §06 F9)
 * @param readIdleDeadlineMs    the HEARTBEAT-silence read-idle deadline once streaming: reconnect if no
 *                              server frame arrives within it (§06 F6-8 / F10-3). It is <b>not</b> armed
 *                              before the first business frame, since a fan-out subscriber is idle by design
 *                              until it subscribes
 * @param maxSnapshotTotalBytes the cross-frame snapshot accumulation ceiling (WH-13; Gate 2 SnapshotReassembler)
 * @param maxSnapshotChunks     the cross-frame snapshot chunk-count ceiling (WH-15; Gate 2 SnapshotReassembler)
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

    /**
     * The RFC-tracking defaults: the 2 MiB frozen frame cap, a 2 s connect and 2 s handshake deadline, a 2 s
     * HEARTBEAT-silence read-idle deadline (8 × the 250 ms heartbeat cadence), and the 512 MiB / 65536-chunk
     * snapshot accumulation caps.
     */
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
