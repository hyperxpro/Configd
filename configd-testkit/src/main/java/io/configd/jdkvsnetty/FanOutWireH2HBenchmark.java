package io.configd.jdkvsnetty;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JDK-vs-Netty head-to-head — <b>surface 3: edge fan-out NOTIFY wire</b> (the highest per-op
 * allocation in the system, on the hottest push path: every committed delta → every
 * subscriber). Phase R measured the status-quo codec at up to 71 KB/op (signed, batch 64) and
 * claimed the dominant term is <em>codec-internal churn upstream of the transport</em>. This
 * benchmark tests that claim directly by decomposing the allocation and racing best-JDK vs
 * best-Netty. Run with {@code -prof gc} (B/op, CPU-count-independent).
 *
 * <h2>The decomposition (the heart of the verdict)</h2>
 * <ul>
 *   <li>{@code jdkStatusQuoEncode} — TOTAL today ({@link EdgeFrameCodec#encode}): intermediate
 *       {@code List<byte[]>}, a per-notification {@code ByteBuffer}, a payload array then a
 *       second {@code out} array, plus the message-building term below.</li>
 *   <li>{@code jdkBestEncodeInto} — BEST JDK: {@link H2HCodecs#encodeNotifyInto} single-pass
 *       into ONE reused heap buffer. Removes every intermediate/output array. Byte-identical
 *       to status quo (proven by {@code WireH2HCorrectnessTest}). No Netty.</li>
 *   <li>{@code messageBuildingFloor} — the CODEC-INTERNAL floor that NEITHER a reused JDK
 *       buffer NOR a pooled Netty {@code ByteBuf} can remove: per notification, the
 *       {@link CommandCodec#encodeBatch} blob + the {@link ConfigDelta#signature()} /
 *       {@link ConfigDelta#nonce()} defensive clones. This is the allocation "upstream of the
 *       wire" — the term Phase R said the transport layer cannot touch. Measuring it isolates
 *       exactly how much of the 71 KB is, and is not, a transport concern.</li>
 *   <li>{@code jdkDecodeNotify} — receive side, for completeness.</li>
 * </ul>
 * The Netty leg ({@code nettyBestEncodePooled}, single pass into a pooled {@code ByteBuf})
 * lives in {@code FanOutWireNettyH2HBenchmark}, added once the Netty dependency is wired. The
 * race: if {@code jdkBestEncodeInto} ≈ {@code nettyBestEncodePooled} ≈ {@code
 * messageBuildingFloor}, the win over status quo is the single-pass rewrite (shared, no Netty),
 * and the residual floor is codec-internal — Netty addresses neither.
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
public class FanOutWireH2HBenchmark {

    /** Notifications per NOTIFY frame: 1 = a single commit, 64 = MAX_NOTIFY_BATCH. */
    @Param({"1", "16", "64"})
    int notifyCount;

    private static final int VALUE_BYTES = 64;
    private static final int ED25519_SIG_BYTES = 64;

    private EdgeFrame.Notify notifyFrame;
    private byte[] preEncodedNotify;
    private ByteBuffer reuseBuf; // BEST-JDK reused destination
    private ByteBufAllocator alloc; // BEST-NETTY pooled allocator
    private int bufCapacity;

    @Setup(Level.Trial)
    public void setUp() {
        byte[] value = new byte[VALUE_BYTES];
        for (int i = 0; i < VALUE_BYTES; i++) {
            value[i] = (byte) i;
        }
        // Identical signed steady-state shape as the Phase R EdgeWireAllocBenchmark (apples-to-
        // apples with the prior baseline): Ed25519 sig + non-zero epoch + 8-byte nonce.
        List<CommitNotification> notifications = new ArrayList<>(notifyCount);
        for (int i = 0; i < notifyCount; i++) {
            List<ConfigMutation> mutations =
                    List.of(new ConfigMutation.Put("config/service/key-" + i, value));
            byte[] signature = new byte[ED25519_SIG_BYTES];
            byte[] nonce = new byte[ConfigDelta.NONCE_LEN];
            ConfigDelta delta = new ConfigDelta(i, i + 1L, mutations, signature, i + 1L, nonce);
            notifications.add(new CommitNotification(i + 1L, 1_700_000_000_000L + i, delta));
        }
        notifyFrame = new EdgeFrame.Notify(notifications);
        preEncodedNotify = EdgeFrameCodec.encode(notifyFrame);
        // Size the reused buffer to the worst case (batch 64) so one buffer serves every param.
        bufCapacity = EdgeFrameCodec.MAX_NOTIFY_BATCH_BYTES + 64;
        reuseBuf = ByteBuffer.allocate(bufCapacity);
        alloc = PooledByteBufAllocator.DEFAULT;
        // Warm the pool once so the first measured op isn't a one-time arena/chunk allocation.
        ByteBuf warm = alloc.directBuffer(bufCapacity);
        NettyWireEncoders.encodeNotifyInto(warm, notifyFrame);
        warm.release();
    }

    /** STATUS QUO: the allocating production NOTIFY encode (all the intermediate churn). */
    @Benchmark
    public byte[] jdkStatusQuoEncode() {
        return EdgeFrameCodec.encode(notifyFrame);
    }

    /** BEST JDK: single-pass into one reused buffer. Reads the length prefix back (no DCE). */
    @Benchmark
    public int jdkBestEncodeInto() {
        H2HCodecs.encodeNotifyInto(reuseBuf, notifyFrame);
        return reuseBuf.getInt(0);
    }

    /**
     * BEST NETTY: single pass into a pooled, reference-counted direct {@code ByteBuf} from
     * {@code PooledByteBufAllocator.DEFAULT}, released each op. Byte-identical to status quo.
     * The pooled buffer is off-heap; {@code -prof gc} (heap B/op) therefore shows the
     * message-building floor PLUS only the {@code ByteBuf} holder bookkeeping — directly
     * comparable to {@code jdkBestEncodeInto}. If the two match, Netty's pool buys nothing the
     * reused JDK buffer didn't.
     */
    @Benchmark
    public int nettyBestEncodePooled() {
        ByteBuf buf = alloc.directBuffer(bufCapacity);
        try {
            NettyWireEncoders.encodeNotifyInto(buf, notifyFrame);
            return buf.getInt(0);
        } finally {
            buf.release();
        }
    }

    /**
     * The CODEC-INTERNAL message-building floor — the allocation upstream of the wire that no
     * transport (JDK reused buffer or Netty pooled ByteBuf) removes: per notification, the
     * encodeBatch blob + the signature/nonce defensive clones.
     */
    @Benchmark
    public void messageBuildingFloor(Blackhole bh) {
        for (CommitNotification n : notifyFrame.notifications()) {
            ConfigDelta d = n.delta();
            bh.consume(CommandCodec.encodeBatch(d.mutations()));
            bh.consume(d.signature());
            bh.consume(d.nonce());
        }
    }

    /** RECEIVE SIDE: decode the NOTIFY batch back to frames + deltas + mutations. */
    @Benchmark
    public EdgeFrame jdkDecodeNotify() {
        return EdgeFrameCodec.decode(preEncodedNotify);
    }
}
