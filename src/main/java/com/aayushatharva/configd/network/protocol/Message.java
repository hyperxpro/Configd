package com.aayushatharva.configd.network.protocol;

import java.util.Arrays;

/**
 * Wire protocol message.
 *
 * Binary format:
 * <pre>
 *   [4 bytes] total length (excluding this field)
 *   [1 byte]  message type
 *   [8 bytes] request ID (for correlating request/response pairs)
 *   [N bytes] payload (JSON or binary depending on message type)
 * </pre>
 */
public record Message(MessageType type, long requestId, byte[] payload) {

    public static final int HEADER_SIZE = 4 + 1 + 8; // length + type + requestId

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Message that)) return false;
        return requestId == that.requestId
                && type == that.type
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + Long.hashCode(requestId);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }
}
