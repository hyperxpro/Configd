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
 * Measures the per-frame allocation of the edge {@link EdgeFrameCodec} as the fan-out
 * server / edge client actually exercise it. Run with {@code -prof gc}; the metric is
 * {@code gc.alloc.rate.norm} (B/op).
 *
 * <p><b>Scope (baseline honesty):</b> isolates the wire-codec allocation of a SIGNED delta
 * (the production steady-state shape). Two production costs sit ON TOP of this and are NOT
 * included here: the {@code SSLSocket} write allocation, and {@code FanOutSessionCore}'s
 * per-frame batch assembly. So these B/op are a floor for the per-frame fan-out cost.
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

    private static final int VALUE_BYTES = 64;

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
            // One Put per delta - encodeBatch requires >= 1 mutation. SIGNED, the production
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

    @Benchmark
    public byte[] encodeNotify() {
        return EdgeFrameCodec.encode(notifyFrame);
    }

    @Benchmark
    public EdgeFrame decodeNotify() {
        return EdgeFrameCodec.decode(preEncodedNotify);
    }

    @Benchmark
    public byte[] encodeHeartbeat() {
        return EdgeFrameCodec.encode(heartbeatFrame);
    }
}
