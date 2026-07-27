package io.configd.distribution.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullChainDeliveryTest {

    private static final Clock CLOCK = new Clock() {
        @Override public long currentTimeMillis() { return 0L; }
        @Override public long nanoTime() { return 0L; }
    };

    @Test
    void prefixSubscriberReceivesEveryDeltaIncludingNonMatchingKeys() {
        FanOutBuffer buffer = new FanOutBuffer(64);
        String[] keys = {"svc/a", "db/x", "other/z", "svc/b", "db/y", "other/w"};
        for (int i = 0; i < keys.length; i++) {
            long seq = i + 1;
            buffer.publish(put(seq, keys[i], "v" + seq));
        }
        ReplaySource replay = new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY);
        RecordingTransportSink sink = new RecordingTransportSink();
        FanOutSessionCore s = new FanOutSessionCore(buffer, replay, sink,
                FanOutConfig.defaults(), FanOutSessionMetrics.NOOP, CLOCK);

        s.onSubscribe(new EdgeFrame.Subscribe(false, List.of("svc/"), 0L, -1L, "edge-prefix"));
        sink.clear();
        s.tick(0L);

        List<Long> delivered = new ArrayList<>();
        List<String> deliveredKeys = new ArrayList<>();
        for (EdgeFrame f : sink.sent()) {
            if (f instanceof EdgeFrame.Notify n) {
                for (CommitNotification cn : n.notifications()) {
                    delivered.add(cn.seq());
                    cn.delta().mutations().forEach(m -> deliveredKeys.add(m.key()));
                }
            }
        }
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), delivered,
                "the full signed chain must reach a prefix subscriber (ADR-0038)");
        for (String k : keys) {
            assertTrue(deliveredKeys.contains(k),
                    "non-matching key '" + k + "' must still be streamed (no transport filter)");
        }
    }

    @Test
    void fullStoreAndPrefixSubscribersReceiveByteIdenticalChains() {
        FanOutBuffer buffer = new FanOutBuffer(64);
        for (long i = 1; i <= 8; i++) {
            buffer.publish(put(i, (i % 2 == 0 ? "svc/" : "db/") + i, "v" + i));
        }
        ReplaySource replay = new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY);

        List<Long> full = drainSeqs(buffer,
                new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "full"));
        List<Long> prefix = drainSeqs(buffer,
                new EdgeFrame.Subscribe(false, List.of("svc/"), 0L, -1L, "prefix"));
        assertEquals(full, prefix,
                "prefix and full-store subscribers see the identical chain on the wire");
    }

    private static List<Long> drainSeqs(FanOutBuffer buffer, EdgeFrame.Subscribe sub) {
        ReplaySource replay = new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY);
        RecordingTransportSink sink = new RecordingTransportSink();
        FanOutSessionCore s = new FanOutSessionCore(buffer, replay, sink,
                FanOutConfig.defaults(), FanOutSessionMetrics.NOOP, CLOCK);
        s.onSubscribe(sub);
        s.tick(0L);
        List<Long> seqs = new ArrayList<>();
        for (EdgeFrame f : sink.sent()) {
            if (f instanceof EdgeFrame.Notify n) {
                n.notifications().forEach(cn -> seqs.add(cn.seq()));
            }
        }
        return seqs;
    }

    private static CommitNotification put(long seq, String key, String val) {
        return new CommitNotification(seq, 1_000L + seq,
                new ConfigDelta(seq - 1, seq,
                        List.of(new ConfigMutation.Put(key, val.getBytes(StandardCharsets.UTF_8)))));
    }
}
