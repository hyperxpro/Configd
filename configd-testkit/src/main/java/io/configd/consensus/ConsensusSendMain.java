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

/**
 * Consensus brief-confirm - the <b>sender</b>. Opens one peer link (synchronous
 * connect) with the forced transport tier ({@link NettyTransport#select()} honouring
 * {@code -Dconfigd.netty.transport}) and sends Raft frames through the <b>production</b>
 * {@link NettyConsensusFrameEncoder} (the in-pipeline near-zero-allocation event-loop encoder).
 * The receiver is the byte-draining {@link io.configd.jdkvsnetty.ConsensusDrainServerMain} (untraced
 * and identical across tiers), so straceing this sender is a clean io_uring-vs-epoll comparison at a
 * single connection - the consensus wire's actual connection scale (N-1 peers), where io_uring is
 * expected to show little benefit.
 *
 * <pre>
 *   java --enable-preview -Dconfigd.netty.transport=io_uring -cp benchmarks.jar \
 *        io.configd.consensus.ConsensusSendMain &lt;recvHost&gt; &lt;recvPort&gt; &lt;payloadBytes&gt; &lt;count&gt; &lt;ratePerSec&gt;
 * </pre>
 */
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
            // Immutable frame reused - the encoder reads it in-pipeline (byte-identical encoding).
            FrameCodec.Frame frame = new FrameCodec.Frame(type, 1, 42L, payload);

            long intervalNanos = rate > 0 ? (1_000_000_000L / rate) : 0L;
            long t0 = System.nanoTime();
            for (long i = 0; i < count; i++) {
                // writeAndFlush from this thread hands the encode+write+flush to the event loop;
                // one flush per frame is roughly one socket write - the un-coalesced single-link
                // send pattern.
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
