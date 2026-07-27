package io.configd.bench;

import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(value = 2)
public class TransportFrameAllocBenchmark {

    /** Payload size: 0 = coalesced heartbeat, 256 = small append, 4096 = batched append. */
    @Param({"0", "256", "4096"})
    int payloadBytes;

    private MessageType type;
    private int groupId;
    private long term;
    private int senderId;
    private byte[] payload;
    private byte[] preEncoded;   // a complete frame, for the decode leg
    private ByteBuffer reuseBuf; // reused destination for the zero-copy encode leg

    @Setup(Level.Trial)
    public void setUp() {
        // A heartbeat carries no payload; the larger sizes stand in for AppendEntries bodies.
        type = (payloadBytes == 0) ? MessageType.HEARTBEAT : MessageType.APPEND_ENTRIES;
        groupId = 7;
        term = 42L;
        senderId = 3;
        payload = new byte[payloadBytes];
        for (int i = 0; i < payloadBytes; i++) {
            payload[i] = (byte) i;
        }
        preEncoded = FrameCodec.encode(type, groupId, term, payload);
        reuseBuf = ByteBuffer.allocate(FrameCodec.frameSize(payloadBytes));
    }

    @Benchmark
    public byte[] encodeAllocating() {
        return FrameCodec.encode(type, groupId, term, payload);
    }

    // Production send: frame + sender-id prepend = ~2x frame allocation (not just codec half).
    @Benchmark
    public byte[] encodeSendWire() {
        byte[] encoded = FrameCodec.encode(type, groupId, term, payload);
        byte[] wire = new byte[4 + encoded.length];
        wire[0] = (byte) (senderId >>> 24);
        wire[1] = (byte) (senderId >>> 16);
        wire[2] = (byte) (senderId >>> 8);
        wire[3] = (byte) senderId;
        System.arraycopy(encoded, 0, wire, 4, encoded.length);
        return wire;
    }

    @Benchmark
    public int encodeInto() {
        reuseBuf.clear();
        FrameCodec.encode(reuseBuf, type, groupId, term, payload);
        return reuseBuf.getInt(0);
    }

    @Benchmark
    public FrameCodec.Frame decode() {
        return FrameCodec.decode(preEncoded);
    }

    @Benchmark
    public void roundTrip(Blackhole bh) {
        byte[] wire = FrameCodec.encode(type, groupId, term, payload);
        bh.consume(FrameCodec.decode(wire));
    }
}
