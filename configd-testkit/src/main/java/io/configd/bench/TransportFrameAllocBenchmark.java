package io.configd.bench;

import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * Netty-migration baseline (Phase R) — <b>surface 4: inter-node consensus wire</b>
 * ({@code configd-transport}). Measures the per-message allocation of the Raft
 * {@link FrameCodec} as the consensus transport actually exercises it, with
 * {@code -prof gc} (the metric is {@code gc.alloc.rate.norm}, B/op).
 *
 * <h2>Why these legs</h2>
 * The production send path ({@code TcpRaftTransport.send}, line 502) calls the
 * <b>allocating</b> {@link FrameCodec#encode(MessageType, int, long, byte[])} per message
 * and writes the returned {@code byte[]} to a <b>per-connection</b> {@code DataOutputStream}
 * — so the per-message, application-controlled transport allocation is exactly one
 * {@code encode} frame array (the stream wrapper is amortised once per connection, not per
 * message; the {@code SSLEngine}'s network buffers are JDK-internal and buffer-reused).
 *
 * <ul>
 *   <li>{@code encodeAllocating} — the STATUS QUO: what {@code TcpRaftTransport} calls today.
 *       Expect ≈ {@code frameSize(payload)} B/op (one heap frame per message).</li>
 *   <li>{@code encodeInto} — the ACHIEVABLE FLOOR with the existing, <b>currently-unused</b>
 *       {@link FrameCodec#encode(ByteBuffer, MessageType, int, long, byte[])} into-variant and
 *       a reused buffer. Expect ≈ 0 B/op. This is the key control: it shows how much of the
 *       status-quo allocation is removable <b>without Netty</b>, just by switching to the
 *       zero-copy variant the codec already ships.</li>
 *   <li>{@code decode} — the receive side ({@code TcpRaftTransport} line 432): allocates the
 *       payload {@code byte[]} + one {@link FrameCodec.Frame} record per message.</li>
 * </ul>
 *
 * <p><b>Scope (baseline honesty):</b> this isolates the wire-codec allocation — the term
 * Netty's pooled {@code ByteBuf} would replace. The {@code SSLSocket} read/write allocation
 * is a separate, harder-to-isolate component; if this surface is convicted it is measured
 * end-to-end during migration. Payload sizes span the real range:
 * {@code 0} (a coalesced heartbeat, the M3 high-frequency message), {@code 256} (a small
 * AppendEntries), {@code 4096} (a batched AppendEntries).
 *
 * <pre>
 *   java --enable-preview -jar configd-testkit/target/benchmarks.jar \
 *       TransportFrameAllocBenchmark -prof gc -f 2 -wi 3 -i 4
 * </pre>
 */
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

    /** The codec frame array alone (one component of the send path; see encodeSendWire). */
    @Benchmark
    public byte[] encodeAllocating() {
        return FrameCodec.encode(type, groupId, term, payload);
    }

    /**
     * TRUE PRODUCTION SEND: what {@code TcpRaftTransport.encodeWire()} allocates per message —
     * the codec frame PLUS a second {@code byte[4 + frame]} to prepend the 4-byte sender node
     * id ({@code System.arraycopy}). This ≈2× the frame is the real per-message send
     * allocation; {@code encodeAllocating} is only the codec half. The achievable floor folds
     * the sender id into one reused buffer alongside the frame (cf. {@code encodeInto}) — a
     * slightly larger in-place change than swapping in the encode-into variant alone.
     */
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

    /**
     * ACHIEVABLE FLOOR: the zero-copy into-variant the codec already ships but the transport
     * does not use. Reads the length prefix back so the writes cannot be dead-code-eliminated.
     */
    @Benchmark
    public int encodeInto() {
        reuseBuf.clear();
        FrameCodec.encode(reuseBuf, type, groupId, term, payload);
        return reuseBuf.getInt(0);
    }

    /** RECEIVE SIDE: decode allocates the payload byte[] + one Frame record per message. */
    @Benchmark
    public FrameCodec.Frame decode() {
        return FrameCodec.decode(preEncoded);
    }

    /** Full per-message round trip both ends (encode allocating + decode). */
    @Benchmark
    public void roundTrip(Blackhole bh) {
        byte[] wire = FrameCodec.encode(type, groupId, term, payload);
        bh.consume(FrameCodec.decode(wire));
    }
}
