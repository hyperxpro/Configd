package io.configd.bench;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Netty-migration baseline (Phase R) — <b>surface 3: edge fan-out streaming wire</b>
 * ({@code configd-distribution-service}). Measures the per-frame allocation of the edge
 * {@link EdgeFrameCodec} as the fan-out server / edge client actually exercise it
 * ({@code FanOutServer.send} line 644, {@code EdgeStreamClient} line 537 call the
 * allocating {@link EdgeFrameCodec#encode(EdgeFrame)} per frame, then write to a
 * per-connection {@code OutputStream}). Run with {@code -prof gc}; the metric is
 * {@code gc.alloc.rate.norm} (B/op).
 *
 * <h2>Why this is the highest-payoff surface a priori</h2>
 * NOTIFY is the high-volume push frame (every committed delta fans out to every
 * subscriber). Its encode path is allocation-heavy by construction: a per-notification
 * {@code CommandCodec.encodeBatch} blob, an intermediate {@code List<byte[]>}, a
 * per-notification {@code ByteBuffer}, then the final frame array — several allocations
 * per frame, multiplied by the batch size. Decode rebuilds {@code ArrayList}s,
 * {@code ConfigDelta}s, mutation lists, and {@code byte[]}s. This is precisely the term a
 * pooled-{@code ByteBuf} streaming encoder would target.
 *
 * <ul>
 *   <li>{@code encodeNotify} — STATUS QUO server→edge push, {@code notifyCount} deltas
 *       per frame (1 = a single commit; 64 = {@code MAX_NOTIFY_BATCH}, a full coalesced
 *       batch).</li>
 *   <li>{@code decodeNotify} — the edge receive side.</li>
 *   <li>{@code encodeHeartbeat} — the cheap coalesced keep-alive frame, for contrast
 *       (payload-independent; the same tiny frame regardless of {@code notifyCount}).</li>
 * </ul>
 *
 * <p><b>Scope (baseline honesty):</b> isolates the wire-codec allocation of a SIGNED delta
 * (the production steady-state shape) — the term Netty's pooled {@code ByteBuf} / an
 * into-buffer codec rewrite would replace. Two production costs sit ON TOP of this and are
 * NOT included here (both additive, same direction): the {@code SSLSocket} write allocation,
 * and {@code FanOutSessionCore}'s per-frame batch assembly (the growing {@code ArrayList} +
 * a per-candidate sizing pass). So these B/op are a floor for the per-frame fan-out cost.
 *
 * <pre>
 *   java --enable-preview -jar configd-testkit/target/benchmarks.jar \
 *       EdgeWireAllocBenchmark -prof gc -f 2 -wi 3 -i 4
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(value = 2)
public class EdgeWireAllocBenchmark {

    /** Notifications per NOTIFY frame: 1 = a single commit, 64 = MAX_NOTIFY_BATCH. */
    @Param({"1", "16", "64"})
    int notifyCount;

    /** Config value size per mutation (a typical small config blob). */
    private static final int VALUE_BYTES = 64;

    /** Ed25519 signature length — every production delta is signed (mandatory). */
    private static final int ED25519_SIG_BYTES = 64;

    private EdgeFrame.Notify notifyFrame;
    private byte[] preEncodedNotify;
    private EdgeFrame.Heartbeat heartbeatFrame;

    @Setup(Level.Trial)
    public void setUp() {
        byte[] value = new byte[VALUE_BYTES];
        for (int i = 0; i < VALUE_BYTES; i++) {
            value[i] = (byte) i;
        }
        List<CommitNotification> notifications = new ArrayList<>(notifyCount);
        for (int i = 0; i < notifyCount; i++) {
            // One Put per delta — encodeBatch requires >= 1 mutation. SIGNED, the production
            // steady-state shape (ConfigdServer initializes Ed25519 signing as mandatory):
            // a 64-byte Ed25519 signature, a non-zero epoch, and an 8-byte nonce. The bytes'
            // values are irrelevant to allocation; the lengths drive the wire size + the
            // per-notification signature/nonce clones the codec + ConfigDelta accessors make.
            List<ConfigMutation> mutations =
                    List.of(new ConfigMutation.Put("config/service/key-" + i, value));
            byte[] signature = new byte[ED25519_SIG_BYTES];
            byte[] nonce = new byte[ConfigDelta.NONCE_LEN];
            ConfigDelta delta = new ConfigDelta(i, i + 1L, mutations,
                    signature, /* epoch */ i + 1L, nonce);
            notifications.add(new CommitNotification(i + 1L, 1_700_000_000_000L + i, delta));
        }
        notifyFrame = new EdgeFrame.Notify(notifications);
        preEncodedNotify = EdgeFrameCodec.encode(notifyFrame);
        heartbeatFrame = new EdgeFrame.Heartbeat(/* latestSeq */ 12345L,
                /* serverNowMillis */ 1_700_000_000_000L);
    }

    /** STATUS QUO server→edge push: encode a NOTIFY batch of {@code notifyCount} deltas. */
    @Benchmark
    public byte[] encodeNotify() {
        return EdgeFrameCodec.encode(notifyFrame);
    }

    /** Edge receive side: decode the NOTIFY batch back to frames + deltas + mutations. */
    @Benchmark
    public EdgeFrame decodeNotify() {
        return EdgeFrameCodec.decode(preEncodedNotify);
    }

    /** The cheap coalesced keep-alive (payload-independent; for contrast with NOTIFY). */
    @Benchmark
    public byte[] encodeHeartbeat() {
        return EdgeFrameCodec.encode(heartbeatFrame);
    }
}
