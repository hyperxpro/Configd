package io.configd.netty;

import io.configd.common.NodeId;
import io.configd.transport.FrameCodec;
import io.configd.transport.InboundMessage;
import io.configd.transport.RaftWireProtocol;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

import java.util.List;

/**
 * Inbound: accumulates the consensus wire ({@code [4B senderId][FrameCodec frame]}) and emits one
 * {@link InboundMessage} per complete frame, preserving the JDK reader's discipline verbatim:
 *
 * <ul>
 *   <li><b>Bounds before allocation</b> - the declared frame length (the 4 bytes after the sender id,
 *       which are the frame's own length field) is checked against
 *       {@link RaftWireProtocol#isValidFrameLength} <b>before</b> the frame buffer is allocated, so a
 *       peer cannot induce a giant allocation by lying in the length prefix. Out of range:
 *       {@link CorruptedFrameException} (the stream is desynced; the pipeline closes the channel,
 *       the JDK reader's {@code throw new IOException} drop-connection path).</li>
 *   <li><b>Decode-first</b> - {@link FrameCodec#decode} verifies the CRC32C / version / type; a
 *       failure is a desync, throwing {@link CorruptedFrameException} to close. (A <em>handler</em>
 *       throw, by contrast, is caught downstream and does <em>not</em> close - the framing layer is
 *       intact.)</li>
 * </ul>
 *
 * Shared by the inbound (accepted) pipeline and the outbound peer pipeline (a peer may send responses
 * back on the connection this node opened - the JDK transport reads on both directions too).
 */
final class RaftFrameDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        final int prefix = RaftWireProtocol.SENDER_ID_SIZE; // 4-byte sender id
        if (in.readableBytes() < prefix + 4) {
            return; // need the sender id + the frame's 4-byte length prefix
        }
        int base = in.readerIndex();
        int senderId = in.getInt(base);
        int frameLength = in.getInt(base + prefix); // the frame's own length field
        if (!RaftWireProtocol.isValidFrameLength(frameLength)) {
            throw new CorruptedFrameException("Invalid frame length: " + frameLength);
        }
        if (in.readableBytes() < prefix + frameLength) {
            return; // the full frame has not arrived yet
        }
        in.skipBytes(prefix); // consume the sender id
        byte[] frameBytes = new byte[frameLength];
        in.readBytes(frameBytes); // the complete FrameCodec frame (length field through CRC trailer)
        FrameCodec.Frame frame;
        try {
            frame = FrameCodec.decode(frameBytes);
        } catch (FrameCodec.UnsupportedWireVersionException | IllegalArgumentException e) {
            // CRC / version / type / length-mismatch: the stream is desynced - drop the connection.
            throw new CorruptedFrameException("frame decode failed: " + e.getMessage());
        }
        out.add(new InboundMessage(NodeId.of(senderId), frame));
    }
}
