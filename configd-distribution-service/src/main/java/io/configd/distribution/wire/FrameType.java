package io.configd.distribution.wire;

/**
 * The on-wire type byte for each {@link EdgeFrame} variant (C1 design §3; ADR-0037
 * "type byte"). The numeric {@link #code()} is pinned by the
 * {@code EdgeFrameCodecGoldenFixtureTest} golden fixture — changing any code is a
 * wire-format change and MUST bump {@link EdgeFrameCodec#EDGE_WIRE_VERSION}.
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
    ERROR_CLOSE(0x09);

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
