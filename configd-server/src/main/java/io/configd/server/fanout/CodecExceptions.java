package io.configd.server.fanout;

import io.configd.distribution.wire.EdgeFrameCodec;

/**
 * Unwraps an {@link EdgeFrameCodec.CodecException} from a Netty exception cause chain. A decode failure
 * thrown from {@link ByteToEdgeFrameDecoder#decode} is a {@code RuntimeException}, so Netty's
 * {@code ByteToMessageDecoder} re-throws it wrapped in a {@code DecoderException}; the raw
 * {@code instanceof CodecException} test therefore misses it. Both the {@code EdgeAuthGateHandler} (its
 * pre-auth reject path) and the {@code FanOutConnection} (its post-auth teardown) must walk the cause
 * chain to recover the frame's real {@link io.configd.distribution.wire.ErrorCode}, so that seam lives
 * here once rather than being copied - and matches the JDK reader, which catches the {@code CodecException}
 * directly (no Netty wrapping) and already reports the real code.
 */
final class CodecExceptions {

    private CodecExceptions() {
    }

    /** The {@link EdgeFrameCodec.CodecException} in {@code t}'s cause chain, or {@code null} if none. */
    static EdgeFrameCodec.CodecException unwrap(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof EdgeFrameCodec.CodecException ce) {
                return ce;
            }
        }
        return null;
    }
}
