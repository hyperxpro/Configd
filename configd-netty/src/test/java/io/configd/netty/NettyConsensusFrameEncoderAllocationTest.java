package io.configd.netty;

import com.sun.management.ThreadMXBean;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import io.configd.transport.RaftWireProtocol;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.FastThreadLocalThread;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Allocation proof for the production {@link NettyConsensusFrameEncoder}: the idiomatic in-pipeline,
 * event-loop-driven encode allocates <b>~0 B/op</b>.
 *
 * <h2>What the 160 actually was (the surprise this test pins)</h2>
 * A naive off-loop microbench shows ~160 B/op: the per-op {@code io.netty.buffer.PooledDirectByteBuf}
 * <b>holder</b>. Crucially, the discriminator is <b>not</b> pipeline-vs-manual - it is the <b>thread
 * type</b>. Netty's
 * pooled-{@code ByteBuf} holder {@code Recycler} and {@code PoolThreadCache} only engage on a
 * {@link FastThreadLocalThread} (an event-loop thread). The head-to-head's 160 was measured on a plain
 * JMH worker thread, off any event loop; the production encoder runs in {@code NettyRaftTransport}'s
 * {@code drain()} <b>on the event loop</b>, so the holder is recycled and the encode allocates ~0. This
 * test measures the full 2x2 to prove exactly that: on the event loop both the manual loop AND the
 * production encoder are ~0; off it, both are ~160.
 *
 * <h2>Method</h2>
 * Per-op heap allocation via {@link ThreadMXBean#getThreadAllocatedBytes(long)} on the encoding thread
 * (the trustworthy, CPU-count-independent axis on this 2-vCPU box - the same axis
 * {@code NettyEncodeOnlyProfileMain} used to attribute the 160). One {@link FrameCodec.Frame} is reused
 * across all ops so the only per-op heap allocation is the encoder's. Heavy warmup drives JIT + the
 * pool/recycler to steady state; every produced {@link ByteBuf} is released.
 *
 * <p>This is a JUnit measurement, not JMH (JMH would need the testkit/shade plumbing); the thresholds
 * are deliberately generous because the point is which <em>side of the cliff</em> the production path is
 * on, and allocation is deterministic (the 3x-stability is documented in the proof doc).
 */
@Timeout(300)
class NettyConsensusFrameEncoderAllocationTest {

    static {
        // Match the JMH/profile methodology: no leak-tracking allocation in the timed runs.
        System.setProperty("io.netty.leakDetection.level", "disabled");
    }

    private static final ThreadMXBean TB = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final ThreadLocal<CRC32C> CRC = ThreadLocal.withInitial(CRC32C::new);
    private static final ByteBufAllocator ALLOC = PooledByteBufAllocator.DEFAULT; // matches production

    private static final int SENDER_ID = 3;
    private static final int WARMUP = 100_000;
    private static final int MEASURE = 500_000;
    /** Generous ~0 ceiling: the production path measures 0 here; a 64 B/op cap tolerates harness noise. */
    private static final long NEAR_ZERO = 64;
    /** Floor proving the naive microbench reproduced the holder-per-op trap (observed 160, deterministic). */
    private static final long TRAP_FLOOR = 100;

    @FunctionalInterface
    interface Body { void run(int reps); }

    private final long[] sink = {0};

    @Test
    void idiomaticInPipelineConsensusEncodeIsNearZeroNotThe160Trap() throws Exception {
        TB.setThreadAllocatedMemoryEnabled(true);

        // A plain (non-Netty) thread = the head-to-head JMH/microbench condition; a real Netty
        // event-loop thread (FastThreadLocalThread) = the production condition.
        ExecutorService plain = Executors.newSingleThreadExecutor(r -> new Thread(r, "alloc-plain-thread"));
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoop eventLoop = group.next();

        try {
            assertFalse(plain.submit(() -> Thread.currentThread() instanceof FastThreadLocalThread).get(),
                    "control thread must be a plain Thread (the microbench condition)");
            assertTrue(eventLoop.submit(() -> Thread.currentThread() instanceof FastThreadLocalThread).get(),
                    "event-loop thread must be a FastThreadLocalThread (the production condition)");

            System.out.println();
            System.out.println("=== NettyConsensusFrameEncoder per-op heap allocation (getThreadAllocatedBytes) ===");
            System.out.printf("%-10s %24s %24s%n", "payload", "idiomatic(event-loop)", "naive-trap(plain-thread)");

            long idiomaticEl256 = -1;
            long naivePlain256 = -1;
            for (int payload : new int[]{0, 256, 4096}) {
                FrameCodec.Frame frame = frame(payload);
                long idiomaticEl = measureIdiomatic(eventLoop, frame);   // PRODUCTION path: encoder, in-pipeline, on the loop
                long naivePlain = measureNaive(plain, frame, payload);   // NAIVE trap: manual alloc/release, off the loop
                System.out.printf("%-10d %24d %24d%n", payload, idiomaticEl, naivePlain);

                assertTrue(idiomaticEl < NEAR_ZERO,
                        "idiomatic in-pipeline encode must be ~0 (<" + NEAR_ZERO + " B/op); was "
                                + idiomaticEl + " at payload " + payload);
                assertTrue(naivePlain >= TRAP_FLOOR,
                        "naive microbench must reproduce the ~160 holder-per-op trap; was "
                                + naivePlain + " at payload " + payload);
                assertTrue(idiomaticEl * 2 < naivePlain,
                        "idiomatic (" + idiomaticEl + ") must be dramatically below naive/2 ("
                                + naivePlain + "/2) at payload " + payload);

                if (payload == 256) {
                    idiomaticEl256 = idiomaticEl;
                    naivePlain256 = naivePlain;
                }
            }

            // Mechanism: the cliff is the FastThreadLocalThread, not pipeline-vs-manual.
            FrameCodec.Frame f256 = frame(256);
            long naiveEl = measureNaive(eventLoop, f256, 256);       // manual loop, but ON the event loop  -> ~0
            long idiomaticPlain = measureIdiomatic(plain, f256);     // production encoder, but OFF the loop -> ~160
            System.out.println();
            System.out.println("--- mechanism (payload 256): recycling is gated on the event-loop thread ---");
            System.out.printf("%-28s naive(manual)=%-6d  idiomatic(production encoder)=%-6d%n",
                    "plain-thread (off loop):", naivePlain256, idiomaticPlain);
            System.out.printf("%-28s naive(manual)=%-6d  idiomatic(production encoder)=%-6d%n",
                    "event-loop  (production):", naiveEl, idiomaticEl256);
            System.out.println("(both columns ~0 ON the loop, both ~160 OFF it => the discriminator is the thread, "
                    + "and the production encoder always runs ON the loop)");
            System.out.println();

            assertTrue(naiveEl < NEAR_ZERO,
                    "manual loop ON the event loop also recycles to ~0; was " + naiveEl);
            assertTrue(idiomaticPlain >= TRAP_FLOOR,
                    "the SAME production encoder OFF the event loop hits the ~160 trap; was " + idiomaticPlain
                            + " — proving the win is the event-loop thread, which production guarantees");
        } finally {
            group.shutdownGracefully();
            plain.shutdownNow();
        }

        // Defeat dead-code elimination of the naive legs.
        if (sink[0] == Long.MIN_VALUE) {
            throw new AssertionError("unreachable sink guard");
        }
    }

    /** Idiomatic/production: drive the real {@link NettyConsensusFrameEncoder} in a pipeline, writes
     *  originating on {@code exec}'s thread (a ReleaseSink frees the encoded buffer on that same
     *  thread - the production lifecycle: alloc in the encoder, release after the write, both on the
     *  event loop). The encoder allocates a pooled DIRECT buffer; only the heap holder shows here. */
    private long measureIdiomatic(ExecutorService exec, FrameCodec.Frame frame) throws Exception {
        return exec.submit(() -> {
            EmbeddedChannel ch = new EmbeddedChannel(new ReleaseSink(), new NettyConsensusFrameEncoder(SENDER_ID));
            ch.config().setAllocator(ALLOC);
            try {
                return perOp(reps -> {
                    for (int i = 0; i < reps; i++) {
                        ch.write(frame, ch.voidPromise());
                        ch.flush();
                    }
                });
            } finally {
                ch.finishAndReleaseAll();
            }
        }).get();
    }

    /** Naive trap: the SAME field writes as the production encoder, but a manual
     *  {@code alloc.ioBuffer()->encode->release()} loop per op (the head-to-head's microbench shape;
     *  see {@code NettyEncodeOnlyProfileMain}). On a plain thread the holder is not recycled. */
    private long measureNaive(ExecutorService exec, FrameCodec.Frame frame, int payload) throws Exception {
        int size = RaftWireProtocol.SENDER_ID_SIZE + FrameCodec.frameSize(payload);
        return exec.submit(() -> perOp(reps -> {
            for (int i = 0; i < reps; i++) {
                ByteBuf buf = ALLOC.ioBuffer(size);
                try {
                    buf.writeInt(SENDER_ID);
                    int frameStart = buf.writerIndex();
                    int totalLength = FrameCodec.frameSize(payload);
                    buf.writeInt(totalLength);
                    buf.writeByte(FrameCodec.WIRE_VERSION);
                    buf.writeByte((byte) frame.messageType().code());
                    buf.writeInt(frame.groupId());
                    buf.writeLong(frame.term());
                    buf.writeLong(0L); // reserved epoch - keep the SAME field writes as production
                    buf.writeBytes(frame.payload());
                    CRC32C crc = CRC.get();
                    crc.reset();
                    crc.update(buf.internalNioBuffer(frameStart, totalLength - FrameCodec.TRAILER_SIZE));
                    buf.writeInt((int) crc.getValue());
                    sink[0] ^= buf.getInt(4);
                } finally {
                    buf.release();
                }
            }
        })).get();
    }

    private static long perOp(Body body) {
        body.run(WARMUP);
        long tid = Thread.currentThread().threadId();
        long before = TB.getThreadAllocatedBytes(tid);
        body.run(MEASURE);
        long after = TB.getThreadAllocatedBytes(tid);
        return (after - before) / MEASURE;
    }

    private static FrameCodec.Frame frame(int payload) {
        byte[] p = new byte[payload];
        for (int i = 0; i < payload; i++) {
            p[i] = (byte) (i * 31 + 7);
        }
        return new FrameCodec.Frame(payload == 0 ? MessageType.HEARTBEAT : MessageType.APPEND_ENTRIES, 7, 42L, p);
    }

    /** Terminal outbound handler: frees the encoded buffer on the encoding thread, completing the
     *  alloc-to-release cycle the production socket write performs (so the holder Recycler engages). */
    @ChannelHandler.Sharable
    static final class ReleaseSink extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            if (!promise.isVoid()) {
                promise.setSuccess();
            }
        }
    }
}
