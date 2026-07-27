package io.configd.server.fanout;

import io.configd.distribution.wire.EdgeFrameCodec;


final class CodecExceptions {

    private CodecExceptions() {
    }

    
    static EdgeFrameCodec.CodecException unwrap(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof EdgeFrameCodec.CodecException ce) {
                return ce;
            }
        }
        return null;
    }
}
