package io.configd.jdkvsnetty;

import com.sun.management.ThreadMXBean;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

import java.lang.management.ManagementFactory;
import java.util.zip.CRC32C;

/**
 * Definitively names what the encode-only {@code nettyBestSendPooled} 160 B/op is (the verdict had
 * two contradictory unverified guesses). Replicates that JMH leg exactly - pooled direct
 * {@code ByteBuf} per op, encode, read-back, release, single thread - under two CRC strategies so a
 * JFR allocation profile can attribute the bytes:
 *   mode=nio       -> CRC via out.nioBuffer(...)         (what the JMH leg / NettyWireEncoders uses)
 *   mode=internal  -> CRC via out.internalNioBuffer(...) (the cached view the in-pipeline encoder uses)
 *
 * <pre>java --enable-preview --enable-native-access=ALL-UNNAMED -XX:StartFlightRecording=... \
 *   -cp benchmarks.jar io.configd.jdkvsnetty.NettyEncodeOnlyProfileMain &lt;payload&gt; &lt;warmup&gt; &lt;measure&gt; &lt;nio|internal&gt;</pre>
 */
public final class NettyEncodeOnlyProfileMain {

    private NettyEncodeOnlyProfileMain() {
    }

    private static final ThreadMXBean TB = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final ThreadLocal<CRC32C> CRC = ThreadLocal.withInitial(CRC32C::new);
    private static final int GROUP_ID = 7, SENDER_ID = 3;
    private static final long TERM = 42L;

    public static void main(String[] args) throws Exception {
        int payload = Integer.parseInt(args[0]);
        long warmup = Long.parseLong(args[1]);
        long measure = Long.parseLong(args[2]);
        boolean internal = args.length > 3 && args[3].equals("internal");

        TB.setThreadAllocatedMemoryEnabled(true);
        MessageType type = (payload == 0) ? MessageType.HEARTBEAT : MessageType.APPEND_ENTRIES;
        byte[] pl = new byte[payload];
        int cap = 4 + FrameCodec.frameSize(payload);
        var alloc = PooledByteBufAllocator.DEFAULT;

        long sink = 0;
        for (long i = 0; i < warmup; i++) {
            sink += encodeOnce(alloc, cap, type, pl, internal);
        }
        System.gc();
        Thread.sleep(150);
        long a0 = TB.getTotalThreadAllocatedBytes();
        for (long i = 0; i < measure; i++) {
            sink += encodeOnce(alloc, cap, type, pl, internal);
        }
        long perMsg = (TB.getTotalThreadAllocatedBytes() - a0) / measure;
        System.out.printf("ENCODE-ONLY mode=%s payload=%d perMsgAllocBytes=%d sink=%d%n",
                internal ? "internal" : "nio", payload, perMsg, sink & 1);
        System.out.flush();
    }

    private static long encodeOnce(PooledByteBufAllocator alloc, int cap, MessageType type,
                                   byte[] pl, boolean internal) {
        ByteBuf buf = alloc.directBuffer(cap);
        try {
            buf.writeInt(SENDER_ID);
            int frameStart = buf.writerIndex();
            int totalLength = FrameCodec.frameSize(pl.length);
            buf.writeInt(totalLength);
            buf.writeByte(FrameCodec.WIRE_VERSION);
            buf.writeByte((byte) type.code());
            buf.writeInt(GROUP_ID);
            buf.writeLong(TERM);
            buf.writeLong(0L); // reserved epoch (dormant, must be zero); byte-identical to FrameCodec.encode
            buf.writeBytes(pl);
            CRC32C crc = CRC.get();
            crc.reset();
            int crcLen = totalLength - FrameCodec.TRAILER_SIZE;
            crc.update(internal ? buf.internalNioBuffer(frameStart, crcLen)
                    : buf.nioBuffer(frameStart, crcLen));
            buf.writeInt((int) crc.getValue());
            return buf.getInt(4);
        } finally {
            buf.release();
        }
    }
}
