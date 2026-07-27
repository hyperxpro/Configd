package io.configd.jdkvsnetty;

import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import org.openjdk.jmh.annotations.*;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * JDK-vs-Netty head-to-head - <b>surface 4: inter-node consensus wire</b>.
 *
 * <p>Reasoning qualitatively about Netty from the current (unoptimized) JDK send path isn't
 * enough to settle the question. This benchmark races the <b>best JDK</b> form against the
 * <b>best Netty</b> form of the same send, apples-to-apples (identical payloads, warmup,
 * measurement), so the verdict rests on measurement. Run with {@code -prof gc} (metric
 * {@code gc.alloc.rate.norm}, B/op - CPU-count-independent, so trustworthy on the 2-vCPU box).
 *
 * <h2>The on-wire bytes (must be byte-identical across all legs)</h2>
 * Production {@code TcpRaftTransport.encodeWire} emits {@code [4B big-endian senderId] ||
 * FrameCodec.encode(type,group,term,payload)}. {@code WireH2HCorrectnessTest} proves every leg
 * below reproduces those exact bytes.
 *
 * <h2>Legs</h2>
 * <ul>
 *   <li>{@code jdkStatusQuoSend} - what {@code encodeWire} allocates TODAY: the codec frame
 *       array PLUS a second {@code byte[4+frame]} sender-id wrap (~2x the frame).</li>
 *   <li>{@code jdkBestSendInto} - BEST JDK: the sender id and the codec's existing
 *       {@code encode(ByteBuffer)} into-variant written into ONE reused heap buffer. No new
 *       dependency. Expected ~ 0 B/op steady state.</li>
 *   <li>{@code nettyBestSendPooled} - BEST Netty: the same bytes written into a pooled,
 *       reference-counted {@code ByteBuf} from {@code PooledByteBufAllocator}, released back to
 *       the pool each op (steady-state pooled behaviour).</li>
 * </ul>
 *
 * <p><b>Why this surface was expected to be close:</b> the codec already ships a zero-copy
 * {@code encode(ByteBuffer)} that escape-analysis proves to ~0 B/op, so Netty cannot beat zero -
 * the only open question was whether a pooled {@code ByteBuf} matches the reused JDK buffer. The
 * benchmarks below confirm it matches (it does not beat it).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {
        "--enable-native-access=ALL-UNNAMED",          // JDK 25 + Netty FFM/MemorySegment path
        "-Dio.netty.leakDetection.level=DISABLED",     // anti-rigging: no tracking alloc in timed runs
        "-Dio.netty.allocator.numDirectArenas=2"})     // pin arenas to the 2-vCPU box (reproducible)
public class ConsensusWireH2HBenchmark {

    /** Payload size: 0 = coalesced heartbeat (hot path), 256 = small append, 4096 = batch. */
    @Param({"0", "256", "4096"})
    int payloadBytes;

    private MessageType type;
    private int groupId;
    private long term;
    private int senderId;
    private byte[] payload;
    private byte[] preEncoded;
    private ByteBuffer reuseBuf;
    private ByteBufAllocator alloc;
    private ByteBuf reusedNettyBuf;
    private int wireCapacity;

    @Setup(Level.Trial)
    public void setUp() {
        type = (payloadBytes == 0) ? MessageType.HEARTBEAT : MessageType.APPEND_ENTRIES;
        groupId = 7;
        term = 42L;
        senderId = 3;
        payload = new byte[payloadBytes];
        for (int i = 0; i < payloadBytes; i++) {
            payload[i] = (byte) i;
        }
        preEncoded = FrameCodec.encode(type, groupId, term, payload);
        wireCapacity = 4 + FrameCodec.frameSize(payloadBytes);
        reuseBuf = ByteBuffer.allocate(wireCapacity);
        alloc = PooledByteBufAllocator.DEFAULT;
        // Warm the pool once so the first measured op isn't a one-time arena/chunk allocation.
        ByteBuf warm = alloc.directBuffer(wireCapacity);
        NettyWireEncoders.encodeSendWireInto(warm, senderId, type, groupId, term, payload);
        warm.release();
        // Diagnostic leg: one pooled direct ByteBuf reused for every op (never released here).
        reusedNettyBuf = alloc.directBuffer(wireCapacity);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (reusedNettyBuf != null) {
            reusedNettyBuf.release();
        }
    }

