package io.configd.jdkvsnetty;

import com.sun.management.ThreadMXBean;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;

/**
 * End-to-end consensus-send head-to-head - the <b>sender</b>. Settles the one thing the
 * encode-only {@code ConsensusWireH2HBenchmark} did not measure: the <b>write path</b>, where the
 * "Netty's off-heap {@code ByteBuf} avoids a heap->kernel copy" argument lives. Races, over a real
 * TCP connection to {@link ConsensusDrainServerMain}, four variants:
 *
 * <ul>
 *   <li><b>jdk</b> - production-style: reused heap buffer, {@code Socket.getOutputStream().write},
 *       per-message write-through (one syscall/msg).</li>
 *   <li><b>jdk-batched</b> - same, wrapped in {@code BufferedOutputStream}, flush every 64
 *       (batched syscalls) - the fair throughput peer to netty-idiomatic.</li>
 *   <li><b>netty</b> - manual: main-thread {@code alloc.directBuffer} + {@code writeAndFlush} per
 *       message (the non-idiomatic path; kept to show the WriteTask/cross-thread artifacts).</li>
 *   <li><b>netty-idiomatic</b> - in-pipeline {@code MessageToByteEncoder} (encode/alloc/release on
 *       the event loop, Recycler works, {@code internalNioBuffer} CRC), {@code write} + batched
 *       flush.</li>
 * </ul>
 *
 * <p><b>Each variant builds its connection ONCE and runs warmup AND measurement on that same
 * connection</b> - so the measured window runs on a <em>warm</em> event loop / socket (populated
 * allocator-arena caches, Recycler stacks, JIT), exactly as a long-lived transport would; a cold
 * event loop would inflate Netty's numbers.
 *
 * <p><b>Plaintext on purpose</b> - the best case for the off-heap-{@code ByteBuf} argument; TLS
 * forces an {@code SSLEngine} copy on both stacks and shrinks any Netty edge. Allocation isolation:
 * the drain receiver is a separate process; this JVM's {@code getTotalThreadAllocatedBytes()} delta
 * over N sends / N is the sender-side per-message allocation (captures the Netty event-loop thread).
 *
 * <pre>java --enable-preview --enable-native-access=ALL-UNNAMED -cp benchmarks.jar \
 *   io.configd.jdkvsnetty.ConsensusSendE2EMain &lt;host&gt; &lt;port&gt; &lt;payloadBytes&gt; &lt;warmupN&gt; &lt;measureN&gt;</pre>
 */
public final class ConsensusSendE2EMain {

    private ConsensusSendE2EMain() {
    }

    private static final int GROUP_ID = 7;
    private static final long TERM = 42L;
    private static final int SENDER_ID = 3;
    private static final int FLUSH_EVERY = 64;

    private static final ThreadMXBean TB =
            (ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int payloadBytes = Integer.parseInt(args[2]);
        long warmupN = Long.parseLong(args[3]);
        long measureN = Long.parseLong(args[4]);

        TB.setThreadAllocatedMemoryEnabled(true);
        MessageType type = (payloadBytes == 0) ? MessageType.HEARTBEAT : MessageType.APPEND_ENTRIES;
        byte[] payload = new byte[payloadBytes];
        for (int i = 0; i < payloadBytes; i++) {
            payload[i] = (byte) i;
        }
        int cap = 4 + FrameCodec.frameSize(payloadBytes);

        String only = args.length > 5 ? args[5] : "all";
        if (only.equals("all") || only.equals("jdk")) {
            jdkSend(host, port, type, payload, cap, warmupN, measureN, false);
        }
        if (only.equals("all") || only.equals("jdk-batched")) {
            jdkSend(host, port, type, payload, cap, warmupN, measureN, true);
        }
        if (only.equals("all") || only.equals("manual")) {
            nettySend(host, port, type, payload, cap, warmupN, measureN);
        }
        if (only.equals("all") || only.equals("idiomatic")) {
            nettyIdiomaticSend(host, port, new FrameMsg(type, GROUP_ID, TERM, payload, SENDER_ID),
                    warmupN, measureN);
        }
        if (only.equals("all") || only.equals("eventloop")) {
            nettyEventLoopSend(host, port, new FrameMsg(type, GROUP_ID, TERM, payload, SENDER_ID),
                    warmupN, measureN);
        }
    }

