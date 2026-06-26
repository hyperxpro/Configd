package io.configd.transport;

import io.configd.common.NodeId;

import java.util.Objects;

/**
 * An inbound consensus message: the sender's identity plus the decoded wire frame.
 * <p>
 * Promoted to a top-level type (M4 / DR-N16) so every {@link RaftTransportEndpoint}
 * implementation — the JDK {@link TcpRaftTransport} and the Netty {@code NettyRaftTransport} —
 * shares the same {@code Consumer<InboundMessage>} inbound-handler shape. Previously a record
 * nested in {@code TcpRaftTransport}; the move is mechanical (no field/semantic change) and the
 * unchanged consensus tests stay green, proving the extraction faithful.
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