    /**
     * STATUS QUO (today's {@code TcpRaftTransport.encodeWire}): codec frame array + a second
     * {@code byte[4+frame]} to prepend the 4-byte big-endian sender id.
     */
    @Benchmark
    public byte[] jdkStatusQuoSend() {
        byte[] encoded = FrameCodec.encode(type, groupId, term, payload);
        byte[] wire = new byte[4 + encoded.length];
        wire[0] = (byte) (senderId >>> 24);
        wire[1] = (byte) (senderId >>> 16);
        wire[2] = (byte) (senderId >>> 8);
        wire[3] = (byte) senderId;
        System.arraycopy(encoded, 0, wire, 4, encoded.length);
        return wire;
    }

    /**
     * BEST JDK: sender id + frame written into one reused heap buffer via the codec's existing
     * {@code encode(ByteBuffer)} into-variant. A big-endian {@code putInt(senderId)} emits the
     * identical 4 bytes as the status-quo bit-shift wrap. Reads the length prefix back so the
     * writes cannot be dead-code-eliminated.
     */
    @Benchmark
    public int jdkBestSendInto() {
        reuseBuf.clear();
        reuseBuf.putInt(senderId); // ByteBuffer is big-endian by default -> identical bytes
        FrameCodec.encode(reuseBuf, type, groupId, term, payload);
        return reuseBuf.getInt(4); // frame length prefix sits right after the 4-byte sender id
    }

    /**
     * BEST NETTY: the same bytes written into a pooled, reference-counted direct {@code ByteBuf}
     * from {@code PooledByteBufAllocator.DEFAULT}, released back to the pool each op (steady-state
     * pooled behaviour). Reads the frame length prefix back so the writes cannot be DCE'd. The
     * pooled buffer is off-heap, so {@code -prof gc} (heap B/op) shows only the per-op heap cost
     * Netty cannot pool away (the {@code ByteBuf} holder bookkeeping) - the honest comparison
     * against the reused JDK buffer.
     */
    @Benchmark
    public int nettyBestSendPooled() {
        ByteBuf buf = alloc.directBuffer(wireCapacity);
        try {
            NettyWireEncoders.encodeSendWireInto(buf, senderId, type, groupId, term, payload);
            return buf.getInt(4); // frame length prefix sits right after the 4-byte sender id
        } finally {
            buf.release();
        }
    }

    /**
     * DIAGNOSTIC (not a production-valid Netty pattern): reuse ONE pooled direct {@code ByteBuf}
     * across every op, no per-op allocate/release. This isolates whether the 160 B/op of
     * {@code nettyBestSendPooled} is the per-op pool acquire/release or the encode/CRC itself.
     * NOTE: a real async Netty pipeline CANNOT do this - once {@code ctx.write(buf)} is issued the
     * buffer is owned by the pipeline until the socket flush completes, so it can't be reused for
     * the next message without serializing writes (killing the async benefit). This leg measures
     * attribution only; its number is NOT an achievable production-Netty figure.
     */
    @Benchmark
    public int nettyReusedDirectNoRelease() {
        reusedNettyBuf.clear();
        NettyWireEncoders.encodeSendWireInto(reusedNettyBuf, senderId, type, groupId, term, payload);
        return reusedNettyBuf.getInt(4);
    }

    /** RECEIVE SIDE (for completeness): decode allocates payload byte[] + one Frame record. */
    @Benchmark
    public FrameCodec.Frame jdkDecode() {
        return FrameCodec.decode(preEncoded);
    }
}