    /**
     * Drives writes FROM the event loop, so {@code AbstractChannelHandlerContext.write} takes its
     * {@code inEventLoop()} inline branch - <b>no per-message {@code WriteTask}</b> (the source: the
     * WriteTask is allocated only on the off-loop {@code else} branch). A single reused,
     * self-rescheduling {@link Runnable} writes a chunk inline, flushes, and re-submits itself - 
     * no per-message and no per-chunk lambda allocation. This is exactly how a real transport sends
     * timer-driven heartbeats (`eventLoop.scheduleAtFixedRate` runs on the loop) or batched appends.
     */
    private static void nettyEventLoopSend(String host, int port, FrameMsg msg,
                                           long warmupN, long measureN) throws Exception {
        boolean epoll = Epoll.isAvailable();
        EventLoopGroup g = newGroup(epoll);
        try {
            Channel ch = new Bootstrap().group(g)
                    .channel(epoll ? EpollSocketChannel.class : NioSocketChannel.class)
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                    .handler(new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel c) {
                            c.pipeline().addLast(new NettyConsensusFrameEncoder());
                        }
                    })
                    .connect(host, port).sync().channel();
            for (long phase = 0; phase < 2; phase++) {
                boolean measure = phase == 1;
                long n = measure ? measureN : warmupN;
                long a0 = 0, t0 = 0;
                if (measure) {
                    System.gc();
                    Thread.sleep(150);
                    a0 = TB.getTotalThreadAllocatedBytes();
                    t0 = System.nanoTime();
                }
                CountDownLatch done = new CountDownLatch(1);
                Drainer d = new Drainer(ch, msg, n, done);
                ch.eventLoop().execute(d);
                done.await();
                if (measure) {
                    report("netty-eventloop(epoll=" + epoll + ")", msg.payload().length, n,
                            TB.getTotalThreadAllocatedBytes() - a0, System.nanoTime() - t0);
                }
            }
            ch.close().sync();
        } finally {
            g.shutdownGracefully();
        }
    }

    private static final class Drainer implements Runnable {
        private static final int CHUNK = 256;
        private final Channel ch;
        private final FrameMsg msg;
        private final CountDownLatch done;
        private long remaining;

        Drainer(Channel ch, FrameMsg msg, long remaining, CountDownLatch done) {
            this.ch = ch;
            this.msg = msg;
            this.remaining = remaining;
            this.done = done;
        }

        @Override
        public void run() {
            int c = 0;
            while (remaining > 0 && c < CHUNK && ch.isWritable()) {
                ch.write(msg, ch.voidPromise());
                remaining--;
                c++;
            }
            ch.flush();
            if (remaining > 0) {
                ch.eventLoop().execute(this); // reschedule the SAME Runnable (no lambda alloc)
            } else {
                done.countDown();
            }
        }
    }

    private static void jdkSend(String host, int port, MessageType type, byte[] payload, int cap,
                                long warmupN, long measureN, boolean batched) throws Exception {
        try (Socket s = new Socket(host, port)) {
            s.setTcpNoDelay(true);
            OutputStream raw = s.getOutputStream();
            OutputStream out = batched ? new BufferedOutputStream(raw, 64 * 1024) : raw;
            ByteBuffer reuse = ByteBuffer.allocate(cap);
            for (long phase = 0; phase < 2; phase++) {
                boolean measure = phase == 1;
                long n = measure ? measureN : warmupN;
                long a0 = 0, t0 = 0;
                if (measure) {
                    System.gc();
                    Thread.sleep(150);
                    a0 = TB.getTotalThreadAllocatedBytes();
                    t0 = System.nanoTime();
                }
                for (long i = 0; i < n; i++) {
                    reuse.clear();
                    reuse.putInt(SENDER_ID);
                    FrameCodec.encode(reuse, type, GROUP_ID, TERM, payload);
                    out.write(reuse.array(), 0, reuse.position());
                    if (batched && (i & (FLUSH_EVERY - 1)) == 0) {
                        out.flush();
                    }
                }
                out.flush();
                if (measure) {
                    report(batched ? "jdk-batched" : "jdk", payload.length, n,
                            TB.getTotalThreadAllocatedBytes() - a0, System.nanoTime() - t0);
                }
            }
        }
    }

    private static void nettySend(String host, int port, MessageType type, byte[] payload, int cap,
                                  long warmupN, long measureN) throws Exception {
        boolean epoll = Epoll.isAvailable();
        EventLoopGroup g = newGroup(epoll);
        try {
            Channel ch = new Bootstrap().group(g)
                    .channel(epoll ? EpollSocketChannel.class : NioSocketChannel.class)
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                    .handler(new ChannelInboundHandlerAdapter())
                    .connect(host, port).sync().channel();
            for (long phase = 0; phase < 2; phase++) {
                boolean measure = phase == 1;
                long n = measure ? measureN : warmupN;
                long a0 = 0, t0 = 0;
                if (measure) {
                    System.gc();
                    Thread.sleep(150);
                    a0 = TB.getTotalThreadAllocatedBytes();
                    t0 = System.nanoTime();
                }
                for (long i = 0; i < n; i++) {
                    awaitWritable(ch);
                    ByteBuf buf = ch.alloc().directBuffer(cap);
                    NettyWireEncoders.encodeSendWireInto(buf, SENDER_ID, type, GROUP_ID, TERM, payload);
                    ch.writeAndFlush(buf, ch.voidPromise());
                }
                if (measure) {
                    // ensure the pipeline drained before snapshotting the counter
                    ch.writeAndFlush(ch.alloc().directBuffer(cap).writeInt(0)).sync();
                    report("netty(epoll=" + epoll + ")", payload.length, n,
                            TB.getTotalThreadAllocatedBytes() - a0, System.nanoTime() - t0);
                }
            }
            ch.close().sync();
        } finally {
            g.shutdownGracefully();
        }
    }

    private static void nettyIdiomaticSend(String host, int port, FrameMsg msg,
                                           long warmupN, long measureN) throws Exception {
        boolean epoll = Epoll.isAvailable();
        EventLoopGroup g = newGroup(epoll);
        try {
            Channel ch = new Bootstrap().group(g)
                    .channel(epoll ? EpollSocketChannel.class : NioSocketChannel.class)
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                    .handler(new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel c) {
                            c.pipeline().addLast(new NettyConsensusFrameEncoder());
                        }
                    })
                    .connect(host, port).sync().channel();
            for (long phase = 0; phase < 2; phase++) {
                boolean measure = phase == 1;
                long n = measure ? measureN : warmupN;
                long a0 = 0, t0 = 0;
                if (measure) {
                    System.gc();
                    Thread.sleep(150);
                    a0 = TB.getTotalThreadAllocatedBytes();
                    t0 = System.nanoTime();
                }
                for (long i = 0; i < n; i++) {
                    awaitWritable(ch);
                    ch.write(msg, ch.voidPromise());
                    if ((i & (FLUSH_EVERY - 1)) == 0) {
                        ch.flush();
                    }
                }
                ch.flush();
                if (measure) {
                    ch.writeAndFlush(msg).sync();          // drain before snapshot
                    report("netty-idiomatic(epoll=" + epoll + ")", msg.payload().length, n,
                            TB.getTotalThreadAllocatedBytes() - a0, System.nanoTime() - t0);
                }
            }
            ch.close().sync();
        } finally {
            g.shutdownGracefully();
        }
    }

    private static EventLoopGroup newGroup(boolean epoll) {
        IoHandlerFactory io = epoll ? EpollIoHandler.newFactory() : NioIoHandler.newFactory();
        return new MultiThreadIoEventLoopGroup(1, io);
    }

    private static void awaitWritable(Channel ch) {
        if (!ch.isWritable()) {
            ch.flush(); // flush queued writes BEFORE waiting, else batched writes never drain (deadlock)
        }
        while (!ch.isWritable()) {
            if (!ch.isActive()) {
                throw new IllegalStateException("netty channel closed mid-send");
            }
            LockSupport.parkNanos(50_000);
        }
    }

    private static void report(String which, int payload, long n, long allocBytes, long nanos) {
        System.out.printf(
                "E2E which=%s payload=%d sends=%d perMsgAllocBytes=%.1f nsPerMsg=%.0f throughputMsgPerSec=%.0f%n",
                which, payload, n, (double) allocBytes / n, (double) nanos / n, n / (nanos / 1e9));
        System.out.flush();
    }
}
