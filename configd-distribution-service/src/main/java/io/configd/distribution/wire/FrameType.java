package io.configd.distribution.wire;

/**
 * The on-wire type byte for each {@link EdgeFrame} variant. The numeric {@link #code()} is
 * pinned by the {@code EdgeFrameCodecGoldenFixtureTest} golden fixture - changing any code is
 * a wire-format change and MUST bump {@link EdgeFrameCodec#EDGE_WIRE_VERSION}.
 *
 * <p><b>Codes {@code 0x01..0x09}</b> are the connection-level fan-out vocabulary (legal on
 * a {@code 0x01} or a {@code 0x02} connection). <b>Codes {@code 0x0A..0x12}</b> are the
 * client-facing <b>watch</b> frames (W5-1); they are legal <b>only</b> on a {@code 0x02}
 * connection ({@link EdgeFrameCodec#EDGE_WIRE_VERSION_V2}) - a watch type on a
 * {@code 0x01}-stamped frame decodes as {@link ErrorCode#FRAME_CORRUPT} (W5-11).
 */
public enum FrameType {

    SUBSCRIBE(0x01),
    SUBSCRIBE_OK(0x02),
    NOTIFY(0x03),
    SNAPSHOT_BEGIN(0x04),
    SNAPSHOT_CHUNK(0x05),
    SNAPSHOT_END(0x06),
    CURSOR_ACK(0x07),
    HEARTBEAT(0x08),
    ERROR_CLOSE(0x09),

    // ---- Watch frames (W5-1); 0x02-only ----
    /** Client-to-server: create/resume a watch (target + cursor vector + flags). */
    WATCH_CREATE(0x0A),
    /** Client-to-server: cancel a watch by {@code watch_id}. */
    WATCH_CANCEL(0x0B),
    /** Server-to-client: acknowledge a created watch; per-shard initial mode vector. */
    WATCH_CREATED(0x0C),
    /** Server-to-client: a per-shard change batch, tagged {@code (gid, S)}. */
    WATCH_EVENT(0x0D),
    /** Server-to-client: bookmark - advance idle cursor components, no events. */
    WATCH_PROGRESS(0x0E),
    /** Server-to-client: terminal per-watch close (authz reject, gap-unrecoverable, etc.). */
    WATCH_CANCELED(0x0F),
    /** Server-to-client: per-{@code (watch_id, gid)} catch-up snapshot header. */
    WATCH_SNAPSHOT_BEGIN(0x10),
    /** Server-to-client: per-{@code (watch_id, gid)} catch-up snapshot chunk. */
    WATCH_SNAPSHOT_CHUNK(0x11),
    /** Server-to-client: per-{@code (watch_id, gid)} catch-up snapshot trailer. */
    WATCH_SNAPSHOT_END(0x12);

    private final int code;

    FrameType(int code) {
        this.code = code;
    }

    /** The unsigned type byte that goes on the wire. */
    public int code() {
        return code;
    }

    /**
     * Resolves a wire type byte to its {@link FrameType}.
     *
     * @param code the unsigned type byte
     * @return the matching frame type
     * @throws IllegalArgumentException if {@code code} is not a defined type
     */
    public static FrameType fromCode(int code) {
        for (FrameType t : VALUES) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown edge frame type code: " + code);
    }

    private static final FrameType[] VALUES = values();
}
