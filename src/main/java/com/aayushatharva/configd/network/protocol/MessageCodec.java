package com.aayushatharva.configd.network.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;

import java.util.List;

/**
 * Netty codec for the Configd wire protocol.
 *
 * Encodes/decodes length-prefixed messages with type and request ID headers.
 */
public class MessageCodec extends ByteToMessageCodec<Message> {

    private static final int MAX_FRAME_SIZE = 64 * 1024 * 1024; // 64 MB

    @Override
    protected void encode(ChannelHandlerContext ctx, Message msg, ByteBuf out) {
        int payloadLen = (msg.payload() != null) ? msg.payload().length : 0;
        int frameLen = 1 + 8 + payloadLen; // type + requestId + payload

        out.writeInt(frameLen);
        out.writeByte(msg.type().code());
        out.writeLong(msg.requestId());
        if (payloadLen > 0) {
            out.writeBytes(msg.payload());
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.readableBytes() >= 4) {
            in.markReaderIndex();

            int frameLen = in.readInt();
            if (frameLen < 0 || frameLen > MAX_FRAME_SIZE) {
                throw new IllegalStateException("Invalid frame length: " + frameLen);
            }

            if (in.readableBytes() < frameLen) {
                in.resetReaderIndex();
                return;
            }

            byte typeCode = in.readByte();
            long requestId = in.readLong();

            int payloadLen = frameLen - 1 - 8;
            byte[] payload = null;
            if (payloadLen > 0) {
                payload = new byte[payloadLen];
                in.readBytes(payload);
            }

            MessageType type = MessageType.fromCode(typeCode);
            out.add(new Message(type, requestId, payload));
        }
    }
}
