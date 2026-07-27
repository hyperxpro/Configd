package io.configd.distribution.fanout;

import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;

public interface TransportSink {

    boolean offer(EdgeFrame frame);

    void close(ErrorCode code, String message);
}
