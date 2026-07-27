package io.configd.distribution.wire;

/**
 * On-wire type byte for each EdgeFrame variant; codes pinned by EdgeFrameCodecGoldenFixtureTest
 * (changing any code is wire-format change: MUST bump EdgeFrameCodec.EDGE_WIRE_VERSION).
 * <p>
 * Codes 0x01–0x09: connection-level fan-out vocabulary (legal on V1 or V2).
 * Codes 0x0A–0x12: watch frames (W5-1), V2-only (V1 decode = FRAME_CORRUPT; W5-11).
 * Codes 0x13–0x14: auth-phase frames (AU3-3), V4-only, version-pin-exempt (may interleave
 * on any business-version connection).
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

    // Watch frames (W5-1); 0x02-only
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
    WATCH_SNAPSHOT_END(0x12),

    // Auth-phase frames (AU3-3); 0x04-only, version-pin-exempt
    /** Client-to-server: present a token/basic credential to authenticate the connection. */
    AUTH(0x13),
    /** Client-to-server: present a fresh credential to extend an already-authenticated connection. */
    REFRESH_AUTH(0x14);

    private final int code;

    FrameType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** Resolve wire type byte to FrameType; throws if not a defined type. */
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
