package io.configd.transport;

import io.configd.common.NodeId;

import java.util.Objects;

public record InboundMessage(NodeId from, FrameCodec.Frame frame) {
    public InboundMessage {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(frame, "frame must not be null");
    }
}
