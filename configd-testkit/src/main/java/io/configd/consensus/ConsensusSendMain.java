package io.configd.consensus;

import io.configd.netty.NettyConsensusFrameEncoder;
import io.configd.netty.NettyTransport;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.MultiThreadIoEventLoopGroup;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public final class ConsensusSendMain {

    private ConsensusSendMain() {
    }

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int payloadBytes = Integer.parseInt(args[2]);
        long count = Long.parseLong(args[3]);
        long rate = Long.parseLong(args[4]);
        int senderId = 1;

        NettyTransport.Selection sel = NettyTransport.select();
        MultiThreadIoEventLoopGroup group =
                new MultiThreadIoEventLoopGroup(1, sel.ioHandlerFactory());
        try {
            Channel ch = new Bootstrap()
                    .group(group)
                    .channel(sel.clientChannelClass())
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel c) {
                            c.pipeline().addLast(new NettyConsensusFrameEncoder(senderId));
                        }
                    })
                    .connect(host, port).sync().channel();
            System.out.println("SENDER tier=" + sel.tier() + " → " + host + ":" + port);
            System.out.flush();

            MessageType type = (payloadBytes == 0) ? MessageType.HEARTBEAT : MessageType.APPEND_ENTRIES;
            byte[] payload = new byte[payloadBytes];
            for (int i = 0; i < payloadBytes; i++) {
                payload[i] = (byte) i;
            }
            // Reused and byte-identical: encoder reads in-pipeline once per send.
            FrameCodec.Frame frame = new FrameCodec.Frame(type, 1, 42L, payload);

            long intervalNanos = rate > 0 ? (1_000_000_000L / rate) : 0L;
            long t0 = System.nanoTime();
            for (long i = 0; i < count; i++) {
                // One flush per frame = one socket write (un-coalesced single-link pattern).
                ch.writeAndFlush(frame, ch.voidPromise());
                if (intervalNanos > 0) {
                    long wait = (t0 + (i + 1) * intervalNanos) - System.nanoTime();
                    if (wait > 0) {
                        LockSupport.parkNanos(wait);
                    }
                }
            }
            ch.close().sync();
            System.out.println("SENT " + count);
        } finally {
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS);
        }
    }
}
