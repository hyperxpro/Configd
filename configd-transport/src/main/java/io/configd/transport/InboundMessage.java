package io.configd.transport;

import io.configd.common.NodeId;

import java.util.Objects;

/**
 * An inbound consensus message: the sender's identity plus the decoded wire frame.
 *
 * @param from  the sending node (decoded from the 4-byte wire sender-id prefix)
 * @param frame the decoded {@link FrameCodec.Frame}
 */
public record InboundMessage(NodeId from, FrameCodec.Frame frame) {
    public InboundMessage {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(frame, "frame must not be null");
    }
}
