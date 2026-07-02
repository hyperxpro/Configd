package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The flag-OFF byte-identity proof (ADR-0044 test (b)): with server-side filtering off, a
 * prefix session's emitted frame stream is identical to the classic full-chain path, and the
 * genuine-eviction gap path is unchanged (server-side gap detection is preserved). The flag is
 * the only switch: the SAME subscribe under flag-ON diverges (it filters).
 */
class FanOutFilterDivergenceTest {

    private static ReplaySource snapshotAt(long version) {
        return new SnapshotReplaySource(() ->
                new ConfigSnapshot(HamtMap.<String, VersionedValue>empty(), version, 0L));
    }

    private static CommitNotification put(long seq, String key) {
        return new CommitNotification(seq, 1_000L + seq,
                new ConfigDelta(seq - 1, seq,
                        List.of(new ConfigMutation.Put(key, "v".getBytes(StandardCharsets.UTF_8)))));
    }

    /** Runs a session over a fixed input and returns the frames it emitted. */
    private static List<EdgeFrame> drain(FanOutConfig cfg, EdgeFrame.Subscribe subscribe) {
        FakeClock clock = new FakeClock(1_000L);
        RecordingTransportSink sink = new RecordingTransportSink();
        FanOutBuffer buffer = new FanOutBuffer(64);
        FanOutSessionCore s = new FanOutSessionCore(buffer, snapshotAt(0), sink, cfg,
                FanOutSessionMetrics.NOOP, clock);
        s.onSubscribe(subscribe);
        for (long i = 1; i <= 10; i++) {
            buffer.publish(put(i, (i % 2 == 0 ? "svc/k" : "other/k") + i));
        }
        s.tick(clock.currentTimeMillis());
        return sink.sent();
    }

    @Test
    void flagOffIsByteIdenticalToClassicFullChain() {
        FanOutConfig flagOff = FanOutConfig.defaults(); // serverSidePrefixFilter == false
        // A prefix subscribe that opted in, but the deployment posture is OFF -> classic path.
        List<EdgeFrame> prefixFlagOff = drain(flagOff,
                new EdgeFrame.Subscribe(false, List.of("svc/"), 0L, -1L, "edge-1", true));
        // The classic full-store baseline over the identical input.
        List<EdgeFrame> classic = drain(flagOff,
                new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-1", false));

        // Same frames (the flag-OFF prefix session delivers the whole chain, like full-store).
        assertEquals(classic, prefixFlagOff,
                "flag-OFF filtering emits the identical frame stream as the classic full-chain path");
        // And byte-identical on the wire (deterministic encode).
        for (int i = 0; i < classic.size(); i++) {
            assertArrayEquals(EdgeFrameCodec.encode(classic.get(i)),
                    EdgeFrameCodec.encode(prefixFlagOff.get(i)),
                    "frame " + i + " must be byte-identical under flag-OFF");
        }
    }

    @Test
    void flagOnDivergesFromClassic() {
        FanOutConfig flagOn = FanOutConfig.defaults().withServerSidePrefixFilter(true, Set.of("secure/"));
        List<EdgeFrame> filtered = drain(flagOn,
                new EdgeFrame.Subscribe(false, List.of("svc/"), 0L, -1L, "edge-1", true));
        List<EdgeFrame> classic = drain(FanOutConfig.defaults(),
                new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-1", false));
        assertNotEquals(classic, filtered,
                "flag-ON filtering diverges from the classic path (it drops the other/ deltas)");
        // The filtered stream carries fewer NOTIFY notifications (only svc/).
        int filteredCount = filtered.stream().filter(f -> f instanceof EdgeFrame.Notify)
                .mapToInt(f -> ((EdgeFrame.Notify) f).notifications().size()).sum();
        int classicCount = classic.stream().filter(f -> f instanceof EdgeFrame.Notify)
                .mapToInt(f -> ((EdgeFrame.Notify) f).notifications().size()).sum();
        assertEquals(5, filteredCount);
        assertEquals(10, classicCount);
    }

    @Test
    void genuineEvictionStillDemotesUnderFiltering() {
        // The handleGap path is untouched by filtering: a cursor whose successor was evicted still
        // demotes to a snapshot (server-side gap detection preserved). A tiny ring + a stale cursor.
        FakeClock clock = new FakeClock(1_000L);
        RecordingTransportSink sink = new RecordingTransportSink();
        FanOutConfig cfg = FanOutConfig.defaults().withServerSidePrefixFilter(true, Set.of("secure/"));
        FanOutBuffer buffer = new FanOutBuffer(4);
        FanOutSessionCore s = new FanOutSessionCore(buffer, snapshotAt(20), sink, cfg,
                FanOutSessionMetrics.NOOP, clock);
        // Fill past capacity so early seqs are evicted, then subscribe with a stale cursor (1).
        for (long i = 1; i <= 10; i++) {
            buffer.publish(put(i, "svc/k" + i));
        }
        s.onSubscribe(new EdgeFrame.Subscribe(false, List.of("svc/"), 1L, -1L, "edge-1", true));
        // decideMode sees the readSince(1) GAP and chooses SNAPSHOT_FIRST (re-bootstrap), exactly
        // as it would on the classic path - filtering does not mask a genuine fall-behind.
        assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST,
                sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).mode());
    }
}
