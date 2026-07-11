package io.configd.jdkvsnetty;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.FrameSink;
import io.configd.distribution.wire.FrameType;
import io.configd.distribution.wire.HeapFrameSink;
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
import java.util.zip.CRC32C;

/**
 * Single-pass encode baseline - the <b>production</b> single-pass {@link EdgeFrameCodec#encodeInto} against the
 * original multi-pass baseline, on the batch-64 signed NOTIFY (the head-to-head surface-3 shape).
 * Run with {@code -prof gc} (B/op, CPU-count-independent on the 2-vCPU box).
 *
 * <p>This differs from {@code FanOutWireH2HBenchmark} (which raced two <em>testkit</em> encoders,
 * {@code H2HCodecs} / {@code NettyWireEncoders}, before the production codec itself was rewritten
 * to single-pass) by measuring the encoder that actually shipped:
 * <ul>
 *   <li>{@code legacyMultiPassEncode} - a verbatim copy of the {@code EdgeFrameCodec.encode}
 *       (intermediate {@code List<byte[]>}, per-notification + payload + out arrays). The 69,492
 *       B/op baseline, kept here so the A/B is reproducible in one run.</li>
 *   <li>{@code prodEncodeIntoHeapReused} - {@link EdgeFrameCodec#encodeInto} into a reused
 *       {@link HeapFrameSink} (the JDK fan-out writer's per-connection buffer). Expected ~ 25,520
 *       (the message-building floor).</li>
 *   <li>{@code prodEncodeIntoByteBufPooled} - {@link EdgeFrameCodec#encodeInto} into a pooled,
 *       reference-counted Netty {@code ByteBuf} (the in-pipeline encoder). Expected ~ 25,776
 *       (floor + the pooled-{@code ByteBuf} holder bookkeeping).</li>
 *   <li>{@code messageBuildingFloor} - the irreducible floor reference (encodeBatch + sig/nonce
 *       clones) that NEITHER backend removes.</li>
 * </ul>
 * If the two {@code encodeInto} legs ~ {@code messageBuildingFloor} << {@code legacyMultiPassEncode},
 * the single-pass rewrite shipped the win and Netty does not regress the floor.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {
        "--enable-native-access=ALL-UNNAMED",
        "-Dio.netty.leakDetection.level=DISABLED",
        "-Dio.netty.allocator.numDirectArenas=2"})
public class FanOutEncodeIntoBenchmark {

    @Param({"64"})
    int notifyCount;

    private static final int VALUE_BYTES = 64;
    private static final int ED25519_SIG_BYTES = 64;

    private EdgeFrame.Notify notifyFrame;
    private HeapFrameSink reuseHeapSink;     // reused JDK destination
    private ByteBufAllocator alloc;          // pooled Netty destination
    private int bufCapacity;

    @Setup(Level.Trial)
    public void setUp() {
        byte[] value = new byte[VALUE_BYTES];
        for (int i = 0; i < VALUE_BYTES; i++) {
            value[i] = (byte) i;
        }
        // Identical signed steady-state shape as FanOutWireH2HBenchmark (apples-to-apples baseline).
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
        bufCapacity = EdgeFrameCodec.MAX_NOTIFY_BATCH_BYTES + 64;
        reuseHeapSink = new HeapFrameSink(bufCapacity);
        alloc = PooledByteBufAllocator.DEFAULT;
        // Warm the pool + grow the heap sink once so the first measured op isn't a one-time growth.
        ByteBuf warm = alloc.directBuffer(bufCapacity);
        EdgeFrameCodec.encodeInto(notifyFrame, new ByteBufFrameSink(warm));
        warm.release();
        reuseHeapSink.reset();
        EdgeFrameCodec.encodeInto(notifyFrame, reuseHeapSink);
    }

    /** BASELINE: the original multi-pass encode (all intermediate churn). */
    @Benchmark
    public byte[] legacyMultiPassEncode() {
        return legacyEncode(notifyFrame);
    }

    /** PRODUCTION (JDK path): single pass into one reused heap sink. */
    @Benchmark
    public int prodEncodeIntoHeapReused() {
        reuseHeapSink.reset();
        EdgeFrameCodec.encodeInto(notifyFrame, reuseHeapSink);
        return reuseHeapSink.writerIndex();
    }

    /** PRODUCTION (Netty path): single pass into a pooled, released {@code ByteBuf}. */
    @Benchmark
    public int prodEncodeIntoByteBufPooled() {
        ByteBuf buf = alloc.directBuffer(bufCapacity);
        try {
            EdgeFrameCodec.encodeInto(notifyFrame, new ByteBufFrameSink(buf));
            return buf.getInt(0);
        } finally {
            buf.release();
        }
    }

    /** The codec-internal floor neither backend removes: encodeBatch + sig/nonce clones. */
    @Benchmark
    public void messageBuildingFloor(Blackhole bh) {
        for (CommitNotification n : notifyFrame.notifications()) {
            ConfigDelta d = n.delta();
            bh.consume(CommandCodec.encodeBatch(d.mutations()));
            bh.consume(d.signature());
            bh.consume(d.nonce());
        }
    }

    // A bench-local Netty ByteBuf FrameSink, shape-identical to the production
    // io.configd.server.fanout.ByteBufFrameSink (which configd-testkit does not depend on). The
    // production sink is proven byte-identical elsewhere; this proves the floor on a pooled ByteBuf.
    static final class ByteBufFrameSink implements FrameSink {
        private final ByteBuf buf;

        ByteBufFrameSink(ByteBuf buf) {
            this.buf = buf;
        }

        @Override public int writerIndex() { return buf.writerIndex(); }
        @Override public void writeByte(int b) { buf.writeByte(b); }
        @Override public void writeInt(int v) { buf.writeInt(v); }
        @Override public void writeLong(long v) { buf.writeLong(v); }
        @Override public void writeBytes(byte[] src) { buf.writeBytes(src); }
        @Override public void setInt(int index, int v) { buf.setInt(index, v); }
        @Override public void crc32cInto(CRC32C crc, int start, int length) {
            crc.update(buf.nioBuffer(start, length));
        }
    }

    // Verbatim copy of the original multi-pass EdgeFrameCodec.encode (NOTIFY path); the baseline.
    private static byte[] legacyEncode(EdgeFrame.Notify frame) {
        byte[] payload = legacyEncodeNotify(frame);
        int totalLen = EdgeFrameCodec.HEADER_SIZE + payload.length + EdgeFrameCodec.TRAILER_SIZE;
        byte[] out = new byte[totalLen];
        ByteBuffer buf = ByteBuffer.wrap(out);
        buf.putInt(totalLen);
        buf.put(EdgeFrameCodec.EDGE_WIRE_VERSION);
        buf.put((byte) FrameType.NOTIFY.code());
        buf.put(payload);
        CRC32C crc = new CRC32C();
        crc.update(out, 0, totalLen - EdgeFrameCodec.TRAILER_SIZE);
        buf.putInt((int) crc.getValue());
        return out;
    }

    private static byte[] legacyEncodeNotify(EdgeFrame.Notify f) {
        List<CommitNotification> ns = f.notifications();
        List<byte[]> encoded = new ArrayList<>(ns.size());
        int total = 4;
        for (CommitNotification n : ns) {
            byte[] nb = legacyEncodeNotification(n);
            encoded.add(nb);
            total += nb.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(total);
        buf.putInt(ns.size());
        for (byte[] nb : encoded) {
            buf.put(nb);
        }
        return buf.array();
    }

    private static byte[] legacyEncodeNotification(CommitNotification n) {
        ConfigDelta d = n.delta();
        byte[] batch = CommandCodec.encodeBatch(d.mutations());
        byte[] sig = d.signature();
        byte[] nonce = d.nonce();
        int size = 8 + 8 + 8 + 8 + 4 + batch.length + 4 + (sig == null ? 0 : sig.length) + 8 + 4 + nonce.length;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putLong(n.seq());
        buf.putLong(n.commitTimestampMillis());
        buf.putLong(d.fromVersion());
        buf.putLong(d.toVersion());
        buf.putInt(batch.length);
        buf.put(batch);
        if (sig == null) {
            buf.putInt(-1);
        } else {
            buf.putInt(sig.length);
            buf.put(sig);
        }
        buf.putLong(d.epoch());
        buf.putInt(nonce.length);
        buf.put(nonce);
        return buf.array();
    }
}
